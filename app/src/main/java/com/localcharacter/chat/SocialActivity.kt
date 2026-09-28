package com.localcharacter.chat

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.LruCache
import android.view.*
import android.widget.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import kotlin.math.max

/** Instagram-inspired PRIVATE fictional feed. This never signs in to or posts on Instagram. */
class SocialActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var store: SocialStore
    private var world = SocialWorld()
    private var base = CharacterSettings()
    private var section = "feed"
    private var selectedPost = ""
    private var replyTo = ""
    private var draft = ""
    private var showSaved = false
    private var feedLimit = 30
    private var commentLimit = 80
    private var registered = false
    private var lastRenderKey = ""
    private var composerView: EditText? = null
    private var picking: ((String) -> Unit)? = null
    private var scroll: ScrollView? = null
    private val bitmaps = object : LruCache<String, Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val ink = Color.rgb(28, 29, 35)
    private val muted = Color.rgb(111, 113, 124)
    private val accent = Color.rgb(109, 69, 216)
    private val soft = Color.rgb(246, 245, 249)
    private val line = Color.rgb(231, 230, 237)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra("error")?.takeIf { it.isNotBlank() }?.let { error(it); SocialGeneration.lastError = "" }
            refresh()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val old = ChatStore(this)
        if (!old.isAgeConfirmed()) { startActivity(Intent(this, MainActivity::class.java)); finish(); return }
        store = SocialStore(this); base = old.settings
        section = savedInstanceState?.getString("section") ?: intent.getStringExtra("section") ?: "feed"
        selectedPost = savedInstanceState?.getString("post") ?: intent.getStringExtra("post").orEmpty()
        draft = savedInstanceState?.getString("draft").orEmpty(); replyTo = savedInstanceState?.getString("parent").orEmpty()
        if (selectedPost.isNotBlank()) section = "post"
        if (Build.VERSION.SDK_INT >= 30) { window.setDecorFitsSystemWindows(false); window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING) }
        else window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 601)
        refresh()
    }
    override fun onStart() {
        super.onStart()
        if (!::store.isInitialized) return
        SocialGeneration.visible = true
        if (SocialGeneration.lastError.isNotBlank()) { error(SocialGeneration.lastError); SocialGeneration.lastError = "" }
        if (!registered) {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, IntentFilter(SocialReplyService.UPDATE), Context.RECEIVER_NOT_EXPORTED)
            else { @Suppress("DEPRECATION") registerReceiver(receiver, IntentFilter(SocialReplyService.UPDATE)) }
            registered = true
        }
        refresh()
    }
    override fun onStop() {
        SocialGeneration.visible = false
        if (registered) { unregisterReceiver(receiver); registered = false }
        super.onStop()
    }
    override fun onDestroy() { scope.cancel(); bitmaps.evictAll(); super.onDestroy() }
    override fun onSaveInstanceState(out: Bundle) { out.putString("section", section); out.putString("post", selectedPost); out.putString("draft", draft); out.putString("parent", replyTo); super.onSaveInstanceState(out) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); selectedPost = intent.getStringExtra("post").orEmpty(); section = if (selectedPost.isBlank()) "feed" else "post"; refresh() }
    @Deprecated("Framework compatibility") override fun onBackPressed() { if (section != "feed") { section = "feed"; selectedPost = ""; replyTo = ""; refresh() } else super.onBackPressed() }

    private fun refresh() {
        if (!::store.isInitialized || isFinishing) return
        scope.launch {
            try {
                world = withContext(Dispatchers.IO) { store.read() }; base = ChatStore(this@SocialActivity).settings
                if (section == "post" && world.posts.none { it.id == selectedPost }) { section = "feed"; selectedPost = "" }
                render()
            } catch (e: Exception) { error("Could not read the feed. Existing data was preserved. ${e.message}") }
        }
    }
    private fun mutate(change: (SocialWorld) -> Unit, after: (() -> Unit)? = null) {
        scope.launch { try { withContext(Dispatchers.IO) { store.edit(change) }; after?.invoke(); refresh() } catch (e: Exception) { error(e.message ?: "Could not save the change.") } }
    }
    private fun go(tab: String) { section = tab; selectedPost = ""; replyTo = ""; draft = ""; refresh() }
    private fun render() {
        val renderKey = section + ":" + selectedPost
        val samePage = renderKey == lastRenderKey
        val y = if (samePage) scroll?.scrollY ?: 0 else 0
        val restoreTyping = samePage && composerView?.hasFocus() == true
        val caret = composerView?.selectionStart ?: draft.length
        var navView: View? = null
        val root = column().apply { setBackgroundColor(Color.WHITE) }
        if (Build.VERSION.SDK_INT >= 30) root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars()); val ime = insets.getInsets(WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom)); navView?.visibility = if (ime.bottom > bars.bottom) View.GONE else View.VISIBLE; insets
        } else root.fitsSystemWindows = true
        val head = row().apply { setPadding(dp(20), dp(14), dp(14), dp(10)) }
        val titles = column()
        titles.addView(text("Local Circle", 25f, true))
        titles.addView(text("YOUR PRIVATE FICTIONAL WORLD", 9f, false, muted).apply { letterSpacing = .12f })
        head.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(action("＋", "Create a post") { postEditor() })
        head.addView(action("◌", "Model and chat settings") { openChat() })
        root.addView(head)
        if (SocialGeneration.active.get()) {
            val status = row().apply { setPadding(dp(18), dp(4), dp(12), dp(4)); setBackgroundColor(soft) }
            status.addView(ProgressBar(this, null, android.R.attr.progressBarStyleSmall), LinearLayout.LayoutParams(dp(18), dp(18)))
            status.addView(text(SocialGeneration.status.ifBlank { "Preparing local replies…" }, 12f, false, muted), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(9) })
            status.addView(button("Stop", false) { SocialGeneration.cancelRequested = true; toast("Stopping after the current local calculation. No further reply will be posted.") })
            root.addView(status)
        }
        scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(soft) }
        val body = column()
        when (section) {
            "post" -> renderThread(body)
            "cast" -> renderCast(body)
            "scenes" -> renderScenes(body)
            "profile" -> renderProfile(body)
            else -> renderFeed(body)
        }
        scroll!!.addView(body); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (section == "post") root.addView(commentComposer())
        val nav = row().apply { setPadding(dp(8), dp(5), dp(8), dp(5)); background = shape(Color.WHITE, 0f, line) }
        listOf("Feed", "Chats", "Cast", "Scenes", "You").forEachIndexed { i, label ->
            val target = listOf("feed", "chat", "cast", "scenes", "profile")[i]
            val b = button(label, false) { if (target == "chat") openChat() else go(target) }
            b.textSize = 12f; b.setTextColor(if (section == target || (section == "post" && target == "feed")) accent else muted)
            nav.addView(b, LinearLayout.LayoutParams(0, dp(47), 1f))
        }
        navView = nav
        root.addView(nav); setContentView(root)
        lastRenderKey = renderKey
        if (samePage) scroll?.post { scroll?.scrollTo(0, y) }
        if (restoreTyping) composerView?.let { it.requestFocus(); it.setSelection(caret.coerceIn(0, it.text.length)) }
        if (Build.VERSION.SDK_INT >= 30) root.requestApplyInsets()
    }
    private fun renderFeed(body: LinearLayout) {
        val rail = row().apply { setPadding(dp(14), dp(14), dp(14), dp(12)) }
        world.characters.filter { it.enabled }.forEach { actor ->
            val item = column().apply { gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), 0) }
            item.addView(avatar(actor.settings.characterName, actor.settings.avatarPath, 58))
            item.addView(text(actor.settings.characterName.take(13), 11f).apply { setPadding(0, dp(6), 0, 0) })
            item.setOnClickListener { characterEditor(actor) }; rail.addView(item)
        }
        val add = column().apply { gravity = Gravity.CENTER }
        add.addView(action("＋", "Add character") { characterEditor(null) }); add.addView(text("New character", 10f, false, muted)); rail.addView(add)
        body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(rail); setBackgroundColor(Color.WHITE) })
        val tabs = row().apply { setPadding(dp(16), dp(10), dp(12), dp(8)) }
        tabs.addView(button(if (showSaved) "All posts" else "Following · local cast", false) { showSaved = false; refresh() }, LinearLayout.LayoutParams(0, -2, 1f))
        tabs.addView(button(if (showSaved) "Saved ✓" else "Saved", false) { showSaved = !showSaved; refresh() }); body.addView(tabs)
        val posts = world.posts.filter { !showSaved || it.saved }.sortedByDescending { it.createdAt }
        if (posts.isEmpty()) {
            val empty = card(); empty.addView(text("Your world, one post at a time.", 23f, true))
            empty.addView(text("Create a photo or text post from yourself or a fictional character. Comment, invite your cast, and let the local model reply. Nothing is posted online.", 15f, false, muted).apply { setPadding(0, dp(12), 0, dp(16)) })
            empty.addView(button("Create your first post", true) { postEditor() }); body.addView(empty)
        }
        posts.take(feedLimit).forEach { body.addView(postCard(it, false)) }
        if (posts.size > feedLimit) body.addView(button("Load 30 older posts", false) { feedLimit += 30; refresh() })
    }
    private fun postCard(post: FeedPost, detailed: Boolean): View {
        val box = column().apply { setBackgroundColor(Color.WHITE); setPadding(0, dp(12), 0, dp(12)) }
        val head = row().apply { setPadding(dp(16), 0, dp(10), dp(10)) }
        val actor = world.characters.find { it.id == post.authorId }; val name = world.name(post.authorId, base.userName)
        head.addView(avatar(name, actor?.settings?.avatarPath.orEmpty(), 38))
        val identity = column().apply { setPadding(dp(10), 0, 0, 0) }
        identity.addView(text(if (post.authorId == "self") name else "@${actor?.handle ?: "removed"}", 14f, true))
        identity.addView(text("${actor?.role ?: base.selfProfile.role} · ${age(post.createdAt)}", 11f, false, muted))
        head.addView(identity, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(action("⋯", "Post options") { postOptions(post) }); box.addView(head)
        if (post.imagePath.isNotBlank()) {
            val image = photo(post.imagePath, resources.displayMetrics.widthPixels / resources.displayMetrics.density)
            image.contentDescription = post.imageDescription.ifBlank { "Post photo; no description" }
            image.setOnClickListener { showImage(post.imagePath, post.imageDescription) }; box.addView(image)
        } else {
            val visual = text(post.caption.ifBlank { "A quiet moment." }, 23f, true).apply {
                setPadding(dp(24), dp(32), dp(24), dp(32)); minHeight = dp(155)
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(245, 238, 255), Color.rgb(236, 244, 252))).apply { cornerRadius = 0f }
            }; box.addView(visual)
        }
        val actions = row().apply { setPadding(dp(10), dp(6), dp(10), 0) }
        actions.addView(action(if (post.liked) "♥" else "♡", "Like post") { mutate({ w -> w.posts.find { it.id == post.id }?.let { it.liked = !it.liked } }) })
        actions.addView(action("◯", "Comment on post") { openPost(post.id) })
        actions.addView(button("Invite replies", false) { chooseResponders(post.id, "") }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(action(if (post.saved) "◆" else "◇", "Save post") { mutate({ w -> w.posts.find { it.id == post.id }?.let { it.saved = !it.saved } }) })
        box.addView(actions)
        if (post.imagePath.isNotBlank() && post.caption.isNotBlank()) box.addView(text("$name  ${post.caption}", 14f).apply { setPadding(dp(18), dp(4), dp(18), dp(5)) })
        if (post.liked) box.addView(text("Liked by you", 12f, true).apply { setPadding(dp(18), 0, dp(18), dp(6)) })
        val comments = world.comments.filter { it.postId == post.id }
        if (!detailed) {
            comments.takeLast(2).forEach { c -> box.addView(text("${world.name(c.authorId, base.userName)}  ${c.text.take(150)}", 12f, false, muted).apply { setPadding(dp(18), dp(2), dp(18), dp(2)) }) }
            box.addView(button(if (comments.isEmpty()) "Add a comment…" else "View ${comments.size} comments", false) { openPost(post.id) }.apply { gravity = Gravity.START; setPadding(dp(18), 0, dp(18), 0) })
        }
        box.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }; return box
    }
    private fun openPost(id: String) { selectedPost = id; section = "post"; replyTo = ""; draft = ""; commentLimit = 80; refresh() }
    private fun renderThread(body: LinearLayout) {
        val post = world.posts.find { it.id == selectedPost } ?: return
        body.addView(postCard(post, true))
        val title = row().apply { setPadding(dp(18), dp(7), dp(14), dp(7)) }
        title.addView(text("Comments", 18f, true), LinearLayout.LayoutParams(0, -2, 1f))
        title.addView(button("Direct scene", false) { sceneEditor(post.id) }); body.addView(title)
        val comments = world.comments.filter { it.postId == post.id }
        if (comments.size > commentLimit) body.addView(button("Load older comments", false) { commentLimit += 80; refresh() })
        if (comments.isEmpty()) body.addView(text("Start the conversation or invite someone from your cast.", 14f, false, muted).apply { setPadding(dp(22), dp(18), dp(22), dp(28)) })
        comments.takeLast(commentLimit).forEach { c ->
            val commentRow = row().apply { gravity = Gravity.TOP; setPadding(dp(if (c.parentId.isBlank()) 18 else 38), dp(12), dp(16), dp(12)); setBackgroundColor(Color.WHITE) }
            val actor = world.characters.find { it.id == c.authorId }; val name = world.name(c.authorId, base.userName)
            commentRow.addView(avatar(name, actor?.settings?.avatarPath.orEmpty(), 30))
            val content = column().apply { setPadding(dp(10), 0, 0, 0) }
            val label = row(); label.addView(text(name, 13f, true)); label.addView(text("  ${age(c.createdAt)}", 10f, false, muted)); content.addView(label)
            if (c.parentId.isNotBlank()) world.comments.find { it.id == c.parentId }?.let { parent -> content.addView(text("↳ ${world.name(parent.authorId, base.userName)}: ${parent.text.take(90)}", 11f, false, muted)) }
            content.addView(text(c.text, 14f).apply { setPadding(0, dp(5), 0, dp(4)) })
            if (c.imagePath.isNotBlank()) content.addView(photo(c.imagePath, 170f).apply { setOnClickListener { showImage(c.imagePath, "Character attachment") } })
            val opts = row(); opts.addView(button("Reply", false) { replyTo = c.id; refresh() }); opts.addView(button(if (c.liked) "Liked ♥" else "Like", false) { mutate({ w -> w.comments.find { it.id == c.id }?.let { it.liked = !it.liked } }) })
            opts.addView(button("Reply as…", false) { chooseResponders(post.id, c.id) }); content.addView(opts)
            content.setOnLongClickListener { commentOptions(c); true }; commentRow.setOnLongClickListener { commentOptions(c); true }
            commentRow.addView(content, LinearLayout.LayoutParams(0, -2, 1f)); body.addView(commentRow)
        }
        body.addView(text("Only this post and its public comments are used for replies. Private messages stay private.", 11f, false, muted).apply { setPadding(dp(20), dp(16), dp(20), dp(16)) })
    }
    private fun commentComposer(): View {
        val outer = column().apply { setPadding(dp(12), dp(7), dp(12), dp(8)); background = shape(Color.WHITE, 0f, line) }
        if (replyTo.isNotBlank()) {
            val selected = world.comments.find { it.id == replyTo }
            val status = row(); status.addView(text("Replying to ${selected?.let { world.name(it.authorId, base.userName) } ?: "post"}", 11f, false, accent), LinearLayout.LayoutParams(0, -2, 1f))
            status.addView(button("Cancel", false) { replyTo = ""; refresh() }); outer.addView(status)
        }
        val r = row(); val input = EditText(this).apply {
            hint = "Add a comment…"; textSize = 15f; maxLines = 4; setTextColor(ink); setHintTextColor(muted)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(2400)); background = shape(soft, 22f); setPadding(dp(14), dp(10), dp(14), dp(10)); setText(draft)
            addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}; override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { draft = s.toString() }; override fun afterTextChanged(s: Editable?) {} })
        }
        composerView = input
        r.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(button("Post", false) { sendComment(input.text.toString()) }); outer.addView(r)
        outer.addView(text("*directions* set the scene; they aren't posted as dialogue", 9f, false, muted).apply { setPadding(dp(6), dp(5), 0, 0) }); return outer
    }
    private fun sendComment(raw: String) {
        val postId = selectedPost; val target = replyTo; val value = raw.trim()
        if (value.isBlank()) return
        val parsed = SceneDirector.parse(value)
        if (parsed.warnings.isNotEmpty()) { error(parsed.warnings.joinToString("\n")); return }
        val id = UUID.randomUUID().toString()
        mutate({ w ->
            val post = requireNotNull(w.posts.find { it.id == postId }) { "Post has been removed." }
            if (target.isNotBlank()) require(w.comments.any { it.id == target && it.postId == postId }) { "Reply target has been removed." }
            if (parsed.directions.isNotEmpty()) {
                val temp = ConversationState(sceneState = post.scene)
                SceneDirector.applyDirectives(base, temp, value, sourceMessageId = id); post.scene = temp.sceneState
            }
            if (parsed.dialogue.isNotBlank()) w.comments += FeedComment(id = id, postId = postId, authorId = "self", text = parsed.dialogue, parentId = target)
            post.revision++
        }, after = {
            draft = ""; replyTo = ""
            if (parsed.dialogue.isNotBlank() && world.autoOwnerReply && !SocialGeneration.active.get()) {
                val post = world.posts.find { it.id == postId }; val targetAuthor = world.comments.find { it.id == target }?.authorId
                val responder = targetAuthor?.takeIf { it != "self" } ?: post?.authorId?.takeIf { it != "self" }
                if (responder != null) launchReplies(postId, id, listOf(responder))
            } else if (parsed.dialogue.isBlank()) toast("Scene updated. Use Invite replies to choose who responds.")
        })
    }
    private fun chooseResponders(postId: String, target: String) {
        val cast = world.characters.filter { it.enabled }
        if (cast.isEmpty()) { toast("Add a character first."); return }
        val checked = BooleanArray(cast.size)
        val owner = world.posts.find { it.id == postId }?.authorId
        cast.indexOfFirst { it.id == owner }.takeIf { it >= 0 }?.let { checked[it] = true }
        AlertDialog.Builder(this).setTitle("Who replies? (up to 3)")
            .setMultiChoiceItems(cast.map { "${it.settings.characterName} · ${it.role}" }.toTypedArray(), checked) { _, index, value -> checked[index] = value }
            .setNegativeButton("Cancel", null).setPositiveButton("Generate local replies") { _, _ ->
                val ids = cast.filterIndexed { i, _ -> checked[i] }.map { it.id }
                if (ids.isEmpty()) toast("Choose at least one character.") else if (ids.size > 3) error("Select no more than three. Each character takes one model generation.") else launchReplies(postId, target, ids)
            }.show()
    }
    private fun launchReplies(post: String, target: String, ids: List<String>) {
        if (SocialGeneration.active.get() || ChatStore(this).isGenerationPending()) { toast("A reply is already running. Wait or stop it before starting another."); return }
        if (base.modelPath.isBlank()) { AlertDialog.Builder(this).setTitle("Local model required").setMessage("The feed works without a model, but generated replies need a downloaded or imported GGUF. Your existing model has not been replaced.").setPositiveButton("Open Chats / models") { _, _ -> openChat() }.setNegativeButton("Later", null).show(); return }
        val intent = Intent(this, SocialReplyService::class.java).putExtra("post", post).putExtra("target", target).putStringArrayListExtra("actors", ArrayList(ids))
        try { if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent); toast("Preparing local replies…") }
        catch (e: Exception) { error("Could not start local inference: ${e.message}") }
    }
    private fun postOptions(post: FeedPost) {
        AlertDialog.Builder(this).setTitle("Post options").setItems(arrayOf("Edit caption / photo description", "Direct this post's scene", "Delete post and comments")) { _, i ->
            when (i) { 0 -> postEditor(post); 1 -> sceneEditor(post.id); 2 -> confirm("Delete this post and all comments?") { mutate({ it.deletePost(post.id) }) } }
        }.show()
    }
    private fun commentOptions(comment: FeedComment) {
        val items = mutableListOf("Copy text", "Reply to this comment", "Invite a character to reply")
        if (comment.authorId == "self" || !comment.generated) items += "Edit and remove later replies"
        items += "Delete comment and its reply branch"
        AlertDialog.Builder(this).setTitle(world.name(comment.authorId, base.userName)).setItems(items.toTypedArray()) { _, i ->
            when (items[i]) {
                "Copy text" -> { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Comment", comment.text)); toast("Copied") }
                "Reply to this comment" -> { replyTo = comment.id; refresh() }
                "Invite a character to reply" -> chooseResponders(comment.postId, comment.id)
                "Edit and remove later replies" -> {
                    val body = column(); val input = field(body, "Comment", comment.text, 5)
                    AlertDialog.Builder(this).setTitle("Edit comment").setView(paddedScroll(body)).setNegativeButton("Cancel", null)
                        .setPositiveButton("Save edited branch") { _, _ ->
                            val value = input.text.toString().trim()
                            if (value.isNotBlank()) mutate({ w ->
                                val position = w.comments.indexOfFirst { it.id == comment.id }
                                require(position >= 0) { "Comment was removed." }
                                w.deleteCommentBranch(comment.id)
                                w.comments.add(position.coerceAtMost(w.comments.size), comment.copy(text = value))
                            })
                        }.show()
                }
                else -> confirm("Delete this comment and all replies to it?") { mutate({ it.deleteCommentBranch(comment.id) }) }
            }
        }.show()
    }
    private fun postEditor(existing: FeedPost? = null) {
        val body = column()
        note(body, "Publish only in this private fictional feed. Choose whether the post is yours or belongs to a cast member.")
        val authors = listOf("self") + world.characters.map { it.id }
        val who = select(body, "Post from", authors.map { world.name(it, base.userName) }, authors.indexOf(existing?.authorId ?: "self").coerceAtLeast(0))
        val caption = field(body, "Caption / text post", existing?.caption.orEmpty(), 5, 2400)
        val description = field(body, "Describe the photo for the text-only model", existing?.imageDescription.orEmpty(), 3, 1200)
        var path = existing?.imagePath.orEmpty()
        val status = text(if (path.isBlank()) "No photo selected" else "Photo attached", 12f, false, muted); body.addView(status)
        body.addView(button("Choose photo", false) { pickImage { newPath -> path = newPath; status.text = "Photo attached" } })
        body.addView(button("Use a character gallery photo", false) {
            val character = world.characters.find { it.id == authors[who.selectedItemPosition] }
            if (character == null) { toast("Select a character as the author first."); return@button }
            val gallery = CharacterPhotoSelector.available(character.settings)
            if (gallery.isEmpty()) { toast("Add images in Cast → character → Photo gallery."); return@button }
            AlertDialog.Builder(this).setTitle("Saved photos").setItems(gallery.map { it.caption.ifBlank { it.tags.joinToString().ifBlank { File(it.path).name } } }.toTypedArray()) { _, index ->
                path = gallery[index].path; description.setText(gallery[index].description); status.text = "Character gallery photo attached"
            }.show()
        })
        body.addView(button("Remove photo", false) { path = ""; status.text = "Text-only post" })
        val dialog = AlertDialog.Builder(this).setTitle(if (existing == null) "Create a post" else "Edit post")
            .setView(paddedScroll(body)).setNegativeButton("Cancel", null).setPositiveButton("Publish locally", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val content = caption.text.toString().trim()
            if (content.isBlank() && path.isBlank()) { caption.error = "Add text or a photo."; return@setOnClickListener }
            val author = authors[who.selectedItemPosition]; val image = path; val alt = description.text.toString().trim()
            mutate({ w ->
                if (existing != null) {
                    val live = requireNotNull(w.posts.find { it.id == existing.id }) { "Post was deleted." }
                    live.authorId = author; live.caption = content; live.imagePath = image; live.imageDescription = alt; live.revision++
                } else w.posts += FeedPost(authorId = author, caption = content, imagePath = image, imageDescription = alt)
            }); dialog.dismiss()
        } }; dialog.show()
    }
    private fun renderCast(body: LinearLayout) {
        val intro = card(); intro.addView(text("Your cast", 24f, true))
        intro.addView(text("Each character has an independent voice, relationship, mood and gallery. They are generated locally, not connected people.", 14f, false, muted).apply { setPadding(0, dp(8), 0, dp(12)) })
        intro.addView(button("＋ Add character", true) { characterEditor(null) }); body.addView(intro)
        world.characters.forEach { c ->
            val card = card(); val r = row(); r.addView(avatar(c.settings.characterName, c.settings.avatarPath, 52))
            val label = column().apply { setPadding(dp(12), 0, 0, 0) }; label.addView(text(c.settings.characterName, 18f, true)); label.addView(text("@${c.handle} · ${c.role}", 12f, false, muted)); r.addView(label); card.addView(r)
            card.addView(text(c.settings.persona.take(220), 13f, false, muted).apply { setPadding(0, dp(14), 0, dp(8)) })
            val actions = row(); actions.addView(button("Edit character", false) { characterEditor(c) }); actions.addView(button("Photo gallery", false) { gallery(c) }); card.addView(actions)
            body.addView(card)
        }
    }
    private fun characterEditor(existing: SocialCharacter?) {
        val c = existing?.let { SocialCharacter.fromJson(it.toJson()) } ?: SocialCharacter(settings = CharacterSettings(
            characterName = "New character", relationship = "friend", relationshipStage = "friend", persona = "Thoughtful and independent. Responds directly, respects the user's choices, and does not force flirting."))
        val s = c.settings; val body = column()
        val name = field(body, "Name (fictional adult)", s.characterName)
        val handle = field(body, "Handle", c.handle)
        val age = field(body, "Age (18+)", s.characterAge.toString(), number = true)
        val gender = select(body, "Character gender", listOf("Woman", "Man"), if (s.characterBody == "male") 1 else 0)
        val role = field(body, "Role — friend, colleague, neighbor, teacher, creator…", c.role)
        val relationValues = listOf("stranger", "acquaintance", "coworker", "friend", "close_friend", "dating", "partner", "married", "casual")
        val relation = select(body, "Relationship to you", relationValues.map { it.replace('_', ' ') }, relationValues.indexOf(s.relationshipStage).coerceAtLeast(0))
        val persona = field(body, "Personality and background", s.persona, 5)
        val traits = field(body, "Traits / quirks", s.roleplay.traits, 3)
        val goals = field(body, "Goals / motivation", s.roleplay.goals, 3)
        val rules = field(body, "How to reply (instructions)", s.roleplay.replyRules, 3)
        val boundaries = field(body, "Boundaries", s.roleplay.boundaries, 3)
        val style = select(body, "Typing style", HumanTypingStyle.labels, HumanTypingStyle.values.indexOf(s.typingRealism).coerceAtLeast(0))
        val emojis = select(body, "Emoji use", listOf("None", "Natural", "Expressive"), listOf("none", "natural", "expressive").indexOf(s.emojiStyle).coerceAtLeast(0))
        val mood = select(body, "Mood mode", listOf("Off", "Manual", "Automatic"), if (!s.moodEnabled) 0 else if (s.automaticMoodEnabled) 2 else 1)
        val scores = listOf("Friendliness", "Love / affection", "Trust", "Annoyance", "Tiredness", "Playfulness")
        val starting = listOf(c.mood.friendliness, c.mood.love, c.mood.trust, c.mood.annoyance, c.mood.tiredness, c.mood.playfulness)
        val sliders = scores.mapIndexed { i, title -> slider(body, title, starting[i]) }
        val photoMode = select(body, "Automatic photo sharing", listOf("Never", "Only when asked", "Contextual (model must request it)"), listOf("never", "request", "contextual").indexOf(s.photoSharing.mode).coerceAtLeast(0))
        val publicPhotos = toggle(body, "Allow this gallery in public feed replies", s.photoSharing.publicReplies)
        note(body, "Request-only is the default. No unrelated images are substituted. A refusal always blocks attachments. Manual posts are controlled separately by you.")
        var avatarPath = s.avatarPath
        body.addView(button("Choose profile picture", false) { pickImage { avatarPath = it; toast("Profile image chosen; save to apply.") } })
        val enabled = toggle(body, "Available for replies", c.enabled)
        val dialog = AlertDialog.Builder(this).setTitle(if (existing == null) "New cast member" else "Edit ${s.characterName}")
            .setView(paddedScroll(body)).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val years = age.text.toString().toIntOrNull()
            if (years == null || years !in 18..120) { age.error = "Use an adult age (18–120)."; return@setOnClickListener }
            if (name.text.toString().trim().isBlank()) { name.error = "Enter a name."; return@setOnClickListener }
            c.handle = handle.text.toString().trim().replace(Regex("[^a-zA-Z0-9._]"), "").take(40).ifBlank { "character" }; c.role = role.text.toString().take(120); c.enabled = enabled.isChecked
            c.settings = s.copy(characterName = name.text.toString().trim().take(50), characterAge = years,
                characterBody = if (gender.selectedItemPosition == 1) "male" else "female", relationshipStage = relationValues[relation.selectedItemPosition],
                relationship = relationValues[relation.selectedItemPosition].replace('_', ' '), persona = persona.text.toString(),
                typingRealism = HumanTypingStyle.values[style.selectedItemPosition], emojiStyle = listOf("none", "natural", "expressive")[emojis.selectedItemPosition],
                moodEnabled = mood.selectedItemPosition != 0, automaticMoodEnabled = mood.selectedItemPosition == 2, avatarPath = avatarPath,
                roleplay = s.roleplay.copy(traits = traits.text.toString(), goals = goals.text.toString(), replyRules = rules.text.toString(), boundaries = boundaries.text.toString()),
                photoSharing = PhotoSharingSettings(listOf("never", "request", "contextual")[photoMode.selectedItemPosition], publicPhotos.isChecked, s.photoSharing.cooldownTurns))
            c.mood.friendliness = sliders[0].progress; c.mood.love = sliders[1].progress; c.mood.trust = sliders[2].progress
            c.mood.annoyance = sliders[3].progress; c.mood.tiredness = sliders[4].progress; c.mood.playfulness = sliders[5].progress
            c.baselineMood = c.mood.copy(lastProcessedUserMessageId = "", lastProcessedAssistantMessageId = "")
            mutate({ w -> w.characters.removeAll { it.id == c.id }; w.characters += c; w.posts.forEach { it.revision++ } }, after = {
                if (c.id == "main") {
                    val old = ChatStore(this); val runtime = old.settings
                    old.saveSettings(runtime.copy(characterName = c.settings.characterName, characterAge = c.settings.characterAge,
                        characterBody = c.settings.characterBody, relationship = c.settings.relationship, relationshipStage = c.settings.relationshipStage,
                        persona = c.settings.persona, typingRealism = c.settings.typingRealism, emojiStyle = c.settings.emojiStyle,
                        moodEnabled = c.settings.moodEnabled, automaticMoodEnabled = c.settings.automaticMoodEnabled,
                        avatarPath = c.settings.avatarPath, roleplay = c.settings.roleplay, photoSharing = c.settings.photoSharing))
                    old.conversation.brain = c.mood.copy(); old.persistConversationOnly()
                }
            }); dialog.dismiss()
        } }; dialog.show()
    }
    private fun gallery(actor: SocialCharacter) {
        val body = column(); note(body, "Local images only. Captions, descriptions and tags help selection; the model does not see pixels.")
        val pictures = CharacterPhotoSelector.available(actor.settings)
        pictures.forEach { p ->
            body.addView(photo(p.path, 145f)); body.addView(text(p.caption.ifBlank { "Untitled" }, 14f, true)); body.addView(text(p.tags.joinToString(", "), 12f, false, muted))
            body.addView(button("Remove from gallery", false) { mutate({ w -> w.characters.find { it.id == actor.id }?.settings?.let { it.characterPhotos.removeAll { it.path == p.path }; it.characterPhotoPaths.removeAll { path -> path == p.path } } }, after = { if (actor.id == "main") syncMainGallery() }); toast("Removed from gallery; existing posts are unchanged.") })
        }
        val dialog = AlertDialog.Builder(this).setTitle("${actor.settings.characterName}'s photos").setView(paddedScroll(body)).setNegativeButton("Close", null)
            .setPositiveButton("Add image") { _, _ -> pickImage { path -> photoDetails(actor.id, path) } }.create()
        dialog.show()
    }
    private fun photoDetails(actorId: String, path: String) {
        val body = column(); body.addView(photo(path, 180f))
        val caption = field(body, "Caption", ""); val description = field(body, "Describe the image", "", 3)
        val tags = field(body, "Tags, comma separated", "", 2)
        AlertDialog.Builder(this).setTitle("Photo metadata").setView(paddedScroll(body)).setNegativeButton("Cancel", null)
            .setPositiveButton("Add to gallery") { _, _ -> mutate({ w ->
                val actor = requireNotNull(w.characters.find { it.id == actorId }); require(actor.settings.characterPhotos.size < 80) { "This gallery already contains 80 images." }
                actor.settings.characterPhotos += CharacterPhoto(path = path, caption = caption.text.toString(), description = description.text.toString(), tags = tags.text.toString().split(',').map { it.trim() }.filter { it.isNotBlank() }.toMutableList())
                actor.settings.characterPhotoPaths += path
            }, after = { if (actorId == "main") syncMainGallery() }) }.show()
    }
    private fun syncMainGallery() {
        scope.launch { val current = withContext(Dispatchers.IO) { store.read().characters.find { it.id == "main" } }; current?.let { actor ->
            val chat = ChatStore(this@SocialActivity); chat.saveSettings(chat.settings.copy(characterPhotos = actor.settings.characterPhotos, characterPhotoPaths = actor.settings.characterPhotoPaths))
        } }
    }
    private fun renderScenes(body: LinearLayout) {
        val content = card(); content.addView(text("Direct the moment", 24f, true))
        note(content, "Scene directions are author notes, never comments spoken by you. Global scenes apply to your feed; a post can override individual details. Private chats keep their own director state.")
        content.addView(button("Edit global feed scene", true) { sceneEditor(null) })
        val current = SceneDirector.prompt(world.globalScene)
        content.addView(text(current.ifBlank { "No global scene is active." }, 13f, false, muted).apply { setPadding(0, dp(16), 0, dp(12)) })
        note(content, "New fields: *cast: Mara is my neighbor; Alex is a photographer* and *my role: new neighbor*. *reply: ...* applies once. *clear scene* removes the setup.")
        body.addView(content)
        val presets = listOf(
            "Coffee catch-up" to "*scene: friends catching up over coffee*\n*location: a neighborhood cafe*\n*goal: ask about the user's week without controlling their answer*",
            "Creative collaboration" to "*scene: adult creators planning a photo walk*\n*location: a city square*\n*goal: propose one practical plan and listen to others*",
            "Work lunch" to "*scene: adult coworkers deciding where to have lunch*\n*relationship: coworkers*\n*traits: relaxed but professional*",
            "New neighbors" to "*scene: adult neighbors meeting for the first time*\n*my role: new neighbor*\n*traits: curious, polite, not overfamiliar*",
        )
        presets.forEach { (name, commands) -> val c = card(); c.addView(text(name, 17f, true)); c.addView(text(commands, 12f, false, muted)); c.addView(button("Use this starting point", false) { sceneEditor(null, commands) }); body.addView(c) }
    }
    private fun sceneEditor(postId: String?, preset: String = "") {
        val body = column()
        note(body, "Write author directions inside *asterisks*. Ordinary text outside stars isn't used by this editor. Public replies stay comments. Clearing a post override returns to the global scene; clear the global scene as well to remove it everywhere.")
        val commands = field(body, "Scene and next-reply instructions", preset, 7, 6000)
        commands.hint = "*scene: we are friends at a cafe*\n*cast: Mara is my neighbor; Alex is my colleague*\n*my role: friend*\n*reply: answer warmly in one sentence*"
        val current = if (postId == null) world.globalScene else world.posts.find { it.id == postId }?.scene
        if (current != null) note(body, "Current state:\n${SceneDirector.prompt(current).ifBlank { "None" }}\nOne-reply direction: ${current.director.nextReply.ifBlank { "None" }}")
        val dialog = AlertDialog.Builder(this).setTitle(if (postId == null) "Global feed scene" else "This post's scene")
            .setView(paddedScroll(body)).setNegativeButton("Cancel", null)
            .setNeutralButton("Clear") { _, _ -> applyScene(postId, "*clear scene*") }.setPositiveButton("Apply", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            var raw = commands.text.toString().trim(); if (raw.isNotBlank() && !raw.contains('*')) raw = "*$raw*"
            val parsed = SceneDirector.parse(raw)
            if (raw.isBlank() || parsed.directions.isEmpty()) { commands.error = "Enter a direction."; return@setOnClickListener }
            if (parsed.warnings.isNotEmpty() || parsed.dialogue.isNotBlank()) { commands.error = parsed.warnings.joinToString().ifBlank { "Put author instructions inside *asterisks*." }; return@setOnClickListener }
            applyScene(postId, raw); dialog.dismiss()
        } }; dialog.show()
    }
    private fun applyScene(postId: String?, raw: String) {
        mutate({ w ->
            val p = if (postId != null) requireNotNull(w.posts.find { it.id == postId }) else null
            val state = ConversationState(sceneState = p?.scene ?: w.globalScene)
            SceneDirector.applyDirectives(base, state, raw, sourceMessageId = UUID.randomUUID().toString())
            if (p != null) { p.scene = state.sceneState; p.revision++ } else { w.globalScene = state.sceneState; w.posts.forEach { it.revision++ } }
        }, after = { toast("Director state saved. No comment was fabricated.") })
    }
    private fun renderProfile(body: LinearLayout) {
        val card = card(); card.addView(avatar(base.userName, "", 68)); card.addView(text(base.userName, 24f, true).apply { setPadding(0, dp(12), 0, dp(5)) })
        card.addView(text("${base.selfProfile.role} · ${base.selfProfile.gender}", 13f, false, muted))
        card.addView(text("${world.posts.count { it.authorId == "self" }} posts   ·   ${world.comments.count { it.authorId == "self" }} comments", 13f).apply { setPadding(0, dp(14), 0, dp(12)) })
        card.addView(button("Edit your identity & role", true) { selfEditor() })
        card.addView(button("Local model / private chat settings", false) { openChat() })
        val auto = toggle(card, "Let the addressed character reply after I comment", world.autoOwnerReply)
        auto.setOnCheckedChangeListener { _, checked -> mutate({ it.autoOwnerReply = checked }) }
        note(card, "Auto-reply makes one response from the addressed character or post author, not an infinite conversation. Invite replies lets you select up to three people. Every reply uses the same selected local model.")
        note(card, "Local Circle is a fictional offline simulation. No Instagram account is connected and no posts or comments are uploaded. The model uses captions and descriptions, not image pixels. Settings and social text are encrypted locally; imported images are app-private files.")
        body.addView(card)
    }
    private fun selfEditor() {
        val profile = base.selfProfile; val body = column()
        val name = field(body, "Your display name", base.userName)
        val genders = listOf("unspecified", "man", "woman", "nonbinary", "custom")
        val gender = select(body, "Your gender", listOf("Unspecified", "Man / guy", "Woman", "Nonbinary", "Custom"), genders.indexOf(profile.gender).let { if (it >= 0) it else 4 })
        val customGender = field(body, "Custom gender (optional)", if (profile.gender !in genders) profile.gender else "")
        val pronouns = field(body, "Pronouns — e.g. he/him, she/her, they/them", profile.pronouns)
        val age = field(body, "Adult age", profile.age.toString(), number = true)
        val roles = select(body, "Role starting point", SelfProfile.roles, SelfProfile.roles.indexOf(profile.role).coerceAtLeast(0))
        val role = field(body, "Your role in your own words (overrides starting point)", profile.role)
        var firstRoleSelection = true
        roles.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (firstRoleSelection) { firstRoleSelection = false; return }
                role.setText(if (SelfProfile.roles[position] == "custom") "" else SelfProfile.roles[position])
            }
        }
        val bio = field(body, "Details the character should know about you", profile.bio, 4, 1200)
        note(body, "These are explicit user choices, not guesses based on pictures. They apply to private chats and public comments. Gender does not prescribe personality or body details.")
        val dialog = AlertDialog.Builder(this).setTitle("Your identity & role").setView(paddedScroll(body)).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val years = age.text.toString().toIntOrNull()
            if (years == null || years !in 18..120) { age.error = "Enter an adult age (18–120)."; return@setOnClickListener }
            val g = genders[gender.selectedItemPosition].let { if (it == "custom") customGender.text.toString().ifBlank { "unspecified" } else it }
            val explicitPronouns = pronouns.text.toString().trim().ifBlank { when (g) { "man" -> "he/him"; "woman" -> "she/her"; "nonbinary" -> "they/them"; else -> "" } }
            val self = SelfProfile(g, explicitPronouns, years, role.text.toString().trim().ifBlank { SelfProfile.roles[roles.selectedItemPosition] }, bio.text.toString())
            val chat = ChatStore(this); chat.saveSettings(chat.settings.copy(userName = name.text.toString().trim().take(50).ifBlank { "You" }, selfProfile = self))
            mutate({ it.posts.forEach { post -> post.revision++ } }); dialog.dismiss()
        } }; dialog.show()
    }
    private fun openChat() = startActivity(Intent(this, MainActivity::class.java))
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = ViewGroup.LayoutParams(-1, -2) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = ViewGroup.LayoutParams(-1, -2) }
    private fun card() = column().apply {
        background = shape(Color.WHITE, 18f); setPadding(dp(20), dp(20), dp(20), dp(20))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), dp(12), dp(14), 0) }
    }
    private fun text(value: String, size: Float, bold: Boolean = false, color: Int = ink) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(2).toFloat(), 1.02f)
    }
    private fun note(body: LinearLayout, value: String) { body.addView(text(value, 12f, false, muted).apply { setPadding(0, dp(10), 0, dp(10)) }) }
    private fun button(label: String, primary: Boolean, click: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f; minHeight = dp(42); minimumHeight = dp(42); minWidth = 0; minimumWidth = 0
        setTextColor(if (primary) Color.WHITE else accent); background = shape(if (primary) accent else Color.TRANSPARENT, 12f)
        setPadding(dp(12), dp(6), dp(12), dp(6)); setOnClickListener { click() }
    }
    private fun action(glyph: String, description: String, click: () -> Unit) = button(glyph, false, click).apply {
        contentDescription = description; textSize = 25f; setTextColor(ink); layoutParams = LinearLayout.LayoutParams(dp(45), dp(45)); setPadding(0, 0, 0, 0)
    }
    private fun shape(color: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); if (stroke != null) setStroke(dp(1), stroke)
    }
    private fun avatar(name: String, path: String, size: Int): View {
        if (path.isNotBlank()) return photo(path, size.toFloat()).apply {
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)); background = shape(soft, size / 2f); clipToOutline = true
        }
        return text(name.trim().take(1).uppercase().ifBlank { "?" }, size * .38f, true, accent).apply {
            gravity = Gravity.CENTER; background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(233, 222, 255), Color.rgb(249, 224, 232))).apply { shape = GradientDrawable.OVAL }
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        }
    }
    private fun photo(path: String, height: Float): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP; layoutParams = LinearLayout.LayoutParams(-1, dp(height)); setBackgroundColor(soft)
        val target = this; val size = if (height < 100f) 160 else 960; val key = "$path:$size"
        bitmaps.get(key)?.let { setImageBitmap(it) } ?: scope.launch {
            val b = withContext(Dispatchers.IO) {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(path, opts)
                if (opts.outWidth <= 0 || opts.outHeight <= 0) null else {
                    var sample = 1; while (max(opts.outWidth, opts.outHeight) / sample > size * 2) sample *= 2
                    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }
            if (b != null) { bitmaps.put(key, b); target.setImageBitmap(b) }
        }
    }
    private fun showImage(path: String, description: String) {
        val view = photo(path, 460f).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        AlertDialog.Builder(this).setTitle(description.take(70).ifBlank { "Photo" }).setView(view).setPositiveButton("Close", null).show()
    }
    private fun field(body: LinearLayout, label: String, value: String, lines: Int = 1, maxChars: Int = 1400, number: Boolean = false): EditText {
        body.addView(text(label, 12f, true, muted).apply { setPadding(0, dp(13), 0, dp(5)) })
        val e = EditText(this).apply {
            setText(value); textSize = 15f; setTextColor(ink); background = shape(soft, 10f); setPadding(dp(12), dp(10), dp(12), dp(10))
            inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or (if (lines > 1) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
            if (lines == 1) setSingleLine(true) else { minLines = lines; maxLines = lines + 2; gravity = Gravity.TOP }
            filters = arrayOf(InputFilter.LengthFilter(maxChars))
        }; body.addView(e); return e
    }
    private fun select(body: LinearLayout, label: String, options: List<String>, selected: Int): Spinner {
        body.addView(text(label, 12f, true, muted).apply { setPadding(0, dp(14), 0, dp(3)) })
        val spinner = Spinner(this).apply { adapter = ArrayAdapter(this@SocialActivity, android.R.layout.simple_spinner_dropdown_item, options); setSelection(selected.coerceIn(0, options.lastIndex)) }
        body.addView(spinner, LinearLayout.LayoutParams(-1, dp(48))); return spinner
    }
    private fun toggle(body: LinearLayout, label: String, checked: Boolean) = CheckBox(this).apply { text = label; textSize = 13f; isChecked = checked; setTextColor(ink); body.addView(this) }
    private fun slider(body: LinearLayout, label: String, value: Int): SeekBar {
        val title = text("$label  $value / 10", 12f, true, muted).apply { setPadding(0, dp(12), 0, dp(5)) }; body.addView(title)
        val seek = SeekBar(this).apply {
            max = 10; progress = value.coerceIn(0, 10)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) { title.text = "$label  $progress / 10" }
                override fun onStartTrackingTouch(seek: SeekBar?) {}; override fun onStopTrackingTouch(seek: SeekBar?) {}
            })
        }; body.addView(seek); return seek
    }
    private fun paddedScroll(body: LinearLayout) = ScrollView(this).apply { body.setPadding(dp(20), dp(8), dp(20), dp(18)); addView(body) }
    private fun confirm(message: String, action: () -> Unit) { AlertDialog.Builder(this).setTitle("Confirm").setMessage(message).setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> action() }.show() }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun error(message: String) { if (!isFinishing) AlertDialog.Builder(this).setTitle("Local Circle").setMessage(message).setPositiveButton("OK", null).show() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()
    private fun age(timestamp: Long): String { val minutes = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0) / 60000); return when { minutes < 1 -> "just now"; minutes < 60 -> "${minutes}m"; minutes < 1440 -> "${minutes / 60}h"; else -> "${minutes / 1440}d" } }
    private fun pickImage(done: (String) -> Unit) {
        picking = done
        @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 620)
    }
    @Deprecated("Uses framework picker for API 24 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 620) return
        val receiver = picking; picking = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null || receiver == null) return
        scope.launch {
            try { val path = withContext(Dispatchers.IO) { importImage(uri) }; receiver(path) }
            catch (e: Exception) { error("Image import failed: ${e.message}") }
        }
    }
    private fun importImage(uri: Uri): String {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri).use { source -> BitmapFactory.decodeStream(requireNotNull(source) { "Image stream unavailable" }, null, opts) }
        require(opts.outWidth > 0 && opts.outHeight > 0) { "This file could not be decoded as an image." }
        var sample = 1; while (max(opts.outWidth, opts.outHeight) / sample > 2048) sample *= 2
        val bitmap = contentResolver.openInputStream(uri).use { source -> BitmapFactory.decodeStream(requireNotNull(source) { "Image stream unavailable" }, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: throw IllegalArgumentException("Image is not supported.")
        val folder = File(filesDir, "social-media").apply { mkdirs() }; val file = File(folder, UUID.randomUUID().toString() + ".jpg")
        val orientation = runCatching { contentResolver.openInputStream(uri).use { source ->
            if (source == null) ExifInterface.ORIENTATION_NORMAL else ExifInterface(source).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        }
        val rotated = if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        try {
            file.outputStream().use { out ->
                require(rotated.compress(Bitmap.CompressFormat.JPEG, 88, out)) { "Could not save image." }
            }
        } finally { if (rotated !== bitmap) rotated.recycle(); bitmap.recycle() }
        return file.absolutePath
    }
}
