package com.localcharacter.chat

object MemoryManager {
    const val MAX_INDEX_MESSAGES = 500
    private const val MAX_FACT_MEMORIES = 500
    private const val MAX_EPISODES = 500
    private val factCache = linkedMapOf<String, List<Candidate>>()

    private val explicitMemory = Regex(
        """(?iu)\b(?:remember|don't forget|keep in mind|remind me|ține minte|nu uita|reține|amintește-mi)\b""",
    )
    private val nameFact = Regex(
        """(?iu)\b(?:my name is|call me|i am called|mă cheamă|spune-mi)\b""",
    )
    private val locationFact = Regex(
        """(?iu)\b(?:i am from|i'm from|i live in|i moved to|sunt din|locuiesc în|m-am mutat în)\b""",
    )
    private val workFact = Regex(
        """(?iu)\b(?:i work as|i work at|my job is|lucrez ca|lucrez la|meseria mea)\b""",
    )
    private val birthdayFact = Regex(
        """(?iu)\b(?:my birthday|i was born|ziua mea|m-am născut)\b""",
    )
    private val likeFact = Regex(
        """(?iu)\b(?:i like|i love|i enjoy|i prefer|îmi place|iubesc|prefer)\b""",
    )
    private val dislikeFact = Regex(
        """(?iu)\b(?:i don't like|i dislike|i hate|nu-mi place|urăsc)\b""",
    )
    private val relationshipEvent = Regex(
        """(?iu)\b(?:we are|we're|we became|our date|our relationship|we met|we kissed|we promised|we love each other|suntem|am devenit|întâlnirea noastră|relația noastră|ne-am întâlnit|ne-am sărutat|am promis|ne iubim)\b""",
    )
    private val futurePlan = Regex(
        """(?iu)\b(?:tomorrow|tonight|friday|saturday|sunday|next week|later|we will|we're going to|we should|i need to|mâine|diseară|vineri|sâmbătă|duminică|săptămâna viitoare|mai târziu|vom|o să|ar trebui|trebuie să)\b""",
    )
    private val sceneOnly = Regex("""(?s)^\s*\*[^*]{1,1000}\*\s*$""")
    private val genericReference = Regex(
        """(?iu)\b(?:that|this|it|before|earlier|again|why not|how so|what do you mean|what did we|what were we|you just said|the last message|asta|aia|mai devreme|înainte|din nou|de ce nu|cum așa|ce vrei să spui|ce am spus|despre ce vorbeam)\b""",
    )

    private val uniqueSlots = setOf(
        "identity:name",
        "identity:location",
        "identity:work",
        "identity:birthday",
        "relationship:status",
    )

    private val stopWords = setOf(
        "a", "an", "and", "are", "as", "at", "be", "but", "by", "do", "for", "from",
        "had", "has", "have", "he", "her", "him", "his", "how", "i", "if", "in", "is",
        "it", "me", "my", "of", "on", "or", "our", "she", "so", "that", "the", "their",
        "them", "they", "this", "to", "was", "we", "were", "what", "when", "where", "which",
        "who", "why", "will", "with", "you", "your",
        "ai", "al", "ale", "am", "are", "ar", "aș", "ca", "că", "ce", "cu", "cum", "da",
        "de", "din", "este", "eu", "fi", "fie", "în", "la", "mai", "mă", "mi", "nu", "o",
        "pe", "pentru", "să", "se", "și", "te", "tu", "un", "una", "unei", "vrei",
    )

    fun refreshContext(state: ConversationState): Int {
        val indexed = refreshContextIndex(state)
        ContextUnderstandingEngine.refresh(state)
        return indexed
    }

    fun refresh(state: ConversationState): Int {
        val indexed = refreshContext(state)
        val facts = refreshFacts(state)
        val episodes = refreshEpisodes(state)
        return indexed + facts + episodes
    }

    fun rebuild(state: ConversationState): Int {
        synchronized(factCache) { factCache.clear() }
        FastContextCache.invalidate()
        state.memories.clear()
        state.episodes.clear()
        state.contextIndex.clear()
        state.contextThreads.clear()
        state.dialogueContext = DialogueContext()
        return refresh(state)
    }

    fun clear(state: ConversationState) {
        FastContextCache.invalidate()
        state.memories.clear()
        state.episodes.clear()
        state.contextIndex.clear()
        state.contextThreads.clear()
        state.dialogueContext = DialogueContext()
        state.brain.currentTopic = ""
        state.brain.rollingSummary = ""
        state.brain.openThreads.clear()
        state.brain.recentTopics.clear()
    }

    fun purgeDeletedReferences(state: ConversationState, deletedMessageIds: Set<String>) {
        if (deletedMessageIds.isEmpty()) return
        FastContextCache.invalidate()
        state.memories.removeAll { it.sourceMessageId in deletedMessageIds }
        state.episodes.removeAll {
            it.userMessageId in deletedMessageIds || it.assistantMessageId in deletedMessageIds
        }
        state.contextIndex.removeAll { it.messageId in deletedMessageIds }
        state.contextThreads.removeAll { thread ->
            thread.messageIds.removeAll { it in deletedMessageIds }
            thread.messageIds.isEmpty()
        }
        state.dialogueContext.linkedMessageIds.removeAll { it in deletedMessageIds }
        state.feedbackExamples.removeAll { example ->
            example.messageId in deletedMessageIds ||
                example.context.any { it.id in deletedMessageIds }
        }
        state.corrections.removeAll { correction ->
            correction.context.any { it.id in deletedMessageIds }
        }
    }

    fun retrieve(
        state: ConversationState,
        query: String,
        limit: Int,
        preferRecent: Boolean = false,
    ): List<MemoryItem> {
        if (limit <= 0 || state.memories.isEmpty()) return emptyList()
        val queryTokens = keywords(query)
        val queryPhrases = phrases(query)
        val newest = state.memories.maxOfOrNull { it.createdAt }?.coerceAtLeast(1L) ?: 1L
        val referenceBoost = preferRecent || genericReference.containsMatchIn(query)
        val now = System.currentTimeMillis()

        val selected = state.memories
            .map { memory ->
                val memoryKeywords = FastContextCache.cachedKeywords(memory.id, memory.text)
                val overlap = queryTokens.intersect(memoryKeywords).size
                val phraseOverlap = queryPhrases.intersect(
                    FastContextCache.cachedPhrases(memory.id, memory.text),
                ).size
                val exactBonus = exactTextBonus(query, memory.text)
                val categoryBonus = when (memory.category) {
                    "identity" -> 5.0
                    "relationship" -> 4.0
                    "preference" -> 3.0
                    "plan" -> 3.5
                    else -> 1.0
                }
                val recency = memory.createdAt.toDouble() / newest.toDouble()
                val accessBonus = memory.accessCount.coerceAtMost(12) * 0.12
                val recentBonus = if (referenceBoost) recency * 5.0 else recency
                val noQueryBonus = if (queryTokens.isEmpty() && memory.importance >= 4) 2.0 else 0.0
                ScoredMemory(
                    memory = memory,
                    score = overlap * 6.0 +
                        phraseOverlap * 8.0 +
                        exactBonus +
                        memory.importance * 1.7 +
                        categoryBonus +
                        recentBonus +
                        accessBonus +
                        noQueryBonus,
                )
            }
            .filter { queryTokens.isEmpty() || it.score >= 4.0 || referenceBoost }
            .sortedByDescending(ScoredMemory::score)
            .distinctBy { normalize(it.memory.text).lowercase() }
            .take(limit)
            .map(ScoredMemory::memory)

        selected.forEach {
            it.lastAccessedAt = now
            it.accessCount++
        }
        return selected
    }

    fun retrieveEpisodes(
        state: ConversationState,
        query: String,
        limit: Int,
        preferRecent: Boolean = false,
    ): List<EpisodeMemory> {
        if (limit <= 0 || state.episodes.isEmpty()) return emptyList()
        val queryTokens = keywords(query)
        val queryPhrases = phrases(query)
        val newest = state.episodes.maxOfOrNull { it.createdAt }?.coerceAtLeast(1L) ?: 1L
        val referenceBoost = preferRecent || genericReference.containsMatchIn(query)
        val now = System.currentTimeMillis()

        val selected = state.episodes
            .map { episode ->
                val combined = episode.userText + " " + episode.assistantText
                val overlap = queryTokens.intersect(
                    FastContextCache.cachedKeywords(episode.id, combined),
                ).size
                val phraseOverlap = queryPhrases.intersect(
                    FastContextCache.cachedPhrases(episode.id, combined),
                ).size
                val exactBonus = exactTextBonus(query, combined)
                val recency = episode.createdAt.toDouble() / newest.toDouble()
                val feedbackBonus = when {
                    episode.feedback > 0 -> 2.5
                    episode.feedback < 0 -> -3.0
                    else -> 0.0
                }
                val accessBonus = episode.accessCount.coerceAtMost(12) * 0.10
                val recentBonus = if (referenceBoost) recency * 8.0 else recency * 1.6
                val emptyQueryBonus = if (queryTokens.isEmpty() && referenceBoost) 3.0 else 0.0
                ScoredEpisode(
                    episode = episode,
                    score = overlap * 7.0 +
                        phraseOverlap * 9.0 +
                        exactBonus +
                        episode.importance * 1.5 +
                        feedbackBonus +
                        recentBonus +
                        accessBonus +
                        emptyQueryBonus,
                )
            }
            .filter { queryTokens.isEmpty() || it.score >= 4.5 || referenceBoost }
            .sortedByDescending(ScoredEpisode::score)
            .take(limit)
            .sortedBy { it.episode.createdAt }
            .map(ScoredEpisode::episode)

        selected.forEach {
            it.lastAccessedAt = now
            it.accessCount++
        }
        return selected
    }

    /**
     * Retrieves coherent windows from the last 500 indexed messages using the
     * process-wide inverted index rather than rescoring every message.
     */
    fun retrieveIndexedMessages(
        state: ConversationState,
        query: String,
        limit: Int,
        preferRecent: Boolean = false,
        neighborRadius: Int = 2,
    ): List<ChatMessage> {
        if (limit <= 0 || state.messages.isEmpty()) return emptyList()
        refreshContextIndex(state)
        return FastContextCache.retrieveIndexedMessages(
            state = state,
            query = query,
            limit = limit,
            preferRecent = preferRecent || genericReference.containsMatchIn(query),
            neighborRadius = neighborRadius,
        )
    }

    fun keywords(text: String): Set<String> = keywordList(text).toSet()

    fun phrases(text: String): Set<String> {
        val tokens = rawTokens(text)
            .filter { it.length >= 2 }
            .take(120)
        if (tokens.size < 2) return emptySet()
        val result = linkedSetOf<String>()
        for (index in 0 until tokens.lastIndex) {
            result += tokens[index] + " " + tokens[index + 1]
            if (index + 2 < tokens.size) {
                result += tokens[index] + " " + tokens[index + 1] + " " + tokens[index + 2]
            }
        }
        return result.take(60).toSet()
    }

    private fun keywordList(text: String): List<String> = rawTokens(text)
        .filter { it.length >= 3 }
        .filterNot { it in stopWords }
        .distinct()
        .take(80)

    private fun messageContextText(message: ChatMessage): String = buildString {
        if (message.text.isNotBlank()) append(message.text.trim())
        if (message.imagePath.isNotBlank()) {
            if (isNotEmpty()) append(" ")
            append("[photo")
            if (message.imageDescription.isNotBlank()) {
                append(": ")
                append(message.imageDescription.trim())
            }
            append("]")
        }
    }

    private fun rawTokens(text: String): List<String> =
        Regex("""[\p{L}\p{N}']+""")
            .findAll(text.lowercase())
            .map(MatchResult::value)
            .map { it.trim('\'') }
            .filter(String::isNotBlank)
            .toList()

    private fun refreshContextIndex(state: ConversationState): Int {
        val indexedMessages = state.messages.takeLast(MAX_INDEX_MESSAGES)
        val validIds = indexedMessages.mapTo(mutableSetOf()) { it.id }
        var changed = 0
        if (state.contextIndex.removeAll { it.messageId !in validIds }) changed++
        val existing = state.contextIndex.associateBy { it.messageId }.toMutableMap()

        indexedMessages.forEachIndexed { position, message ->
            val contextText = messageContextText(message)
            val normalized = normalizeIndex(contextText)
            val previousId = indexedMessages.getOrNull(position - 1)?.id.orEmpty()
            val nextId = indexedMessages.getOrNull(position + 1)?.id.orEmpty()
            val current = existing[message.id]

            if (current == null) {
                state.contextIndex += ContextIndexEntry(
                    messageId = message.id,
                    role = message.role,
                    normalizedText = normalized,
                    keywords = keywordList(contextText).toMutableList(),
                    phrases = phrases(contextText).toMutableList(),
                    timestamp = message.timestamp,
                    position = position,
                    feedback = message.feedback,
                    previousMessageId = previousId,
                    nextMessageId = nextId,
                )
                changed++
            } else {
                val contentChanged = current.normalizedText != normalized
                val metadataChanged = current.position != position ||
                    current.feedback != message.feedback ||
                    current.previousMessageId != previousId ||
                    current.nextMessageId != nextId

                current.normalizedText = normalized
                current.position = position
                current.feedback = message.feedback
                current.previousMessageId = previousId
                current.nextMessageId = nextId

                // Tokenization is the expensive part. Only redo it when message
                // content actually changed, not merely because a new turn moved
                // positions in the rolling 500-message window.
                if (contentChanged) {
                    current.keywords.clear()
                    current.keywords.addAll(keywordList(contextText))
                    current.phrases.clear()
                    current.phrases.addAll(phrases(contextText))
                }
                if (contentChanged || metadataChanged) changed++
            }
        }
        state.contextIndex.sortBy(ContextIndexEntry::position)
        if (changed > 0) FastContextCache.invalidate()
        return changed
    }

    /** Reconcile facts chronologically. Older superseded identities must never return on the next turn. */
    private fun refreshFacts(state: ConversationState): Int {
        val before = state.memories.map { it.sourceMessageId + "|" + it.slot + "|" + it.text }
        val window = state.messages.takeLast(MAX_INDEX_MESSAGES)
        val windowIds = window.mapTo(hashSetOf()) { it.id }
        val allIds = state.messages.mapTo(hashSetOf()) { it.id }
        val oldByKey = state.memories.associateBy { it.sourceMessageId + "|" + it.slot + "|" + it.text }
        val resolved = mutableListOf<MemoryItem>()
        // Keep older sourced facts and explicit pins until superseded or their source is deleted.
        resolved += state.memories.filter {
            it.sourceMessageId in allIds && (it.sourceMessageId !in windowIds || it.category == "pinned")
        }
        window.forEach { message ->
            if (message.role != "user") return@forEach
            val spoken = SceneDirector.parse(message.text).dialogue
            if (spoken.isBlank()) return@forEach
            val key = message.id + "|" + spoken
            val candidates = synchronized(factCache) {
                factCache[key] ?: extractCandidates(message.copy(text = spoken)).also {
                    factCache[key] = it
                    while (factCache.size > 600) factCache.remove(factCache.keys.first())
                }
            }
            candidates.forEach candidateLoop@ { candidate ->
                val text = normalize(candidate.text)
                if (text.length !in 8..360) return@candidateLoop
                if (candidate.slot in uniqueSlots) resolved.removeAll { it.slot == candidate.slot && it.category != "pinned" }
                if (resolved.any { it.text.equals(text, ignoreCase = true) }) return@candidateLoop
                val itemKey = message.id + "|" + candidate.slot + "|" + text
                resolved += oldByKey[itemKey] ?: MemoryItem(
                    text = text, category = candidate.category, slot = candidate.slot,
                    sourceMessageId = message.id, createdAt = message.timestamp, importance = candidate.importance,
                )
            }
        }
        val kept = resolved.sortedWith(compareByDescending<MemoryItem> { it.category == "pinned" }
            .thenByDescending { it.importance }.thenByDescending { it.createdAt }).take(MAX_FACT_MEMORIES)
            .sortedBy { it.createdAt }
        state.memories.clear(); state.memories.addAll(kept)
        return if (before != kept.map { it.sourceMessageId + "|" + it.slot + "|" + it.text }) 1 else 0
    }

    private fun refreshEpisodes(state: ConversationState): Int {
        val validMessages = state.messages.takeLast(MAX_INDEX_MESSAGES)
        val validIds = validMessages.mapTo(mutableSetOf()) { it.id }
        state.episodes.removeAll {
            it.userMessageId !in validIds || it.assistantMessageId !in validIds
        }
        val byAssistantId = state.episodes.associateBy { it.assistantMessageId }.toMutableMap()
        var pendingUser: ChatMessage? = null
        var added = 0

        validMessages.forEach { message ->
            when (message.role) {
                "user" -> pendingUser = message.copy(text = SceneDirector.parse(message.text).dialogue).takeIf { it.text.isNotBlank() }
                "assistant" -> {
                    val user = pendingUser ?: return@forEach
                    if (ReplyPipeline.shouldHideStoredReply(message.text, user.text)) return@forEach
                    val existing = byAssistantId[message.id]
                    if (existing != null) {
                        existing.userText = normalizeLong(user.text)
                        existing.assistantText = normalizeLong(message.text)
                        existing.feedback = message.feedback
                        existing.importance = episodeImportance(user, message)
                    } else {
                        val episode = EpisodeMemory(
                            userMessageId = user.id,
                            assistantMessageId = message.id,
                            userText = normalizeLong(user.text),
                            assistantText = normalizeLong(message.text),
                            createdAt = message.timestamp,
                            importance = episodeImportance(user, message),
                            feedback = message.feedback,
                        )
                        state.episodes += episode
                        byAssistantId[message.id] = episode
                        added++
                    }
                }
            }
        }

        if (state.episodes.size > MAX_EPISODES) {
            val retained = state.episodes
                .sortedWith(
                    compareByDescending<EpisodeMemory> { it.importance }
                        .thenByDescending { it.feedback }
                        .thenByDescending { it.accessCount }
                        .thenByDescending { it.createdAt },
                )
                .take(MAX_EPISODES)
                .sortedBy { it.createdAt }
            state.episodes.clear()
            state.episodes.addAll(retained)
        }
        return added
    }

    private fun episodeImportance(user: ChatMessage, assistant: ChatMessage): Int = when {
        assistant.feedback > 0 -> 5
        explicitMemory.containsMatchIn(user.text) -> 5
        relationshipEvent.containsMatchIn(user.text) -> 4
        futurePlan.containsMatchIn(user.text) -> 4
        user.text.length >= 240 -> 3
        else -> 2
    }

    private fun extractCandidates(message: ChatMessage): List<Candidate> {
        val cleaned = normalize(message.text)
        if (cleaned.isBlank() || sceneOnly.matches(cleaned)) return emptyList()

        return cleaned
            .split(Regex("""(?<=[.!?])\s+|\n+"""))
            .map(String::trim)
            .filter { it.length in 8..360 }
            .mapNotNull { sentence ->
                when {
                    nameFact.containsMatchIn(sentence) ->
                        Candidate(sentence, "identity", "identity:name", 5)
                    locationFact.containsMatchIn(sentence) ->
                        Candidate(sentence, "identity", "identity:location", 5)
                    workFact.containsMatchIn(sentence) ->
                        Candidate(sentence, "identity", "identity:work", 5)
                    birthdayFact.containsMatchIn(sentence) ->
                        Candidate(sentence, "identity", "identity:birthday", 5)
                    dislikeFact.containsMatchIn(sentence) ->
                        Candidate(sentence, "preference", preferenceSlot("dislike", sentence), 4)
                    likeFact.containsMatchIn(sentence) ->
                        Candidate(sentence, "preference", preferenceSlot("like", sentence), 4)
                    relationshipEvent.containsMatchIn(sentence) ->
                        Candidate(sentence, "relationship", "relationship:status", 4)
                    futurePlan.containsMatchIn(sentence) ->
                        Candidate(sentence, "plan", "plan:${keywords(sentence).take(3).joinToString("_")}", 4)
                    explicitMemory.containsMatchIn(sentence) ->
                        Candidate(sentence, "fact", "fact:${keywords(sentence).take(3).joinToString("_")}", 5)
                    message.role == "assistant" && message.feedback > 0 && sentence.length >= 24 ->
                        Candidate(sentence, "relationship", "assistant:liked", 2)
                    else -> null
                }
            }
    }

    private fun preferenceSlot(prefix: String, sentence: String): String {
        val subject = keywords(sentence).toList().takeLast(3).joinToString("_").ifBlank { "general" }
        return "preference:$prefix:$subject"
    }

    private fun exactTextBonus(query: String, candidate: String): Double {
        val normalizedQuery = normalizeLong(query).lowercase()
        val normalizedCandidate = normalizeLong(candidate).lowercase()
        return when {
            normalizedQuery.length >= 8 && normalizedCandidate.contains(normalizedQuery) -> 18.0
            normalizedCandidate.length >= 8 && normalizedQuery.contains(normalizedCandidate) -> 7.0
            else -> 0.0
        }
    }

    private fun normalize(value: String): String =
        value.replace(Regex("""\s+"""), " ").trim().take(360)

    private fun normalizeLong(value: String): String =
        value.replace(Regex("""\s+"""), " ").trim().take(1600)

    private fun normalizeIndex(value: String): String =
        value.replace(Regex("""\s+"""), " ").trim().take(900)

    private data class Candidate(
        val text: String,
        val category: String,
        val slot: String,
        val importance: Int,
    )

    private data class ScoredMemory(
        val memory: MemoryItem,
        val score: Double,
    )

    private data class ScoredEpisode(
        val episode: EpisodeMemory,
        val score: Double,
    )

}
