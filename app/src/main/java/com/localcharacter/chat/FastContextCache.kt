package com.localcharacter.chat

import kotlin.math.ln

/**
 * Process-wide retrieval cache. Persisted ContextIndexEntry values remain the
 * source of truth; this object only avoids rebuilding token maps every turn.
 */
object FastContextCache {
    private val lock = Any()
    private var signature: Long = Long.MIN_VALUE
    private var cachedEntries: List<ContextIndexEntry> = emptyList()
    private var messageById: Map<String, ChatMessage> = emptyMap()
    private var tokenPostings: Map<String, IntArray> = emptyMap()
    private var phrasePostings: Map<String, IntArray> = emptyMap()
    private var tokenDocumentFrequency: Map<String, Int> = emptyMap()

    private val keywordCache = object : LinkedHashMap<String, Set<String>>(256, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Set<String>>?,
        ): Boolean = size > 2_000
    }

    private val phraseCache = object : LinkedHashMap<String, Set<String>>(256, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Set<String>>?,
        ): Boolean = size > 2_000
    }

    fun invalidate() {
        synchronized(lock) {
            signature = Long.MIN_VALUE
            cachedEntries = emptyList()
            messageById = emptyMap()
            tokenPostings = emptyMap()
            phrasePostings = emptyMap()
            tokenDocumentFrequency = emptyMap()
        }
    }

    fun retrieveIndexedMessages(
        state: ConversationState,
        query: String,
        limit: Int,
        preferRecent: Boolean,
        neighborRadius: Int,
    ): List<ChatMessage> {
        if (limit <= 0 || state.contextIndex.isEmpty() || state.messages.isEmpty()) {
            return emptyList()
        }
        ensureBuilt(state)

        val entries: List<ContextIndexEntry>
        val messages: Map<String, ChatMessage>
        val postings: Map<String, IntArray>
        val pPostings: Map<String, IntArray>
        val documentFrequency: Map<String, Int>
        synchronized(lock) {
            entries = cachedEntries
            messages = messageById
            postings = tokenPostings
            pPostings = phrasePostings
            documentFrequency = tokenDocumentFrequency
        }
        if (entries.isEmpty()) return emptyList()

        val queryKeywords = MemoryManager.keywords(query)
        val queryPhrases = MemoryManager.phrases(query)
        val candidates = linkedSetOf<Int>()
        queryKeywords.forEach { token ->
            postings[token]?.forEach { position -> candidates += position }
        }
        queryPhrases.forEach { phrase ->
            pPostings[phrase]?.forEach { position -> candidates += position }
        }

        if (preferRecent || candidates.isEmpty()) {
            val recentStart = (entries.size - maxOf(12, limit * 2)).coerceAtLeast(0)
            for (index in recentStart until entries.size) candidates += index
        }

        val normalizedQuery = normalize(query)
        val totalDocuments = entries.size.coerceAtLeast(1)
        val anchors = candidates
            .mapNotNull { position -> entries.getOrNull(position)?.let { position to it } }
            .map { (position, entry) ->
                val entryKeywords = entry.keywords.toHashSet()
                val keywordScore = queryKeywords.sumOf { token ->
                    if (token !in entryKeywords) {
                        0.0
                    } else {
                        val frequency = (documentFrequency[token] ?: 1).coerceAtLeast(1)
                        4.0 + ln((totalDocuments + 1.0) / frequency.toDouble()) * 2.2
                    }
                }
                val phraseScore = queryPhrases.count { it in entry.phrases } * 8.5
                val exactScore = when {
                    normalizedQuery.length >= 8 && entry.normalizedText.contains(normalizedQuery) -> 22.0
                    normalizedQuery.length >= 5 && normalizedQuery.contains(entry.normalizedText) -> 7.0
                    else -> 0.0
                }
                val recency = (position + 1).toDouble() / entries.size.toDouble()
                val recencyScore = if (preferRecent) recency * 8.0 else recency * 1.5
                val feedbackScore = when {
                    entry.feedback > 0 -> 1.5
                    entry.feedback < 0 -> -3.0
                    else -> 0.0
                }
                ScoredPosition(
                    position = position,
                    score = keywordScore + phraseScore + exactScore + recencyScore +
                        (if (entry.role == "user") 0.6 else 0.0) + feedbackScore,
                )
            }
            .filter { queryKeywords.isEmpty() || it.score >= 3.5 || preferRecent }
            .sortedByDescending(ScoredPosition::score)
            .take(limit.coerceAtLeast(if (preferRecent) 4 else 1))

        if (anchors.isEmpty()) {
            return entries.takeLast(limit.coerceAtMost(8)).mapNotNull { messages[it.messageId] }
        }

        val positions = linkedSetOf<Int>()
        anchors.forEach { anchor ->
            val start = (anchor.position - neighborRadius).coerceAtLeast(0)
            val end = (anchor.position + neighborRadius).coerceAtMost(entries.lastIndex)
            for (position in start..end) positions += position
        }

        val maximum = (limit * (neighborRadius * 2 + 1)).coerceIn(limit, 28)
        return positions
            .sorted()
            .takeLast(maximum)
            .mapNotNull { entries.getOrNull(it)?.messageId?.let(messages::get) }
            .distinctBy(ChatMessage::id)
    }

    fun cachedKeywords(cacheKey: String, text: String): Set<String> = synchronized(lock) {
        val key = "$cacheKey:${text.hashCode()}"
        keywordCache[key] ?: MemoryManager.keywords(text).also { keywordCache[key] = it }
    }

    fun cachedPhrases(cacheKey: String, text: String): Set<String> = synchronized(lock) {
        val key = "$cacheKey:${text.hashCode()}"
        phraseCache[key] ?: MemoryManager.phrases(text).also { phraseCache[key] = it }
    }

    private fun ensureBuilt(state: ConversationState) {
        val nextSignature = signatureOf(state)
        synchronized(lock) {
            if (nextSignature == signature) return

            val entries = state.contextIndex.sortedBy(ContextIndexEntry::position)
            val tokenLists = mutableMapOf<String, MutableList<Int>>()
            val phraseLists = mutableMapOf<String, MutableList<Int>>()
            val frequencies = mutableMapOf<String, Int>()
            entries.forEachIndexed { index, entry ->
                entry.keywords.distinct().forEach { token ->
                    tokenLists.getOrPut(token) { mutableListOf() } += index
                    frequencies[token] = (frequencies[token] ?: 0) + 1
                }
                entry.phrases.distinct().forEach { phrase ->
                    phraseLists.getOrPut(phrase) { mutableListOf() } += index
                }
            }

            cachedEntries = entries
            messageById = state.messages.takeLast(MemoryManager.MAX_INDEX_MESSAGES)
                .associateBy(ChatMessage::id)
            tokenPostings = tokenLists.mapValues { (_, positions) -> positions.toIntArray() }
            phrasePostings = phraseLists.mapValues { (_, positions) -> positions.toIntArray() }
            tokenDocumentFrequency = frequencies
            signature = nextSignature
        }
    }

    private fun signatureOf(state: ConversationState): Long {
        var value = 1_125_899_906_842_597L
        state.contextIndex.sortedBy(ContextIndexEntry::position).forEach { entry ->
            value = value * 31 + entry.messageId.hashCode()
            value = value * 31 + entry.normalizedText.hashCode()
            value = value * 31 + entry.feedback
            value = value * 31 + entry.position
        }
        return value
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private data class ScoredPosition(
        val position: Int,
        val score: Double,
    )
}
