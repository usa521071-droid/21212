package com.localcharacter.chat

/** Public-post context is kept separate from private DMs. No private chat is accepted here. */
data class SocialPrompt(val system: String, val prompt: String, val maxTokens: Int, val includedIds: List<String>, val nextDirection: String)

object SocialReplyPolicy {
    fun effectiveSettings(base: CharacterSettings, actor: SocialCharacter): CharacterSettings = actor.settings.copy(
        modelPath = base.modelPath, modelDisplayName = base.modelDisplayName, contextSize = base.contextSize,
        temperature = base.temperature, topK = base.topK, topP = base.topP, cpuThreads = base.cpuThreads,
        performanceProfile = base.performanceProfile, userName = base.userName, selfProfile = base.selfProfile,
        adultLanguage = base.adultLanguage && actor.settings.adultLanguage)

    fun mergedScene(global: SceneState, local: SceneState): SceneState {
        val result = SceneState.fromJson(global.toJson())
        result.active = global.active || local.active
        if (local.premise.isNotBlank()) result.premise = local.premise
        result.director.fields.putAll(local.director.fields)
        result.director.mood.putAll(local.director.mood)
        if (local.relationshipOverride.isNotBlank()) result.relationshipOverride = local.relationshipOverride
        if (local.director.nextReply.isNotBlank()) {
            result.director.nextReply = local.director.nextReply; result.director.nextReplyFor = local.director.nextReplyFor
        }
        return result
    }

    fun build(world: SocialWorld, postId: String, targetId: String, actorId: String, base: CharacterSettings): SocialPrompt {
        val post = requireNotNull(world.posts.find { it.id == postId }) { "Post was deleted." }
        val actor = requireNotNull(world.characters.find { it.id == actorId && it.enabled }) { "Character is unavailable." }
        val settings = effectiveSettings(base, actor)
        val all = world.comments.filter { it.postId == postId }.takeLast(500)
        // Find the requested parent in the full thread so old explicit targets never become unrelated recent comments.
        val target = if (targetId.isBlank()) null else requireNotNull(world.comments.find { it.id == targetId && it.postId == postId }) { "Comment was deleted." }
        val chain = mutableListOf<FeedComment>(); val seen = mutableSetOf<String>(); var cursor = target
        while (cursor != null && seen.add(cursor.id) && chain.size < 8) {
            chain += cursor
            val parent = cursor.parentId
            cursor = if (parent.isBlank()) null else world.comments.find { it.id == parent && it.postId == postId }
        }
        val selected = linkedMapOf<String, FeedComment>()
        // Latest target + its antecedents always outrank keyword matches. Keep prompt size bounded.
        var budget = (settings.contextSize * 2 - 1100).coerceIn(700, 6500)
        for (c in chain) { if (selected.isEmpty() || c.text.length + 60 <= budget) { selected[c.id] = c; budget -= c.text.length + 60 } }
        val recent = all.takeLast(5)
        val terms = MemoryManager.keywords(target?.text ?: post.caption)
        val ranked = all.filterNot { it.id in selected }.map { it to terms.intersect(MemoryManager.keywords(it.text)).size }
            .filter { it.second >= 2 }.sortedByDescending { it.second }.take(3).map { it.first }
        for (c in recent.asReversed() + ranked) {
            if (c.id !in selected && c.text.length + 60 <= budget) { selected[c.id] = c; budget -= c.text.length + 60 }
        }
        val scene = mergedScene(world.globalScene, post.scene)
        val state = ConversationState(sceneState = SceneState.fromJson(scene.toJson()), brain = actor.mood.copy())
        val brain = SceneDirector.effectiveBrain(settings, state)
        val direction = scene.director.nextReply
        val newestText = target?.text ?: post.caption
        val greeting = Regex("""(?iu)^\s*(?:hi|hello|hey|salut|bună|buna)[\s!.?🙂👋]*$""").matches(newestText)
        val system = buildString {
            appendLine("Write ONE public comment as ${settings.characterName}, a fictional adult aged ${settings.characterAge}. Nobody here is a real social-media contact. Never write the other characters' or user's turns.")
            appendLine(base.selfProfile.prompt(base.userName))
            appendLine("Character role: ${actor.role}; relationship to user: ${settings.relationship}; stage: ${settings.relationshipStage}.")
            appendLine("Voice: ${settings.persona.take(500)}. Traits: ${settings.roleplay.traits.take(600)}. Goals: ${settings.roleplay.goals.take(350)}.")
            appendLine(HumanTypingStyle.prompt(settings.typingRealism)); appendLine(settings.languageRule)
            if (settings.moodEnabled) appendLine("Apply current mood subtly, never recite: friendliness ${brain.friendliness}/10; affection ${brain.love}/10; trust ${brain.trust}/10; annoyance ${brain.annoyance}/10; tiredness ${brain.tiredness}/10; playfulness ${brain.playfulness}/10. Low trust/affection means reserved, not romantic. Tired or annoyed means brief, never abusive.")
            appendLine("Reply to the exact selected comment about THIS post. A thank-you only needs acknowledgement; 'why?' refers to its parent. Do not repeat old replies, invent memories, create a crowd, or reveal private DMs. Do not assume that characters know private scene notes outside this fictional setup.")
            appendLine("This is written social-media commenting, not physical narration. No *winks*, third-person actions or stage directions, even if a scene establishes background. Respect boundaries and consent. Any intimate characters must be consenting fictional adults. Never escalate a neutral greeting into intimacy.")
            appendLine(if (base.adultLanguage) "Public-channel appropriateness still applies: enabling mature language does not mean initiate sexual comments." else "Keep all comments non-explicit.")
            appendLine("Photo descriptions are supplied text, NOT visual perception. Never claim to see pixels. " + PhotoSharePolicy.prompt(settings.photoSharing))
            appendLine(when (settings.emojiStyle) { "none" -> "No emojis."; "expressive" -> "A few fitting emojis are fine."; else -> "Occasional appropriate emoji, not compulsory." })
            if (settings.roleplay.boundaries.isNotBlank()) appendLine("Boundaries: ${settings.roleplay.boundaries.take(500)}")
            if (settings.roleplay.replyRules.isNotBlank()) appendLine("Reply preferences: ${settings.roleplay.replyRules.take(500)}")
            appendLine(SceneDirector.prompt(scene))
            if (direction.isNotBlank()) appendLine("AUTHOR DIRECTION FOR THIS ONE REPLY (do not quote): $direction")
            append("No analysis, labels or instructions in the output. ${if (greeting && direction.isBlank()) "Acknowledge the greeting in one brief sentence, about 3–10 words." else "Usually 1–3 short sentences; finish your thought and leave other speakers' choices open."}")
        }
        val prompt = buildString {
            appendLine("POST BY ${world.name(post.authorId, base.userName)}:\n${post.caption.take(2000)}")
            if (post.imagePath.isNotBlank()) appendLine("Attached photo description (user supplied; do not infer missing contents): ${post.imageDescription.ifBlank { "No description available" }.take(900)}")
            appendLine("PUBLIC THREAD (background only; each speaker is separately labeled):")
            selected.values.sortedBy { it.createdAt }.forEach { c -> appendLine("[${c.id.take(8)} → ${c.parentId.take(8)}] ${world.name(c.authorId, base.userName)}: ${c.text.take(1000)}") }
            if (target != null) appendLine("REPLY ONLY TO ${world.name(target.authorId, base.userName)}'S SELECTED COMMENT:\n${target.text}")
            else appendLine("Write a relevant first comment on the post.")
            append("Only ${settings.characterName}'s actual comment text:")
        }
        return SocialPrompt(system, prompt, if (greeting && direction.isBlank()) 28 else base.maxTokens.coerceIn(80, 160), selected.keys.toList(), direction)
    }

    fun clean(raw: String, world: SocialWorld, base: CharacterSettings, actorId: String, hitTokenLimit: Boolean = false): String {
        var text = ReplyPipeline.stripReasoning(raw).first
        text = text.replace(Regex("""(?is)\[\[SEND_PHOTO(?::[^]]*)?]]"""), "")
        val actorName = world.name(actorId, base.userName)
        text = text.replaceFirst(Regex("^\\s*(?:${Regex.escape(actorName)}|assistant)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        val names = (world.characters.map { it.settings.characterName } + listOf(base.userName, "User", "Human", "Assistant")).distinct().joinToString("|") { Regex.escape(it) }
        Regex("(?im)^\\s*(?:$names)\\s*:\\s*").find(text)?.let { text = text.take(it.range.first) }
        text = text.replace(Regex("""(?s)\*[^*\n]{1,500}\*"""), " ")
        text = text.lines().filterNot { Regex("""(?i)^\s*(?:(?:she|he)\s+)?(?:winks?|moans?|blushes?|smirks?|nods?)\b.*$""").matches(it) }.joinToString("\n").trim()
        require(text.isNotBlank()) { "The model returned only narration or reasoning. Try again." }
        require(!Regex("""(?i)^(?:the user wants|i should respond|we need to respond|analysis:)""").containsMatchIn(text)) { "The model returned internal instructions rather than a comment. Try again." }
        require(text.length <= 3000) { "The model ignored the comment length. Try again." }
        if (hitTokenLimit) require(text.trimEnd().lastOrNull() in setOf('.', '!', '?', '…') || text.codePoints().toArray().lastOrNull()?.let { it in 0x1F300..0x1FAFF } == true) {
            "The draft reached the token limit without finishing. Retry or increase the reply limit in model settings."
        }
        return text
    }
}
