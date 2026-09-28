package com.localcharacter.chat

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

object SocialGeneration {
    val active = AtomicBoolean(false)
    @Volatile var cancelRequested = false
    @Volatile var postId = ""
    @Volatile var status = ""
    @Volatile var visible = false
    @Volatile var lastError = ""
}

/** One user-triggered finite group of up to three local replies, never a self-perpetuating bot loop. */
class SocialReplyService : Service() {
    companion object {
        const val UPDATE = "com.localcharacter.chat.SOCIAL_UPDATE"
        const val CANCEL = "com.localcharacter.chat.SOCIAL_CANCEL"
        private const val CHANNEL = "social_local_generation"
        private const val READY = "social_replies"
        private const val WORK_ID = 2401
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wake: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(NotificationChannel(CHANNEL, "Local comment generation", NotificationManager.IMPORTANCE_LOW))
            createNotificationChannel(NotificationChannel(READY, "Replies in your private feed", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) { SocialGeneration.cancelRequested = true; return START_NOT_STICKY }
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        if (!SocialGeneration.active.compareAndSet(false, true)) return START_NOT_STICKY
        val postId = intent.getStringExtra("post").orEmpty()
        val targetId = intent.getStringExtra("target").orEmpty()
        val actors = intent.getStringArrayListExtra("actors").orEmpty().distinct().take(3)
        SocialGeneration.postId = postId; SocialGeneration.cancelRequested = false; SocialGeneration.lastError = ""
        startForeground(WORK_ID, working("Preparing local replies…", postId))
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:socialReply").apply { acquire(15 * 60 * 1000L) }
        scope.launch {
            try {
                require(!ChatStore(this@SocialReplyService).isGenerationPending()) { "A private reply is still running. Wait for it to finish." }
                val store = SocialStore(this@SocialReplyService)
                for (actorId in actors) {
                    if (SocialGeneration.cancelRequested) break
                    val world = store.read()
                    val post = world.posts.find { it.id == postId } ?: break
                    val actor = world.characters.find { it.id == actorId && it.enabled } ?: continue
                    val base = ChatStore(this@SocialReplyService).settings
                    val settings = SocialReplyPolicy.effectiveSettings(base, actor)
                    require(settings.modelPath.isNotBlank()) { "Choose a local GGUF in Chats → model menu first." }
                    actor.mood = SocialBrain.replay(world, actor, targetId, settings)
                    val plan = SocialReplyPolicy.build(world, postId, targetId, actorId, base)
                    val revision = post.revision
                    val brainInput = world.comments.find { it.id == targetId }
                    val name = settings.characterName
                    SocialGeneration.status = "$name is writing…"; changed()
                    getSystemService(NotificationManager::class.java).notify(WORK_ID, working(SocialGeneration.status, postId))
                    val result = LlmRuntime.engine.completeFor(settings, plan.prompt, plan.system, plan.maxTokens)
                    if (SocialGeneration.cancelRequested) break
                    val clean = SocialReplyPolicy.clean(result.text, world, base, actorId, result.tokensGenerated >= plan.maxTokens)
                    val reply = HumanTypingStyle.apply(clean, settings, world.comments.size, false)
                    val tag = Regex("""(?i)\[\[SEND_PHOTO(?::([^]]*))?]]""").find(ReplyPipeline.stripReasoning(result.text).first)?.groupValues?.getOrNull(1).orEmpty()
                    val prior = world.comments.filter { it.authorId == actorId && it.imagePath.isNotBlank() }.takeLast(4).map { it.imagePath }
                    val actorHistory = world.comments.filter { it.authorId == actorId }
                    val lastPhotoIndex = actorHistory.indexOfLast { it.imagePath.isNotBlank() }
                    val photoGap = if (lastPhotoIndex < 0) Int.MAX_VALUE else actorHistory.size - lastPhotoIndex - 1
                    val chosen = PhotoSharePolicy.choose(settings.photoSharing, CharacterPhotoSelector.available(settings),
                        brainInput?.text ?: post.caption, reply, tag, public = true, recentPaths = prior, turnsSincePhoto = photoGap)
                    require(chosen != null || !PhotoSharePolicy.claimsAttachment(reply)) { "The draft promised an unavailable or disallowed photo. Nothing was posted; update the gallery or retry." }
                    var saved = false
                    store.edit { current ->
                        val live = current.posts.find { it.id == postId }
                        // Results from a deleted/edited branch or changed scene are discarded, not appended to stale context.
                        if (live != null && live.revision == revision && (targetId.isBlank() || current.comments.any { it.id == targetId })) {
                            current.comments += FeedComment(postId = postId, authorId = actorId, text = reply,
                                parentId = targetId, imagePath = chosen?.path.orEmpty(), generated = true)
                            live.revision++
                            current.characters.find { it.id == actorId }?.mood = actor.mood.copy()
                            val scene = if (live.scene.director.nextReply == plan.nextDirection && plan.nextDirection.isNotBlank()) live.scene
                                else if (current.globalScene.director.nextReply == plan.nextDirection) current.globalScene else null
                            scene?.director?.let { it.nextReply = ""; it.nextReplyFor = "" }
                            saved = true
                        }
                    }
                    if (saved) { changed(); notifyReply(name, reply, postId) }
                    else throw IllegalStateException("The post or reply branch changed. The old draft was discarded; retry on the updated thread.")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                SocialGeneration.lastError = e.message ?: "The local reply failed."
                changed(SocialGeneration.lastError)
                notifyReply("Reply not sent", "Open the post to inspect the error and retry.", postId)
            }
            finally {
                wake?.let { if (it.isHeld) it.release() }; wake = null
                SocialGeneration.status = ""; SocialGeneration.active.set(false); changed()
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); wake?.let { if (it.isHeld) it.release() }; super.onDestroy() }
    private fun changed(error: String? = null) = sendBroadcast(Intent(UPDATE).setPackage(packageName).putExtra("error", error))
    private fun pending(postId: String) = PendingIntent.getActivity(this, postId.hashCode(), Intent(this, SocialActivity::class.java)
        .putExtra("post", postId).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    @Suppress("DEPRECATION") private fun builder(channel: String) = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channel) else Notification.Builder(this)
    private fun working(text: String, postId: String) = builder(CHANNEL).setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Local Circle").setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(pending(postId)).build()
    private fun notifyReply(name: String, text: String, postId: String) {
        if (SocialGeneration.visible) return
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        getSystemService(NotificationManager::class.java).notify(postId.hashCode(), builder(READY).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(name).setContentText(text.take(160)).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(pending(postId)).setAutoCancel(true).build())
    }
}
