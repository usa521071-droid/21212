package com.localcharacter.chat

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.util.LruCache
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlin.math.roundToInt

class MainActivity : Activity() {
    companion object {
        private const val REQ_MODEL = 5001
        private const val REQ_AVATAR = 5002
        private const val REQ_CHAT_IMAGE = 5003
        private const val REQ_CHARACTER_PHOTO = 5004
        private const val GREEN_DARK = 0xFF075E54.toInt()
        private const val GREEN = 0xFF128C7E.toInt()
        private const val PAPER = 0xFFEFEAE2.toInt()
        private const val TEXT = 0xFF111B21.toInt()
        private const val MUTED = 0xFF667781.toInt()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var store: ChatStore
    private val engine: LocalLlmEngine
        get() = LlmRuntime.engine

    private lateinit var rootView: LinearLayout
    private lateinit var headerView: View
    private lateinit var composerView: View
    private lateinit var messageArea: LinearLayout
    private lateinit var messageScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private lateinit var redoButton: Button
    private lateinit var sceneButton: Button
    private lateinit var nameView: TextView
    private lateinit var statusView: TextView
    private lateinit var avatarView: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var modelChip: TextView

    private var busy = false
    private var modelLoading = false
    private var modelLoadError: String? = null
    private var typingBubble: View? = null
    private var replyReceiverRegistered = false
    private var visibleMessageLimit = 160

    private val bitmapCache = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val replyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ChatReplyService.ACTION_REPLY_READY) return
            store = ChatStore(this@MainActivity)
            store.removeBrokenAssistantMessages()
            typingBubble = null
            setBusy(store.isGenerationPending())
            renderMessages()
            val error = intent.getStringExtra(ChatReplyService.EXTRA_ERROR)
            if (!error.isNullOrBlank()) showError("Reply not posted", error)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        store = ChatStore(this)
        store.removeBrokenAssistantMessages()
        setContentView(buildScreen())
        configureInsets()
        refreshHeader()
        renderMessages()
        requestNotificationPermissionIfNeeded()

        if (!store.isAgeConfirmed()) {
            showAgeGate()
        } else {
            startAutomaticModelLoad(showPickerWhenMissing = true)
        }
    }

    override fun onStart() {
        super.onStart()
        LlmRuntime.appVisible = true
        if (!replyReceiverRegistered) {
            val filter = IntentFilter(ChatReplyService.ACTION_REPLY_READY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(replyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(replyReceiver, filter)
            }
            replyReceiverRegistered = true
        }
        store = ChatStore(this)
        store.removeBrokenAssistantMessages()
        val pending = store.isGenerationPending()
        typingBubble = if (pending) createTypingBubble() else null
        setBusy(pending)
        renderMessages()
    }

    override fun onStop() {
        LlmRuntime.appVisible = false
        if (replyReceiverRegistered) {
            unregisterReceiver(replyReceiver)
            replyReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildScreen(): View {
        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PAPER)
        }

        headerView = buildHeader()
        rootView.addView(headerView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(68),
        ))

        messageScroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(PAPER)
        }
        messageArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(12), dp(10), dp(18))
        }
        messageScroll.addView(messageArea, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        rootView.addView(messageScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        composerView = buildComposer()
        rootView.addView(composerView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        return rootView
    }

    private fun configureInsets() {
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN,
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        window.setDecorFitsSystemWindows(false)
        rootView.setOnApplyWindowInsetsListener { _, insets ->
            val topInsets = insets.getInsets(
                WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout(),
            )
            val navigation = insets.getInsets(WindowInsets.Type.navigationBars())
            val keyboard = insets.getInsets(WindowInsets.Type.ime())
            val bottomInset = maxOf(navigation.bottom, keyboard.bottom)

            headerView.setPadding(dp(12), dp(6) + topInsets.top, dp(8), dp(6))
            headerView.layoutParams = headerView.layoutParams.apply {
                height = dp(68) + topInsets.top
            }
            composerView.setPadding(dp(8), dp(7), dp(8), dp(7) + bottomInset)
            insets
        }
        rootView.requestApplyInsets()
    }

    private fun buildHeader(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(8), dp(6))
            setBackgroundColor(0xFFF0F2F5.toInt())
            elevation = dp(2).toFloat()
        }

        avatarView = ImageView(this).apply {
            setBackgroundResource(com.localcharacter.chat.R.drawable.avatar_background)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        bar.addView(avatarView, LinearLayout.LayoutParams(dp(46), dp(46)))

        val contact = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
        }
        val topName = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nameView = TextView(this).apply {
            textSize = 17f
            setTextColor(TEXT)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
        }
        topName.addView(nameView)

        statusView = TextView(this).apply {
            textSize = 12f
            setTextColor(MUTED)
        }
        contact.addView(topName)
        contact.addView(statusView)
        bar.addView(contact, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val modelButton = headerButton("◉", "Model") { showModelDialog(firstRun = false) }
        val settingsButton = headerButton("⋮", "Settings") { showSettingsDialog() }
        bar.addView(modelButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(settingsButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        return bar
    }

    private fun headerButton(text: String, description: String, action: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            contentDescription = description
            textSize = 23f
            setTextColor(0xFF54656F.toInt())
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, 0, 0, 0)
            setOnClickListener { action() }
        }

    private fun buildComposer(): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(7))
            setBackgroundColor(0xFFF0F2F5.toInt())
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }

        sceneButton = Button(this).apply {
            text = "*"
            textSize = 19f
            setTextColor(0xFF54656F.toInt())
            setBackgroundColor(Color.TRANSPARENT)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            contentDescription = "Scene director; hold to insert asterisks"
            setPadding(0, 0, 0, 0)
            setOnClickListener { openRoleplayStudio(director = true) }
            setOnLongClickListener { insertSceneDirectionMarkers(); true }
        }
        row.addView(sceneButton, LinearLayout.LayoutParams(dp(38), dp(46)))

        redoButton = Button(this).apply {
            text = "↻"
            textSize = 20f
            setTextColor(0xFF54656F.toInt())
            setBackgroundColor(Color.TRANSPARENT)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            contentDescription = "Redo last reply"
            setPadding(0, 0, 0, 0)
            setOnClickListener { redoLastReply() }
        }
        row.addView(redoButton, LinearLayout.LayoutParams(dp(42), dp(46)))

        val attachButton = Button(this).apply {
            text = "＋"
            textSize = 22f
            setTextColor(0xFF54656F.toInt())
            setBackgroundColor(Color.TRANSPARENT)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            contentDescription = "Photo options"
            setPadding(0, 0, 0, 0)
            setOnClickListener { showPhotoSendMenu() }
        }
        row.addView(attachButton, LinearLayout.LayoutParams(dp(38), dp(46)))

        input = EditText(this).apply {
            hint = "Message or *scene direction*"
            textSize = 16f
            setTextColor(TEXT)
            setHintTextColor(0xFF8696A0.toInt())
            setBackgroundResource(com.localcharacter.chat.R.drawable.input_background)
            minHeight = dp(46)
            maxHeight = dp(150)
            maxLines = 6
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(14), dp(9), dp(14), dp(9))
        }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        sendButton = Button(this).apply {
            text = "➤"
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(GREEN)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener { sendMessage() }
        }
        row.addView(sendButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            marginStart = dp(7)
        })

        modelChip = TextView(this).apply {
            textSize = 10f
            setTextColor(MUTED)
            maxLines = 1
            visibility = View.GONE
        }
        progress = ProgressBar(this).apply {
            visibility = View.GONE
        }

        outer.addView(row)
        return outer
    }

    private fun refreshHeader() {
        val settings = store.settings
        nameView.text = settings.characterName
        modelChip.text = when {
            settings.modelDisplayName.isNotBlank() -> settings.modelDisplayName
            settings.modelPath.isNotBlank() -> File(settings.modelPath).name
            else -> "No model"
        }

        val baseStatus = when {
            modelLoading -> "connecting…"
            busy || store.isGenerationPending() -> "typing…"
            engine.isLoaded -> "online"
            !modelLoadError.isNullOrBlank() -> "offline"
            settings.modelPath.isNotBlank() -> "connecting…"
            else -> "offline"
        }
        val moodStatus = BrainEngine.headerMood(settings, store.conversation)
        val sceneStatus = if (store.conversation.sceneState.active) "scene" else ""
        val extras = listOf(sceneStatus, moodStatus).filter(String::isNotBlank).joinToString(" • ")
        statusView.text = if (
            extras.isNotBlank() &&
            baseStatus != "typing…" &&
            baseStatus != "connecting…"
        ) {
            "$baseStatus • $extras"
        } else {
            baseStatus
        }

        val avatar = settings.avatarPath.takeIf { it.isNotBlank() }?.let(::File)
        if (avatar != null && avatar.exists()) {
            avatarView.setImageBitmap(loadThumbnail(avatar, dp(96)))
        } else {
            avatarView.setImageDrawable(null)
            avatarView.setBackgroundResource(com.localcharacter.chat.R.drawable.avatar_background)
        }
    }

    private fun renderMessages() {
        messageArea.removeAllViews()
        val allMessages = store.conversation.messages
        if (allMessages.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No messages yet"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(MUTED)
                setPadding(dp(24), dp(80), dp(24), dp(24))
            }
            messageArea.addView(empty, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        } else {
            val hiddenCount = (allMessages.size - visibleMessageLimit).coerceAtLeast(0)
            if (hiddenCount > 0) {
                val loadOlder = Button(this).apply {
                    text = "Load 120 older messages ($hiddenCount hidden)"
                    isAllCaps = false
                    setOnClickListener {
                        visibleMessageLimit = (visibleMessageLimit + 120)
                            .coerceAtMost(store.conversation.messages.size)
                        renderMessages()
                    }
                }
                messageArea.addView(loadOlder, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(8)
                })
            }
            allMessages.takeLast(visibleMessageLimit).forEach(::addMessageBubble)
        }
        typingBubble?.let { messageArea.addView(it) }
        updateComposerState()
        scrollToBottom()
    }

    private fun addNotice(text: String) {
        val notice = TextView(this).apply {
            this.text = text
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTextColor(0xFF5D5B53.toInt())
            setBackgroundColor(0xFFFFEECD.toInt())
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(12)
        }
        messageArea.addView(notice, params)
    }

    private fun addMessageBubble(message: ChatMessage) {
        if (store.settings.roleplay.showDirectorCards && message.role == "user" && SceneDirector.isSceneOnly(message.text)) {
            val card = TextView(this).apply {
                text = "DIRECTOR · not spoken\n" + SceneDirector.parse(message.text).directions.joinToString("\n")
                textSize = 12f
                setTextColor(0xFF465666.toInt())
                setBackgroundColor(0xFFE4E9EF.toInt())
                setPadding(dp(12), dp(9), dp(12), dp(9))
                setOnLongClickListener { showUserMessageActions(message); true }
            }
            messageArea.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(7); bottomMargin = dp(7) })
            return
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (message.role == "user") Gravity.END else Gravity.START
        }

        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setBackgroundResource(
                if (message.role == "user") com.localcharacter.chat.R.drawable.bubble_outgoing
                else com.localcharacter.chat.R.drawable.bubble_incoming,
            )
            setPadding(dp(8), dp(7), dp(8), dp(6))
            isLongClickable = true
            setOnLongClickListener {
                if (message.role == "assistant") {
                    showAssistantMessageActions(message)
                } else {
                    showUserMessageActions(message)
                }
                true
            }
        }

        if (message.imagePath.isNotBlank()) {
            val imageFile = File(message.imagePath)
            if (imageFile.exists()) {
                val bitmap = loadThumbnail(imageFile, dp(480))
                if (bitmap != null) {
                    val image = ImageView(this).apply {
                        setImageBitmap(bitmap)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        adjustViewBounds = true
                        contentDescription =
                            if (message.role == "user") "Sent photo" else "${store.settings.characterName} photo"
                        setOnLongClickListener {
                            bubble.performLongClick()
                            true
                        }
                    }
                    bubble.addView(image, LinearLayout.LayoutParams(dp(240), dp(240)).apply {
                        bottomMargin = if (message.text.isNotBlank()) dp(6) else 0
                    })
                }
            }
        }

        val textView = TextView(this).apply {
            text = buildString {
                if (message.text.isNotBlank()) {
                    append(message.text)
                    append("\n")
                }
                append(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.timestamp)))
                when (message.feedback) {
                    1 -> append("  👍")
                    -1 -> append("  👎")
                }
                if (message.corrected) append("  ✓")
            }
            textSize = 15f
            setTextColor(TEXT)
            setLineSpacing(0f, 1.08f)
            maxWidth = (resources.displayMetrics.widthPixels * 0.84f).roundToInt()
            setOnLongClickListener {
                bubble.performLongClick()
                true
            }
        }
        bubble.addView(textView)

        row.addView(bubble)
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(2)
            bottomMargin = dp(2)
            if (message.role == "user") leftMargin = dp(34) else rightMargin = dp(34)
        }
        messageArea.addView(row, params)
    }

    private fun loadThumbnail(file: File, targetPixels: Int): Bitmap? {
        val key = "${file.absolutePath}:${file.lastModified()}:$targetPixels"
        bitmapCache.get(key)?.let { return it }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        while (largest / sample > targetPixels * 2 && sample < 16) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)?.also {
            bitmapCache.put(key, it)
        }
    }

    private fun createTypingBubble(): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.START
        }
        val bubble = TextView(this).apply {
            text = "typing…"
            textSize = 13f
            setTextColor(MUTED)
            setBackgroundResource(com.localcharacter.chat.R.drawable.bubble_incoming)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        row.addView(bubble)
        return row
    }

    private fun sendMessage() {
        if (SocialGeneration.active.get()) { toast("A feed reply is still running. Wait for it to finish first."); return }
        if (busy || store.isGenerationPending()) {
            toast("A reply is already being generated.")
            return
        }
        if (modelLoading) {
            toast("The model is still loading.")
            return
        }

        val text = input.text.toString().trim()
        if (text.isBlank()) return

        if (text.startsWith("*improve", ignoreCase = true)) {
            input.setText("")
            applyImprove(text.removePrefixIgnoreCase("*improve").trim())
            return
        }

        if (submitChatText(text)) input.setText("")
    }

    private fun submitChatText(text: String): Boolean {
        if (busy || store.isGenerationPending()) { toast("A reply is already running."); return false }
        if (text.length > 12000) { toast("That message is too long. Split it into smaller messages."); return false }
        val parsed = if (store.settings.sceneDirectionsEnabled) SceneDirector.parse(text) else SceneInput(emptyList(), text, false)
        if (parsed.warnings.isNotEmpty()) { toast(parsed.warnings.joinToString(" ")); return false }
        val generate = SceneDirector.shouldGenerate(store.settings, parsed)
        if (generate && !hasUsableSelectedModel()) return false
        val message = ChatMessage(role = "user", text = text)
        store.conversation.messages += message
        SceneDirector.applyDirectives(store.settings, store.conversation, text, message.timestamp, message.id)
        store.persistConversationOnly()
        if (generate) beginGeneration() else {
            refreshHeader(); renderMessages(); toast("Director settings applied. No chat reply requested.")
        }
        return true
    }

    private fun beginGeneration() {
        if (busy || store.isGenerationPending()) return
        if (!hasUsableSelectedModel()) return
        if (store.conversation.messages.lastOrNull()?.role != "user") {
            toast("There is no user message to answer.")
            return
        }

        store.markGenerationStarted()
        typingBubble = createTypingBubble()
        setBusy(true)
        renderMessages()

        val serviceIntent = Intent(this, ChatReplyService::class.java)
            .setAction(ChatReplyService.ACTION_GENERATE)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (error: Throwable) {
            store.markGenerationFinished()
            typingBubble = null
            setBusy(false)
            showError("Could not start background reply", error.message ?: error.javaClass.simpleName)
        }
    }

    private fun hasUsableSelectedModel(): Boolean {
        val modelFile = store.settings.modelPath.takeIf { it.isNotBlank() }?.let(::File)
        if (modelFile != null && isUsableGguf(modelFile)) return true
        toast("Choose a local GGUF model first.")
        showModelDialog(firstRun = true)
        return false
    }

    private fun redoLastReply() {
        if (busy || modelLoading || store.isGenerationPending()) {
            toast("Wait for the current reply to finish.")
            return
        }
        if (store.conversation.messages.lastOrNull()?.role == "user") { beginGeneration(); return }
        val index = store.conversation.messages.indexOfLast { it.role == "assistant" }
        if (index < 0 || index != store.conversation.messages.lastIndex) {
            toast("There is no latest reply to redo.")
            return
        }
        val target = store.conversation.messages[index]
        recordFeedback(target, -1, showToast = false)
        store.conversation.messages.removeAt(index)
        BrainEngine.rebuildFromHistory(store.settings, store.conversation)
        store.saveConversation()
        renderMessages()
        beginGeneration()
    }

    private fun showAssistantMessageActions(message: ChatMessage) {
        val actions = arrayOf(
            "👍 Like response",
            "👎 Dislike response",
            "↻ Redo latest response",
            "Copy message",
            "Delete this response",
            "Delete everything from here",
        )
        AlertDialog.Builder(this)
            .setTitle("Response")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> recordFeedback(message, 1)
                    1 -> recordFeedback(message, -1)
                    2 -> {
                        val latest = store.conversation.messages.lastOrNull { it.role == "assistant" }
                        if (latest?.id == message.id && store.conversation.messages.lastOrNull()?.id == message.id) {
                            redoLastReply()
                        } else {
                            toast("Only the latest reply can be redone.")
                        }
                    }
                    3 -> {
                        copyMessage(message.text)
                        toast("Message copied.")
                    }
                    4 -> confirmConversationEdit(
                        title = "Delete this response?",
                        message = "The response will also be removed from memory and feedback examples.",
                    ) {
                        deleteSingleAssistantMessage(message)
                    }
                    5 -> confirmConversationEdit(
                        title = "Delete from here?",
                        message = "This response and every message after it will be deleted so the conversation can branch again.",
                    ) {
                        deleteFromHere(message)
                    }
                }
            }
            .show()
    }

    private fun showUserMessageActions(message: ChatMessage) {
        val actions = arrayOf(
            "Edit and redo from here",
            "Delete this message and its reply",
            "Delete everything from here",
            "Copy message",
        )
        AlertDialog.Builder(this)
            .setTitle("Your message")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> confirmConversationEdit(
                        title = "Edit and redo from here?",
                        message = "This message and everything after it will be removed. The text will be placed back in the message box for editing.",
                    ) {
                        val original = message.text
                        deleteFromHere(message, prefillText = original)
                    }
                    1 -> confirmConversationEdit(
                        title = "Delete this turn?",
                        message = "Your message and the AI response directly following it will be deleted.",
                    ) {
                        deleteUserTurn(message)
                    }
                    2 -> confirmConversationEdit(
                        title = "Delete from here?",
                        message = "This message and every message after it will be deleted so you can redo the conversation.",
                    ) {
                        deleteFromHere(message)
                    }
                    3 -> {
                        copyMessage(message.text)
                        toast("Message copied.")
                    }
                }
            }
            .show()
    }

    private fun confirmConversationEdit(
        title: String,
        message: String,
        action: () -> Unit,
    ) {
        if (busy || modelLoading || store.isGenerationPending()) {
            toast("Wait for the current reply to finish before editing the conversation.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> action() }
            .show()
    }

    private fun deleteSingleAssistantMessage(message: ChatMessage) {
        val result = ConversationEditor.deleteAssistantMessage(
            settings = store.settings,
            state = store.conversation,
            messageId = message.id,
        )
        if (result.removedCount <= 0) return
        saveAfterConversationEdit()
        toast("Response deleted.")
    }

    private fun deleteUserTurn(message: ChatMessage) {
        val result = ConversationEditor.deleteUserTurn(
            settings = store.settings,
            state = store.conversation,
            messageId = message.id,
        )
        if (result.removedCount <= 0) return
        saveAfterConversationEdit()
        toast("Message and linked response deleted.")
    }

    private fun deleteFromHere(message: ChatMessage, prefillText: String? = null) {
        val result = ConversationEditor.deleteFromMessage(
            settings = store.settings,
            state = store.conversation,
            messageId = message.id,
        )
        if (result.removedCount <= 0) return
        saveAfterConversationEdit()
        if (prefillText != null) {
            input.setText(prefillText)
            input.setSelection(input.text.length)
            input.requestFocus()
        }
        toast(if (prefillText == null) "Conversation deleted from that point." else "Edit the message and send it again.")
    }

    private fun saveAfterConversationEdit() {
        store.saveConversation()
        cleanupOrphanChatImages()
        renderMessages()
    }

    private fun cleanupOrphanChatImages() {
        val used = store.conversation.messages
            .mapNotNull { it.imagePath.takeIf(String::isNotBlank) }
            .toSet()
        val folder = File(filesDir, "chat-images")
        folder.listFiles()?.forEach { file ->
            if (file.absolutePath !in used) runCatching { file.delete() }
        }
    }

    private fun recordFeedback(
        message: ChatMessage,
        rating: Int,
        showToast: Boolean = true,
    ) {
        val index = store.conversation.messages.indexOfFirst { it.id == message.id }
        if (index < 0 || message.role != "assistant") return

        message.feedback = rating.coerceIn(-1, 1)
        if (message.feedback < 1) {
            store.conversation.memories.removeAll { it.sourceMessageId == message.id }
        }
        val context = store.conversation.messages
            .subList(maxOf(0, index - 8), index)
            .map { it.copy() }
        store.conversation.feedbackExamples.removeAll { it.messageId == message.id }
        store.conversation.feedbackExamples += FeedbackExample(
            messageId = message.id,
            context = context,
            reply = message.text,
            rating = message.feedback,
        )
        while (store.conversation.feedbackExamples.size > 80) {
            store.conversation.feedbackExamples.removeAt(0)
        }
        BrainEngine.onFeedback(store.settings, store.conversation, message.feedback)
        store.saveConversation()
        renderMessages()
        if (showToast) {
            toast(if (message.feedback > 0) "Liked. Future replies will use this as a good example." else "Disliked. Future replies will avoid this style.")
        }
    }

    private fun copyMessage(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
    }

    private fun insertSceneDirectionMarkers() {
        if (!store.settings.sceneDirectionsEnabled) {
            toast("Scene directions are disabled in settings.")
            return
        }
        val start = input.selectionStart.coerceAtLeast(0)
        val end = input.selectionEnd.coerceAtLeast(start)
        val current = input.text
        if (start != end) {
            current.replace(start, end, "*${current.subSequence(start, end).toString()}*")
            input.setSelection(end + 2)
        } else {
            current.insert(start, "**")
            input.setSelection(start + 1)
        }
        input.requestFocus()
    }

    private fun buildSystemPrompt(): String =
        ReplyPipeline.buildPlan(
            settings = store.settings,
            conversation = store.conversation,
            previousMetrics = store.generationMetrics,
        ).systemPrompt

    private fun buildConversationPrompt(): String =
        ReplyPipeline.buildPlan(
            settings = store.settings,
            conversation = store.conversation,
            previousMetrics = store.generationMetrics,
        ).conversationPrompt

    private fun cleanReply(raw: String): String {
        val latest = store.conversation.messages.lastOrNull { it.role == "user" }?.text.orEmpty()
        val mode = ReplyPipeline.buildPlan(store.settings, store.conversation, store.generationMetrics).mode
        return ReplyPipeline.cleanReply(raw, store.settings, store.conversation, latest, mode)
    }

    private fun applyImprove(desired: String) {
        if (desired.isBlank()) {
            toast("Type *improve followed by the exact reply you preferred.")
            return
        }

        val lastAssistantIndex = store.conversation.messages.indexOfLast { it.role == "assistant" }
        if (lastAssistantIndex < 0) {
            toast("There is no model reply to improve yet.")
            return
        }

        val context = store.conversation.messages
            .subList(maxOf(0, lastAssistantIndex - 6), lastAssistantIndex)
            .map { it.copy() }

        val target = store.conversation.messages[lastAssistantIndex]
        target.text = desired
        target.corrected = true
        target.feedback = 1
        store.conversation.corrections += CorrectionExample(context, desired)
        store.conversation.feedbackExamples.removeAll { it.messageId == target.id }
        store.conversation.feedbackExamples += FeedbackExample(
            messageId = target.id,
            context = context,
            reply = desired,
            rating = 1,
        )
        while (store.conversation.corrections.size > 30) {
            store.conversation.corrections.removeAt(0)
        }
        while (store.conversation.feedbackExamples.size > 80) {
            store.conversation.feedbackExamples.removeAt(0)
        }
        store.saveConversation()
        renderMessages()
        toast("Reply replaced and saved as a style example.")
    }

    private fun showAgeGate() {
        val check = CheckBox(this).apply {
            text = "I am 18+ and will use only fictional adult characters."
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Adult fictional chat")
            .setMessage(
                "This app is intended only for adults. Romantic or sexual content must involve fictional consenting adults aged 18 or older.\n\n" +
                    "The app must not be used to impersonate a real person or fabricate real messages.",
            )
            .setView(check)
            .setNegativeButton("Exit") { _, _ -> finish() }
            .setPositiveButton("Enter", null)
            .setCancelable(false)
            .create()

        dialog.setOnShowListener {
            val enter = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            enter.isEnabled = false
            check.setOnCheckedChangeListener { _, checked -> enter.isEnabled = checked }
            enter.setOnClickListener {
                if (check.isChecked) {
                    store.confirmAge()
                    dialog.dismiss()
                    startAutomaticModelLoad(showPickerWhenMissing = true)
                }
            }
        }
        dialog.show()
    }

    private fun showModelDialog(firstRun: Boolean) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(10))
        }
        val scroll = ScrollView(this).apply {
            addView(content)
        }
        val current = TextView(this).apply {
            text = if (store.settings.modelPath.isBlank()) {
                "No local model selected"
            } else {
                "Selected: ${store.settings.modelDisplayName.ifBlank { File(store.settings.modelPath).name }}"
            }
            textSize = 13f
            setTextColor(MUTED)
            setPadding(0, dp(4), 0, dp(12))
        }
        content.addView(current)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Local model")
            .setView(scroll)
            .setNegativeButton(if (firstRun) "Later" else "Close", null)
            .create()

        val localModels = listStoredGgufModels()
        sectionTitle(content, "Models already on this phone")
        if (localModels.isEmpty()) {
            content.addView(TextView(this).apply {
                text = "No downloaded or imported GGUF models found."
                textSize = 13f
                setTextColor(MUTED)
                setPadding(dp(4), dp(5), dp(4), dp(12))
            })
        } else {
            localModels.forEach { model ->
                val selected = model.absolutePath == store.settings.modelPath
                val button = Button(this).apply {
                    text = buildString {
                        if (selected) append("✓ ")
                        append(model.name)
                        append("\n")
                        append(formatBytes(model.length()))
                    }
                    isAllCaps = false
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    setOnClickListener {
                        dialog.dismiss()
                        selectLocalModel(model)
                    }
                }
                content.addView(button, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(7) })
            }
        }

        val importButton = Button(this).apply {
            text = "Import another GGUF from this phone"
            isAllCaps = false
            setOnClickListener {
                dialog.dismiss()
                chooseModelFile()
            }
        }
        content.addView(importButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(12) })

        sectionTitle(content, "Download a model")
        HuggingFaceModels.presets.forEach { preset ->
            val button = Button(this).apply {
                text = "${preset.title}\n${preset.subtitle}"
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener {
                    dialog.dismiss()
                    downloadPreset(preset)
                }
            }
            content.addView(button, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(7) })
        }

        if (engine.isLoaded) {
            val unload = Button(this).apply {
                text = "Unload selected model from RAM"
                isAllCaps = false
                setOnClickListener {
                    engine.unload()
                    refreshHeader()
                    toast("Model unloaded. It will load again when needed.")
                    dialog.dismiss()
                }
            }
            content.addView(unload)
        }

        dialog.setOnDismissListener {
            if (firstRun && store.settings.modelPath.isBlank()) statusView.text = "offline"
        }
        dialog.show()
    }

    private fun selectLocalModel(model: File) {
        if (!isUsableGguf(model)) {
            showError("Invalid model", "The selected file is not a valid GGUF model.")
            return
        }
        engine.unload()
        store.saveSettings(store.settings.copy(
            modelPath = model.absolutePath,
            modelDisplayName = model.name,
        ))
        startAutomaticModelLoad(
            showPickerWhenMissing = false,
            showSuccessToast = true,
            showFailureDialog = true,
        )
    }

    private fun sectionTitle(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF3B4A54.toInt())
            setPadding(dp(2), dp(7), dp(2), dp(7))
        })
    }

    private fun downloadPreset(preset: ModelPreset) {
        val progressView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(10))
        }
        val text = TextView(this).apply {
            text = "Finding the Q4_K_M model file…"
            textSize = 14f
            setTextColor(TEXT)
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 1000
        }
        progressView.addView(text)
        progressView.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(12),
        ).apply { topMargin = dp(12) })

        val dialog = AlertDialog.Builder(this)
            .setTitle("Downloading ${preset.title}")
            .setView(progressView)
            .setNegativeButton("Hide", null)
            .create()
        dialog.show()

        scope.launch {
            try {
                val resolved = HuggingFaceModels.resolveQ4Km(preset.repository)
                text.text = "Starting ${resolved.fileName}…"
                val (downloadId, _) = HuggingFaceModels.enqueueDownload(this@MainActivity, resolved)

                val completed = HuggingFaceModels.awaitDownload(this@MainActivity, downloadId, resolved) { p ->
                    runOnUiThread {
                        if (p.total > 0) {
                            bar.isIndeterminate = false
                            bar.progress = ((p.downloaded.toDouble() / p.total.toDouble()) * 1000).roundToInt()
                            text.text = "${formatBytes(p.downloaded)} of ${formatBytes(p.total)}"
                        } else {
                            bar.isIndeterminate = true
                            text.text = when (p.status) {
                                DownloadManager.STATUS_PAUSED -> "Download paused by Android…"
                                DownloadManager.STATUS_PENDING -> "Waiting to start…"
                                else -> "Downloading model…"
                            }
                        }
                    }
                }

                val settings = store.settings.copy(
                    modelPath = completed.absolutePath,
                    modelDisplayName = preset.title,
                )
                engine.unload()
                store.saveSettings(settings)
                dialog.dismiss()
                startAutomaticModelLoad(
                    showPickerWhenMissing = false,
                    showSuccessToast = true,
                    showFailureDialog = true,
                )
            } catch (error: Throwable) {
                dialog.dismiss()
                showError("Model download failed", error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private fun chooseModelFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/octet-stream",
                "application/gguf",
                "*/*",
            ))
        }
        startActivityForResult(intent, REQ_MODEL)
    }

    private fun chooseAvatarFile() {
        openImagePicker(REQ_AVATAR)
    }

    private fun chooseChatImage() {
        if (busy || store.isGenerationPending()) {
            toast("Wait for the current reply to finish.")
            return
        }
        openImagePicker(REQ_CHAT_IMAGE)
    }

    private fun chooseCharacterPhotoFile() {
        openImagePicker(REQ_CHARACTER_PHOTO)
    }

    private fun openImagePicker(requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, requestCode)
    }

    @Deprecated("Uses the platform file picker for broad Android compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        when (requestCode) {
            REQ_MODEL -> importModel(uri)
            REQ_AVATAR -> importAvatar(uri)
            REQ_CHAT_IMAGE -> importChatImage(uri)
            REQ_CHARACTER_PHOTO -> importCharacterPhoto(uri)
        }
    }

    private fun importModel(uri: Uri) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Importing GGUF")
            .setMessage("Copying the model into the app's private storage…")
            .setCancelable(false)
            .create()
        dialog.show()

        scope.launch {
            try {
                val target = withContext(Dispatchers.IO) {
                    val models = File(filesDir, "models").apply { mkdirs() }
                    val displayName = queryDisplayName(uri) ?: "imported-model.gguf"
                    require(displayName.endsWith(".gguf", ignoreCase = true)) {
                        "The chosen file does not have a .gguf extension."
                    }
                    val destination = File(models, sanitizeName(displayName))
                    contentResolver.openInputStream(uri).use { source ->
                        requireNotNull(source) { "The selected file could not be opened." }
                        FileOutputStream(destination).use { output ->
                            source.copyTo(output, bufferSize = 1024 * 1024)
                        }
                    }
                    require(destination.length() > 1024 * 1024) {
                        "The copied file is too small to be a GGUF model."
                    }
                    destination
                }

                engine.unload()
                store.saveSettings(store.settings.copy(
                    modelPath = target.absolutePath,
                    modelDisplayName = target.name,
                ))
                dialog.dismiss()
                startAutomaticModelLoad(
                    showPickerWhenMissing = false,
                    showSuccessToast = true,
                    showFailureDialog = true,
                )
            } catch (error: Throwable) {
                dialog.dismiss()
                showError("Import failed", error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private fun importAvatar(uri: Uri) {
        scope.launch {
            try {
                val target = withContext(Dispatchers.IO) {
                    val destination = File(filesDir, "character-avatar")
                    contentResolver.openInputStream(uri).use { source ->
                        val inputStream = requireNotNull(source) { "The selected image could not be opened." }
                        FileOutputStream(destination).use { output -> inputStream.copyTo(output) }
                    }
                    destination
                }
                store.saveSettings(store.settings.copy(avatarPath = target.absolutePath))
                refreshHeader()
            } catch (error: Throwable) {
                showError("Avatar import failed", error.message ?: "Unknown error")
            }
        }
    }

    private fun showPhotoSendMenu() {
        AlertDialog.Builder(this)
            .setTitle("Send photo")
            .setItems(arrayOf("Send from me", "Send from ${store.settings.characterName}")) { _, which ->
                when (which) {
                    0 -> chooseChatImage()
                    1 -> showSendCharacterPhotoNow()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importChatImage(uri: Uri) {
        scope.launch {
            try {
                val target = copyImageToApp(uri, "chat-images", "chat")
                val wrapper = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), dp(4), dp(18), 0)
                }
                val caption = EditText(this@MainActivity).apply {
                    hint = "Visible caption (optional)"
                    setText(input.text.toString().trim())
                }
                val description = EditText(this@MainActivity).apply {
                    hint = "Describe the photo for the local text model (optional)"
                    minLines = 2
                    maxLines = 4
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                }
                wrapper.addView(caption)
                wrapper.addView(description)

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Send photo")
                    .setMessage(
                        "This GGUF is text-only, so it cannot inspect the pixels. " +
                            "An optional description lets it understand what the photo shows.",
                    )
                    .setView(wrapper)
                    .setNegativeButton("Cancel") { _, _ -> target.delete() }
                    .setPositiveButton("Send") { _, _ ->
                        input.setText("")
                        val visibleCaption = caption.text.toString().trim()
                        val modelDescription = description.text.toString().trim()
                        store.conversation.messages += ChatMessage(
                            role = "user",
                            text = visibleCaption,
                            imagePath = target.absolutePath,
                            imageDescription = modelDescription,
                        )
                        store.saveConversation()
                        beginGeneration()
                    }
                    .setOnCancelListener { target.delete() }
                    .show()
            } catch (error: Throwable) {
                showError("Photo import failed", error.message ?: "Unknown error")
            }
        }
    }

    private fun importCharacterPhoto(uri: Uri) {
        scope.launch {
            try {
                val target = copyImageToApp(uri, "character-gallery", "character")
                val wrapper = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), dp(4), dp(18), 0)
                }
                val caption = EditText(this@MainActivity).apply {
                    hint = "Caption Elena may send with it (optional)"
                }
                val description = EditText(this@MainActivity).apply {
                    hint = "What this photo shows (used to choose the right photo)"
                    minLines = 2
                    maxLines = 4
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                }
                val tags = EditText(this@MainActivity).apply {
                    hint = "Tags, comma separated: selfie, shoes, beach, feet..."
                }
                wrapper.addView(caption)
                wrapper.addView(description)
                wrapper.addView(tags)

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Add character photo")
                    .setMessage("These labels let the chatter choose and send the matching local photo from her own side of the conversation.")
                    .setView(wrapper)
                    .setNegativeButton("Cancel") { _, _ -> target.delete() }
                    .setPositiveButton("Add") { _, _ ->
                        val photo = CharacterPhoto(
                            path = target.absolutePath,
                            caption = caption.text.toString().trim(),
                            description = description.text.toString().trim(),
                            tags = tags.text.toString()
                                .split(',')
                                .map(String::trim)
                                .filter(String::isNotBlank)
                                .take(20)
                                .toMutableList(),
                        )
                        val photos = store.settings.characterPhotos
                            .filter { File(it.path).exists() }
                            .toMutableList()
                            .apply {
                                removeAll { it.path == photo.path }
                                add(photo)
                                while (size > 40) {
                                    runCatching { File(first().path).delete() }
                                    removeAt(0)
                                }
                            }
                        val paths = photos.map { it.path }.toMutableList()
                        store.saveSettings(store.settings.copy(
                            characterPhotoPaths = paths,
                            characterPhotos = photos,
                        ))
                        toast("Character photo added. Gallery now has ${photos.size} photo(s).")
                    }
                    .setOnCancelListener { target.delete() }
                    .show()
            } catch (error: Throwable) {
                showError("Character photo import failed", error.message ?: "Unknown error")
            }
        }
    }

    private fun showSendCharacterPhotoNow() {
        val photos = store.settings.characterPhotos
            .filter { File(it.path).exists() }
            .ifEmpty {
                store.settings.characterPhotoPaths
                    .filter { File(it).exists() }
                    .map { CharacterPhoto(path = it) }
            }
        if (photos.isEmpty()) {
            toast("Add character photos first.")
            return
        }
        val labels = photos.mapIndexed { index, photo ->
            photo.caption.ifBlank { photo.description }.ifBlank {
                photo.tags.joinToString(", ")
            }.ifBlank { "Photo ${index + 1}" }.take(80)
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Send a photo from ${store.settings.characterName}")
            .setItems(labels) { _, which ->
                val photo = photos[which]
                store.conversation.messages += ChatMessage(
                    role = "assistant",
                    text = photo.caption,
                    imagePath = photo.path,
                    imageDescription = photo.description,
                )
                store.saveConversation()
                renderMessages()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private suspend fun copyImageToApp(
        uri: Uri,
        folderName: String,
        prefix: String,
    ): File = withContext(Dispatchers.IO) {
        val folder = File(filesDir, folderName).apply { mkdirs() }
        val target = File(folder, "$prefix-${UUID.randomUUID()}.img")
        contentResolver.openInputStream(uri).use { source ->
            val inputStream = requireNotNull(source) { "The selected image could not be opened." }
            FileOutputStream(target).use { output ->
                inputStream.copyTo(output, bufferSize = 256 * 1024)
            }
        }
        require(target.length() in 1..20_000_000L) {
            "Choose an image smaller than 20 MB."
        }
        target
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME))
                }
            }
        return uri.lastPathSegment
    }

    private fun openRoleplayStudio(director: Boolean = false) {
        if (busy || store.isGenerationPending()) { toast("Wait for the current reply before changing its instructions."); return }
        val studio = RoleplayStudio(this, { store }, { refreshHeader(); renderMessages() },
            { command -> submitChatText(command) }, { showBrainDialog() })
        if (director) studio.director() else studio.show()
    }

    private fun showSettingsDialog() {
        if (busy || store.isGenerationPending()) { toast("Wait for the current reply before changing settings."); return }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(container) }

        container.addView(Button(this).apply {
            text = "Roleplay Studio · traits, scenes, reply rules"
            isAllCaps = false
            setOnClickListener { openRoleplayStudio() }
        })
        val name = field("Character name", store.settings.characterName)
        val age = field("Character age (18+)", store.settings.characterAge.toString(), InputType.TYPE_CLASS_NUMBER)
        val body = spinnerField(
            "Character body for roleplay",
            listOf("Female", "Male"),
            if (store.settings.characterBody == "male") 1 else 0,
        )
        val userName = field("Your name", store.settings.userName)
        val relationship = field("Relationship details", store.settings.relationship)
        val relationshipStage = spinnerField(
            "Relationship stage (strongly controls tone and intimacy)",
            listOf(
                "Auto from relationship details",
                "Strangers",
                "Acquaintances",
                "Coworkers",
                "Friends",
                "Close friends",
                "Dating",
                "Committed partners",
                "Married",
                "Casual / friends with benefits",
            ),
            when (store.settings.relationshipStage) {
                "stranger" -> 1
                "acquaintance" -> 2
                "coworker" -> 3
                "friend" -> 4
                "close_friend" -> 5
                "dating" -> 6
                "partner" -> 7
                "married" -> 8
                "casual" -> 9
                else -> 0
            },
        )
        val persona = field("Personality and background", store.settings.persona, multiline = true)
        val language = field("Language behavior", store.settings.languageRule, multiline = true)
        val emoji = spinnerField(
            "Emoji use",
            listOf("None", "Natural", "Expressive"),
            when (store.settings.emojiStyle) {
                "none" -> 0
                "expressive" -> 2
                else -> 1
            },
        )
        val typingRealism = spinnerField(
            "Phone typing realism",
            HumanTypingStyle.labels,
            HumanTypingStyle.values.indexOf(store.settings.typingRealism).coerceAtLeast(0),
        )
        val replyStyle = spinnerField(
            "Reply length",
            listOf("Short", "Medium", "Long"),
            when (store.settings.replyStyle) {
                "short" -> 0
                "long" -> 2
                else -> 1
            },
        )
        val performanceProfile = spinnerField(
            "Performance profile",
            listOf("Fast", "Balanced", "Quality"),
            when (store.settings.performanceProfile) {
                "fast" -> 0
                "quality" -> 2
                else -> 1
            },
        )
        val maxTokens = field("Maximum reply tokens", store.settings.maxTokens.toString(), InputType.TYPE_CLASS_NUMBER)
        val contextSize = field("Context size (1024–8192)", store.settings.contextSize.toString(), InputType.TYPE_CLASS_NUMBER)
        val cpuThreads = field("CPU threads (0 = automatic)", store.settings.cpuThreads.toString(), InputType.TYPE_CLASS_NUMBER)
        val temperature = field(
            "Temperature",
            store.settings.temperature.toString(),
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
        )

        val adult = CheckBox(this).apply {
            text = "Permit consensual adult language between fictional adults"
            isChecked = store.settings.adultLanguage
            setPadding(0, dp(8), 0, dp(4))
        }
        val scenes = CheckBox(this).apply {
            text = "Allow *asterisk scene directions*"
            isChecked = store.settings.sceneDirectionsEnabled
            setPadding(0, dp(4), 0, dp(4))
        }
        val adaptivePerformance = CheckBox(this).apply {
            text = "Keep reply speed stable as the conversation grows"
            isChecked = store.settings.adaptivePerformance
            setPadding(0, dp(4), 0, dp(8))
        }
        val performanceStatus = TextView(this).apply {
            text = PerformanceController.diagnostics(store.generationMetrics)
            textSize = 11.5f
            setTextColor(MUTED)
            setPadding(dp(4), 0, dp(4), dp(6))
        }
        val resetPerformance = Button(this).apply {
            text = "Reset performance history"
            isAllCaps = false
            setOnClickListener {
                store.saveGenerationMetrics(
                    GenerationMetrics(performanceProfile = store.settings.performanceProfile),
                )
                performanceStatus.text = "No reply timing data yet."
                toast("Performance history reset.")
            }
        }
        val brainButton = Button(this).apply {
            text = "Mood, brain, and memory"
            isAllCaps = false
        }
        val avatarButton = Button(this).apply {
            text = "Choose character picture"
            isAllCaps = false
            setOnClickListener { chooseAvatarFile() }
        }
        val galleryButton = Button(this).apply {
            text = "Add character chat photo (${store.settings.characterPhotos.count { File(it.path).exists() }.coerceAtLeast(store.settings.characterPhotoPaths.count { File(it).exists() })})"
            isAllCaps = false
            setOnClickListener { chooseCharacterPhotoFile() }
        }
        val clearGalleryButton = Button(this).apply {
            text = "Clear character photo gallery"
            isAllCaps = false
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Clear character photo gallery?")
                    .setMessage("This deletes the local photos the character can send in chat.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear") { _, _ ->
                        (store.settings.characterPhotoPaths + store.settings.characterPhotos.map { it.path })
                            .distinct()
                            .forEach { path -> runCatching { File(path).delete() } }
                        store.saveSettings(store.settings.copy(
                            characterPhotoPaths = mutableListOf(),
                            characterPhotos = mutableListOf(),
                        ))
                        toast("Character photo gallery cleared.")
                    }
                    .show()
            }
        }
        val sendCharacterPhotoButton = Button(this).apply {
            text = "Send a photo from ${store.settings.characterName} now"
            isAllCaps = false
            setOnClickListener { showSendCharacterPhotoNow() }
        }
        val clearSceneButton = Button(this).apply {
            text = if (store.conversation.sceneState.active) "Clear active *scene*" else "No active scene"
            isAllCaps = false
            isEnabled = store.conversation.sceneState.active
            setOnClickListener {
                submitChatText("*clear scene*")
            }
        }
        val modelButton = Button(this).apply {
            text = "Choose local GGUF model"
            isAllCaps = false
        }
        val newChat = Button(this).apply {
            text = "Start a new empty chat"
            isAllCaps = false
        }

        listOf(
            name.first,
            age.first,
            body.first,
            userName.first,
            relationship.first,
            relationshipStage.first,
            persona.first,
            language.first,
            emoji.first,
            typingRealism.first,
            replyStyle.first,
            performanceProfile.first,
            maxTokens.first,
            contextSize.first,
            cpuThreads.first,
            temperature.first,
        ).forEach(container::addView)
        container.addView(adult)
        container.addView(scenes)
        container.addView(adaptivePerformance)
        container.addView(performanceStatus)
        container.addView(resetPerformance)
        container.addView(brainButton)
        container.addView(avatarButton)
        container.addView(galleryButton)
        container.addView(sendCharacterPhotoButton)
        container.addView(clearGalleryButton)
        container.addView(clearSceneButton)
        container.addView(modelButton)
        container.addView(newChat)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Chat settings")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()

        brainButton.setOnClickListener {
            dialog.dismiss()
            showBrainDialog()
        }

        modelButton.setOnClickListener {
            dialog.dismiss()
            showModelDialog(firstRun = false)
        }

        newChat.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Start new chat?")
                .setMessage("This clears messages, feedback, and learned scene memory. Character settings remain.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ ->
                    store.clearConversation()
                    renderMessages()
                    toast("New chat started.")
                }
                .show()
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsedAge = age.second.text.toString().toIntOrNull()
                if (parsedAge == null || parsedAge < 18) {
                    age.second.error = "The fictional character must be 18 or older."
                    return@setOnClickListener
                }

                val next = store.settings.copy(
                    characterName = name.second.text.toString().trim().ifBlank { "Elena" },
                    characterAge = parsedAge.coerceAtMost(99),
                    characterBody = if (body.second.selectedItemPosition == 1) "male" else "female",
                    userName = userName.second.text.toString().trim().ifBlank { "You" },
                    relationship = relationship.second.text.toString().trim(),
                    relationshipStage = when (relationshipStage.second.selectedItemPosition) {
                        1 -> "stranger"
                        2 -> "acquaintance"
                        3 -> "coworker"
                        4 -> "friend"
                        5 -> "close_friend"
                        6 -> "dating"
                        7 -> "partner"
                        8 -> "married"
                        9 -> "casual"
                        else -> "auto"
                    },
                    persona = persona.second.text.toString().trim(),
                    languageRule = language.second.text.toString().trim(),
                    adultLanguage = adult.isChecked,
                    sceneDirectionsEnabled = scenes.isChecked,
                    emojiStyle = when (emoji.second.selectedItemPosition) {
                        0 -> "none"
                        2 -> "expressive"
                        else -> "natural"
                    },
                    typingRealism = HumanTypingStyle.values[typingRealism.second.selectedItemPosition],
                    replyStyle = when (replyStyle.second.selectedItemPosition) {
                        0 -> "short"
                        2 -> "long"
                        else -> "medium"
                    },
                    performanceProfile = when (performanceProfile.second.selectedItemPosition) {
                        0 -> "fast"
                        2 -> "quality"
                        else -> "balanced"
                    },
                    adaptivePerformance = adaptivePerformance.isChecked,
                    maxTokens = maxTokens.second.text.toString().toIntOrNull()?.coerceIn(24, 320) ?: 160,
                    contextSize = contextSize.second.text.toString().toIntOrNull()?.coerceIn(1024, 8192) ?: 3072,
                    cpuThreads = cpuThreads.second.text.toString().toIntOrNull()?.coerceIn(0, 16) ?: 0,
                    temperature = temperature.second.text.toString().toFloatOrNull()?.coerceIn(0.1f, 1.5f) ?: 0.75f,
                    performanceVersion = 40,
                )

                val requiresReload = next.cpuThreads != store.settings.cpuThreads || next.contextSize != store.settings.contextSize ||
                    next.temperature != store.settings.temperature ||
                    next.topP != store.settings.topP ||
                    next.topK != store.settings.topK ||
                    next.performanceProfile != store.settings.performanceProfile
                store.saveSettings(next)
                if (requiresReload) engine.unload()
                refreshHeader()
                renderMessages()
                dialog.dismiss()
                if (requiresReload && next.modelPath.isNotBlank()) {
                    startAutomaticModelLoad(
                        showPickerWhenMissing = false,
                        showSuccessToast = false,
                        showFailureDialog = true,
                    )
                } else {
                    toast("Settings saved.")
                }
            }
        }
        dialog.show()
    }

    private fun showBrainDialog() {
        val brain = store.conversation.brain
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(10))
        }
        val scroll = ScrollView(this).apply { addView(container) }

        val current = TextView(this).apply {
            text = brainStatusText()
            textSize = 13f
            setTextColor(MUTED)
            setPadding(0, 0, 0, dp(10))
        }

        val moodEnabled = CheckBox(this).apply {
            text = "Enable emotional state and moods"
            isChecked = store.settings.moodEnabled
        }
        val automaticMood = CheckBox(this).apply {
            text = "Let mood scores change automatically"
            isChecked = store.settings.automaticMoodEnabled
        }
        if (store.conversation.sceneState.director.mood.isNotEmpty()) {
            container.addView(TextView(this).apply {
                text = "Active scene mood overrides: " + store.conversation.sceneState.director.mood.entries.joinToString { "${it.key}=${it.value}" } +
                    "\nUse *mood: clear* to return to the scores below. Mood OFF ignores all score overrides."
                textSize = 12f; setTextColor(MUTED)
            })
        }
        val showMood = CheckBox(this).apply {
            text = "Show the current mood in the chat header"
            isChecked = store.settings.showMoodInHeader
        }
        val memoryEnabled = CheckBox(this).apply {
            text = "Enable long-term memory and index the last 500 messages"
            isChecked = store.settings.memoryEnabled
            setPadding(0, 0, 0, dp(4))
        }
        val contextEngineStatus = TextView(this).apply {
            text = "Context Engine v2: ${store.conversation.contextIndex.size} indexed messages, " +
                "${store.conversation.contextThreads.size} topic threads. It links replies, corrections, " +
                "follow-ups, questions, plans, and references before prompting the model."
            textSize = 11.5f
            setTextColor(MUTED)
            setPadding(dp(4), 0, dp(4), dp(10))
        }

        val friendliness = field(
            "Friendliness toward you (0–10)",
            brain.friendliness.toString(),
            InputType.TYPE_CLASS_NUMBER,
        )
        val love = field(
            "Love / affection (0–10)",
            brain.love.toString(),
            InputType.TYPE_CLASS_NUMBER,
        )
        val trust = field(
            "Trust (0–10)",
            brain.trust.toString(),
            InputType.TYPE_CLASS_NUMBER,
        )
        val annoyance = field(
            "Annoyance (0–10)",
            brain.annoyance.toString(),
            InputType.TYPE_CLASS_NUMBER,
        )
        val tiredness = field(
            "Tiredness (0–10)",
            brain.tiredness.toString(),
            InputType.TYPE_CLASS_NUMBER,
        )
        val playfulness = field(
            "Playfulness (0–10)",
            brain.playfulness.toString(),
            InputType.TYPE_CLASS_NUMBER,
        )

        val resetMood = Button(this).apply {
            text = "Reset mood scores"
            isAllCaps = false
        }
        val rebuildMemory = Button(this).apply {
            text = "Rebuild memory from this chat"
            isAllCaps = false
        }
        val clearMemory = Button(this).apply {
            text = "Clear learned memory only"
            isAllCaps = false
        }

        container.addView(current)
        container.addView(moodEnabled)
        container.addView(automaticMood)
        container.addView(showMood)
        container.addView(memoryEnabled)
        container.addView(contextEngineStatus)
        listOf(
            friendliness.first,
            love.first,
            trust.first,
            annoyance.first,
            tiredness.first,
            playfulness.first,
        ).forEach(container::addView)
        container.addView(resetMood)
        container.addView(rebuildMemory)
        container.addView(clearMemory)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Mood, brain, and memory")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()

        resetMood.setOnClickListener {
            friendliness.second.setText("7")
            love.second.setText("5")
            trust.second.setText("5")
            annoyance.second.setText("0")
            tiredness.second.setText("2")
            playfulness.second.setText("5")
            toast("Mood fields reset. Press Save to apply.")
        }

        rebuildMemory.setOnClickListener {
            val count = MemoryManager.rebuild(store.conversation)
            val latest = store.conversation.messages.lastOrNull { it.role == "user" }?.text.orEmpty()
            BrainEngine.refreshContext(store.settings, store.conversation, latest)
            store.saveConversation()
            current.text = brainStatusText()
            toast("Memory rebuilt. Indexed ${store.conversation.contextIndex.size} messages and refreshed $count memory items.")
        }

        clearMemory.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear learned memory?")
                .setMessage(
                    "This removes extracted facts, episodic memories, conversation summary, and open threads. " +
                        "The visible chat messages remain.",
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ ->
                    BrainEngine.clearBrainMemory(store.conversation)
                    store.saveConversation()
                    current.text = brainStatusText()
                    toast("Learned memory cleared.")
                }
                .show()
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                fun score(field: EditText, label: String): Int? {
                    val value = field.text.toString().toIntOrNull()
                    if (value == null || value !in 0..10) {
                        field.error = "$label must be from 0 to 10."
                        return null
                    }
                    return value
                }

                val friendlyValue = score(friendliness.second, "Friendliness") ?: return@setOnClickListener
                val loveValue = score(love.second, "Love") ?: return@setOnClickListener
                val trustValue = score(trust.second, "Trust") ?: return@setOnClickListener
                val annoyanceValue = score(annoyance.second, "Annoyance") ?: return@setOnClickListener
                val tiredValue = score(tiredness.second, "Tiredness") ?: return@setOnClickListener
                val playfulValue = score(playfulness.second, "Playfulness") ?: return@setOnClickListener

                val oldMemoryEnabled = store.settings.memoryEnabled
                val nextSettings = store.settings.copy(
                    memoryEnabled = memoryEnabled.isChecked,
                    moodEnabled = moodEnabled.isChecked,
                    automaticMoodEnabled = automaticMood.isChecked,
                    showMoodInHeader = showMood.isChecked,
                    performanceVersion = 40,
                )
                store.saveSettings(nextSettings)

                store.conversation.brain.apply {
                    this.friendliness = friendlyValue
                    this.love = loveValue
                    this.trust = trustValue
                    this.annoyance = annoyanceValue
                    this.tiredness = tiredValue
                    this.playfulness = playfulValue
                    this.lastUpdatedAt = System.currentTimeMillis()
                    this.lastMoodReason =
                        if (automaticMood.isChecked) "manual adjustment" else "manual mood"
                    this.clampScores()
                }
                BrainEngine.recalculateMood(store.conversation.brain)

                if (!oldMemoryEnabled && nextSettings.memoryEnabled) {
                    MemoryManager.rebuild(store.conversation)
                }
                val latest = store.conversation.messages.lastOrNull { it.role == "user" }?.text.orEmpty()
                BrainEngine.refreshContext(nextSettings, store.conversation, latest)
                store.saveConversation()
                refreshHeader()
                dialog.dismiss()
                toast("Mood and memory settings saved.")
            }
        }
        dialog.show()
    }

    private fun brainStatusText(): String {
        val brain = store.conversation.brain
        return buildString {
            append("Current mood: ${brain.moodLabel}")
            append("\nFriendliness ${brain.friendliness}/10 • Love ${brain.love}/10")
            append(" • Trust ${brain.trust}/10")
            append("\nAnnoyance ${brain.annoyance}/10 • Tiredness ${brain.tiredness}/10")
            append(" • Playfulness ${brain.playfulness}/10")
            append("\nMemory: ${store.conversation.memories.size} facts, ")
            append("${store.conversation.episodes.size} remembered exchanges")
            append("\nContext index: ${store.conversation.contextIndex.size}/${MemoryManager.MAX_INDEX_MESSAGES} recent messages")
            if (brain.currentTopic.isNotBlank()) {
                append("\nCurrent topic: ${brain.currentTopic}")
            }
        }
    }

    private fun field(
        label: String,
        value: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
        multiline: Boolean = false,
    ): Pair<LinearLayout, EditText> {
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }
        val title = TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(MUTED)
        }
        val edit = EditText(this).apply {
            setText(value)
            this.inputType = if (multiline) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            } else inputType
            if (multiline) {
                minLines = 3
                maxLines = 6
                gravity = Gravity.TOP
            } else {
                setSingleLine(true)
            }
        }
        group.addView(title)
        group.addView(edit)
        return group to edit
    }

    private fun spinnerField(
        label: String,
        options: List<String>,
        selectedIndex: Int,
    ): Pair<LinearLayout, Spinner> {
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }
        val title = TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(MUTED)
        }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                options,
            )
            setSelection(selectedIndex.coerceIn(0, options.lastIndex))
        }
        group.addView(title)
        group.addView(spinner)
        return group to spinner
    }

    private fun startAutomaticModelLoad(
        showPickerWhenMissing: Boolean,
        showSuccessToast: Boolean = false,
        showFailureDialog: Boolean = false,
    ) {
        if (busy || modelLoading) return
        if (engine.isLoaded) {
            modelLoadError = null
            refreshHeader()
            if (showSuccessToast) toast("Local model is already loaded.")
            return
        }

        setModelLoading(true)
        modelLoadError = null

        scope.launch {
            var openPicker = false
            try {
                val detected = withContext(Dispatchers.IO) { findStoredGgufModel() }
                if (detected == null) {
                    if (store.settings.modelPath.isNotBlank()) {
                        store.saveSettings(store.settings.copy(
                            modelPath = "",
                            modelDisplayName = "",
                        ))
                    }
                    openPicker = showPickerWhenMissing
                } else {
                    if (store.settings.modelPath != detected.absolutePath) {
                        store.saveSettings(store.settings.copy(
                            modelPath = detected.absolutePath,
                            modelDisplayName = detected.name,
                        ))
                    }
                    withContext(Dispatchers.IO) {
                        engine.ensureLoaded(store.settings)
                    }
                    modelLoadError = null
                    maybeWarnAboutSlowModel(detected)
                    if (showSuccessToast) toast("Downloaded model loaded and ready.")
                }
            } catch (error: Throwable) {
                modelLoadError = error.message ?: error.javaClass.simpleName
                if (showFailureDialog) {
                    showError("Model could not be loaded", modelLoadError ?: "Unknown model error")
                } else {
                    toast("The saved model could not be loaded. Tap the model button to choose another.")
                }
            } finally {
                setModelLoading(false)
                if (openPicker) showModelDialog(firstRun = true)
            }
        }
    }

    private fun findStoredGgufModel(): File? {
        val configured = store.settings.modelPath
            .takeIf { it.isNotBlank() }
            ?.let(::File)
        if (configured != null && isUsableGguf(configured)) return configured
        return listStoredGgufModels().firstOrNull()
    }

    private fun listStoredGgufModels(): List<File> {
        val candidates = mutableListOf<File>()
        store.settings.modelPath
            .takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf(::isUsableGguf)
            ?.let(candidates::add)

        val directories = listOfNotNull(
            File(filesDir, "models"),
            getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
        )
        directories.forEach { directory ->
            directory.listFiles()
                ?.filter(::isUsableGguf)
                ?.let(candidates::addAll)
        }

        val selectedPath = store.settings.modelPath
        return candidates
            .distinctBy { it.absolutePath }
            .sortedWith(
                compareByDescending<File> { if (it.absolutePath == selectedPath) 1 else 0 }
                    .thenByDescending { it.lastModified() }
                    .thenBy { it.name.lowercase() },
            )
    }

    private fun isUsableGguf(file: File): Boolean {
        if (!file.isFile || !file.name.endsWith(".gguf", ignoreCase = true)) return false
        if (file.length() < 1_048_576L) return false
        return try {
            file.inputStream().buffered().use { inputStream ->
                val magic = ByteArray(4)
                inputStream.read(magic) == magic.size && magic.contentEquals(byteArrayOf(
                    'G'.code.toByte(),
                    'G'.code.toByte(),
                    'U'.code.toByte(),
                    'F'.code.toByte(),
                ))
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        updateComposerState()
        refreshHeader()
    }

    private fun setModelLoading(value: Boolean) {
        modelLoading = value
        updateComposerState()
        refreshHeader()
    }

    private fun updateComposerState() {
        val enabled = !busy && !modelLoading
        input.isEnabled = enabled
        sendButton.isEnabled = enabled
        val canRedo = enabled && store.conversation.messages.lastOrNull()?.role in setOf("assistant", "user")
        redoButton.isEnabled = canRedo
        redoButton.alpha = if (canRedo) 1f else 0.35f
        sceneButton.isEnabled = enabled && store.settings.sceneDirectionsEnabled
        sceneButton.alpha = if (sceneButton.isEnabled) 1f else 0.35f
        progress.visibility = if (busy || modelLoading) View.VISIBLE else View.GONE
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9001)
        }
    }

    private fun maybeWarnAboutSlowModel(model: File) {
        if (model.length() < 1_700_000_000L) return
        if (!store.shouldWarnForSlowModel(model.absolutePath)) return
        store.markSlowModelWarningShown(model.absolutePath)

        AlertDialog.Builder(this)
            .setTitle("Large local model")
            .setMessage(
                "The same model will be kept. Version 1.4 uses a compact prompt, relevant-memory retrieval, " +
                    "and a lower-contention CPU thread count to reduce reply time. A 4B CPU model can still " +
                    "take noticeably longer than a small model.",
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showError(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(
                "$message\n\n" +
                    "The model remains local. Close other heavy apps and wait until the header says online before retrying.",
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun scrollToBottom() {
        messageScroll.post { messageScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> String.format("%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format("%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun sanitizeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(160)

    private fun String.removePrefixIgnoreCase(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this
}
