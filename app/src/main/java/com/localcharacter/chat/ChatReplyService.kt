package com.localcharacter.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.content.pm.PackageManager
import android.Manifest
import kotlinx.coroutines.CancellationException
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ChatReplyService : Service() {
    companion object {
        const val ACTION_GENERATE = "com.localcharacter.chat.action.GENERATE"
        const val ACTION_REPLY_READY = "com.localcharacter.chat.action.REPLY_READY"
        const val EXTRA_ERROR = "error"

        private const val CHANNEL_WORK = "local_generation"
        private const val CHANNEL_REPLIES = "character_replies"
        private const val FOREGROUND_ID = 1401
        private const val REPLY_ID = 1402
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var running = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_GENERATE || running) return START_NOT_STICKY
        running = true

        val initialStore = ChatStore(this)
        startForeground(
            FOREGROUND_ID,
            buildWorkingNotification(initialStore.settings.characterName),
        )

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PrivateCharacterChat:reply")
            .apply { acquire(10 * 60 * 1000L) }
        scope.launch {
            var errorMessage: String? = null
            try {
                val store = ChatStore(this@ChatReplyService)
                val latestUserMessage = store.conversation.messages
                    .lastOrNull { it.role == "user" }
                    ?: throw IllegalStateException("There is no user message to answer.")
                val latestUser = latestUserMessage.text

                SceneDirector.applyDirectives(
                    settings = store.settings,
                    state = store.conversation,
                    rawMessage = latestUser,
                    sourceMessageId = latestUserMessage.id,
                )

                BrainEngine.prepareForReply(
                    settings = store.settings,
                    state = store.conversation,
                    latestUserMessage = latestUserMessage,
                )
                // BrainEngine has already refreshed memory/context. Persist the
                // result without immediately rebuilding it again.
                store.persistConversationOnly()

                val plan = ReplyPipeline.buildPlan(
                    settings = store.settings,
                    conversation = store.conversation,
                    previousMetrics = store.generationMetrics,
                )

                var result = LlmRuntime.engine.completeFor(
                    settings = store.settings,
                    prompt = plan.conversationPrompt,
                    systemPrompt = plan.systemPrompt,
                    maxTokens = plan.maxTokens,
                )

                var checked = ReplyPipeline.evaluate(
                    result.text, store.settings, store.conversation, plan,
                    hitTokenLimit = result.tokensGenerated >= plan.maxTokens,
                )
                if (!checked.usable && store.settings.roleplay.repairInvalidReply) {
                    val retry = LlmRuntime.engine.completeFor(
                    settings = store.settings,
                        prompt = ReplyPipeline.repairPrompt(plan, checked),
                        systemPrompt = plan.systemPrompt,
                        maxTokens = plan.maxTokens,
                    )
                    checked = ReplyPipeline.evaluate(retry.text, store.settings, store.conversation,
                        plan, retry.tokensGenerated >= plan.maxTokens)
                    // Timings reflect both passes, not just the faster successful pass.
                    result = retry.copy(
                        promptEvalTimeMs = result.promptEvalTimeMs + retry.promptEvalTimeMs,
                        generateTimeMs = result.generateTimeMs + retry.generateTimeMs,
                    )
                }
                if (!checked.usable) throw IllegalStateException(
                    "No reply was posted. " + checked.issues.joinToString(" ") +
                        " Try Retry or adjust the character/scene settings.",
                )
                val photoMarker = Regex("""(?iu)\[\[SEND_PHOTO(?::([^]]{1,100}))?]]""").find(ReplyPipeline.stripReasoning(result.text).first)
                val requestedPhotoHint = photoMarker?.groupValues?.getOrNull(1)?.trim().orEmpty()
                val rawPhotoMarker = photoMarker != null
                val cleanedReply = checked.text
                val reply = HumanTypingStyle.apply(
                    text = cleanedReply,
                    settings = store.settings,
                    interactionCount = store.conversation.brain.interactionCount,
                    sceneMode = plan.responseMode != "texting",
                )
                val characterPhoto = chooseCharacterPhoto(
                    store = store,
                    latestUser = latestUser,
                    rawPhotoMarker = rawPhotoMarker,
                    requestedHint = requestedPhotoHint,
                    reply = reply,
                )
                require(characterPhoto.isNotBlank() || !PhotoSharePolicy.claimsAttachment(reply)) {
                    "The draft promised an unavailable or disallowed photo. Nothing was posted. Check photo settings or retry."
                }
                store.saveGenerationMetrics(
                    PerformanceController.updatedMetrics(
                        previous = store.generationMetrics,
                        result = result,
                        plan = plan,
                    ),
                )
                val chosenPhoto = findPhotoMetadata(store.settings, characterPhoto)
                val assistantMessage = ChatMessage(
                    role = "assistant",
                    text = reply.ifBlank { chosenPhoto?.caption.orEmpty() },
                    imagePath = characterPhoto,
                    imageDescription = chosenPhoto?.description.orEmpty(),
                )
                // Editing controls are disabled during generation; still guard against stale commits.
                check(store.conversation.messages.lastOrNull { it.role == "user" }?.id == latestUserMessage.id) {
                    "Conversation changed while a reply was running. The stale reply was not posted."
                }
                store.conversation.messages += assistantMessage
                BrainEngine.finishReply(
                    settings = store.settings,
                    state = store.conversation,
                    userMessage = latestUserMessage,
                    assistantMessage = assistantMessage,
                )
                store.persistConversationOnly()
                store.markGenerationFinished()

                if (!LlmRuntime.appVisible) {
                    showReplyNotification(store.settings.characterName, reply)
                }
            } catch (error: Exception) {
                errorMessage = error.message ?: error.javaClass.simpleName
                ChatStore(this@ChatReplyService).markGenerationFinished()
                if (!LlmRuntime.appVisible) {
                    showFailureNotification(errorMessage ?: "Reply generation failed")
                }
            } finally {
                sendBroadcast(
                    Intent(ACTION_REPLY_READY)
                        .setPackage(packageName)
                        .putExtra(EXTRA_ERROR, errorMessage),
                )
                wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
                running = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        super.onDestroy()
    }

    private fun chooseCharacterPhoto(
        store: ChatStore,
        latestUser: String,
        rawPhotoMarker: Boolean,
        requestedHint: String,
        reply: String,
    ): String {
        val recent = store.conversation.messages.filter { it.role == "assistant" && it.imagePath.isNotBlank() }.takeLast(4).map { it.imagePath }
        val lastPhoto = store.conversation.messages.indexOfLast { it.role == "assistant" && it.imagePath.isNotBlank() }
        val turns = if (lastPhoto < 0) Int.MAX_VALUE else store.conversation.messages.drop(lastPhoto + 1).count { it.role == "assistant" }
        return PhotoSharePolicy.choose(store.settings.photoSharing, CharacterPhotoSelector.available(store.settings),
            latestUser, reply, if (rawPhotoMarker) requestedHint.ifBlank { "photo" } else "",
            recentPaths = recent, turnsSincePhoto = turns)?.path.orEmpty()

    }

    private fun findPhotoMetadata(settings: CharacterSettings, path: String): CharacterPhoto? =
        CharacterPhotoSelector.available(settings).firstOrNull { it.path == path }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_WORK,
                "Background replies",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows while a local reply is being generated."
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REPLIES,
                "Character replies",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Notifies you when a local character reply is ready."
            },
        )
    }

    private fun buildWorkingNotification(characterName: String): Notification {
        val builder = notificationBuilder(CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(characterName)
            .setContentText("Typing…")
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        return builder.build()
    }

    private fun showReplyNotification(characterName: String, reply: String) {
        if (!canNotify()) return
        val notification = notificationBuilder(CHANNEL_REPLIES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(characterName)
            .setContentText(reply)
            .setStyle(Notification.BigTextStyle().bigText(reply))
            .setContentIntent(openAppPendingIntent())
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(REPLY_ID, notification)
    }

    private fun showFailureNotification(message: String) {
        if (!canNotify()) return
        val notification = notificationBuilder(CHANNEL_REPLIES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Reply failed")
            .setContentText(message.take(140))
            .setContentIntent(openAppPendingIntent())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(REPLY_ID, notification)
    }

    private fun canNotify(): Boolean = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            77,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    @Suppress("DEPRECATION")
    private fun notificationBuilder(channelId: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            Notification.Builder(this)
        }
}
