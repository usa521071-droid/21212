package com.localcharacter.chat

import kotlin.math.ln

data class ContextBundle(
    val directive: String,
    val activeThreadLabel: String,
    val activeThreadSummary: String,
    val openLoops: List<String>,
    val directMessages: List<ChatMessage>,
    val linkedMessages: List<ChatMessage>,
    val threadMessages: List<ChatMessage>,
    val currentIntent: String,
    val activeSubject: String,
    val referentHints: List<String>,
)

object ContextUnderstandingEngine {
    private const val MAX_THREADS = 80
    private const val MAX_THREAD_MESSAGES = 80
    private const val MAX_THREAD_KEYWORDS = 48
    private const val MAX_THREAD_ENTITIES = 32

    private val greeting = Regex(
        """(?iu)^\s*(?:hi+|hello+|hey+|hei+|salut+|bun[ăa]+|yo+|ciao+)[\s!.?🙂😊👋]*$""",
    )
    private val thanks = Regex(
        """(?iu)^\s*(?:thanks?|thank you|thx|mersi|mulțumesc|multumesc)[\s!.?🙂😊👍❤️😘💋]*$""",
    )
    private val apology = Regex(
        """(?iu)^\s*(?:sorry|i'?m sorry|my bad|scuze|îmi pare rău|imi pare rau)[\s!.?]*$""",
    )
    private val acknowledgement = Regex(
        """(?iu)^\s*(?:ok(?:ay)?|yes|yeah|yep|sure|fine|good|nice|great|perfect|alright|all right|da|bine|sigur|super|perfect)[\s!.?🙂😊👍😂❤️😘💋]*$""",
    )
    private val negativeAnswer = Regex(
        """(?iu)^\s*(?:no|nope|nah|nu)[\s!.?]*$""",
    )
    private val correction = Regex(
        """(?iu)\b(?:i mean|i meant|what i mean|no[, ]+i mean|no[, ]+i meant|actually|rather|to clarify|correction|adică|adica|voiam să spun|vroiam să spun|de fapt|mai exact|corectez)\b""",
    )
    private val whyFollowup = Regex(
        """(?iu)^\s*(?:why|why not|how so|how come|de ce|de ce nu|cum așa|cum asa)[?.!]*\s*$""",
    )
    private val reference = Regex(
        """(?iu)\b(?:it|that|this|those|them|there|then|before|earlier|again|what about|you said|you just said|the last one|the last message|remember that|do you remember|asta|aia|acela|aceea|ele|ei|acolo|atunci|mai devreme|înainte|inainte|din nou|ai spus|ultima|ultimul mesaj|îți amintești|iti amintesti)\b""",
    )
    private val proposal = Regex(
        """(?iu)\b(?:can i|can we|could i|could we|would you|do you want to|wanna|want to|let'?s|shall we|how about we|go on a date|go out with|meet me|pot să|pot sa|putem|ai vrea|vrei să|vrei sa|hai să|hai sa|ieșim|iesim|întâlnim|intalnim)\b""",
    )
    private val request = Regex(
        """(?iu)^\s*(?:please\b|can you\b|could you\b|would you\b|will you\b|tell me\b|show me\b|send me\b|give me\b|help me\b|te rog\b|poți\b|poti\b|spune-mi\b|arată-mi\b|arata-mi\b|trimite-mi\b|dă-mi\b|da-mi\b|ajută-mă\b|ajuta-ma\b)""",
    )
    private val refusal = Regex(
        """(?iu)\b(?:no\b|can't|cannot|won't|will not|not comfortable|don't want|do not want|not going to|rather not|nu pot|nu vreau|nu mă simt confortabil|nu ma simt confortabil|n-o să|nu o să)\b""",
    )
    private val questionLead = Regex(
        """(?iu)^\s*(?:who|what|when|where|why|how|which|can|could|would|will|do|does|did|is|are|was|were|have|has|should|cine|ce|când|cand|unde|de ce|cum|care|poți|poti|putem|ai|este|e|sunt|ar)\b""",
    )
    private val affection = Regex(
        """(?iu)\b(?:i like you|i love you|miss you|baby|babe|darling|sweetheart|te plac|te iubesc|mi-e dor|iubire|dragule|draga mea)\b""",
    )
    private val scene = Regex("""(?s)\*[^*]{1,1200}\*""")
    private val dateOrTime = Regex(
        """(?iu)\b(?:today|tomorrow|tonight|yesterday|monday|tuesday|wednesday|thursday|friday|saturday|sunday|week|month|year|azi|mâine|maine|diseară|diseara|ieri|luni|marți|marti|miercuri|joi|vineri|sâmbătă|sambata|duminică|duminica|săptămână|saptamana|lună|luna|an)\b|\b\d{1,4}[:/.-]\d{1,4}(?:[:/.-]\d{1,4})?\b""",
    )

    fun refresh(state: ConversationState) {
        val messages = state.messages.takeLast(MemoryManager.MAX_INDEX_MESSAGES)
        if (messages.isEmpty()) {
            state.contextThreads.clear()
            state.dialogueContext = DialogueContext()
            return
        }

        val latestUserId = messages.lastOrNull { it.role == "user" }?.id.orEmpty()
        val latestAssistantId = messages.lastOrNull { it.role == "assistant" }?.id.orEmpty()
        val lastIndexedId = state.contextIndex.maxByOrNull(ContextIndexEntry::position)?.messageId.orEmpty()
        if (
            state.dialogueContext.version >= 3 &&
            state.dialogueContext.lastUserMessageId == latestUserId &&
            state.dialogueContext.lastAssistantMessageId == latestAssistantId &&
            lastIndexedId == messages.last().id &&
            state.contextThreads.isNotEmpty()
        ) {
            return
        }

        val entriesById = state.contextIndex.associateBy { it.messageId }
        val threads = mutableListOf<ContextThread>()
        var threadCounter = 0
        var previousEntry: ContextIndexEntry? = null
        var lastUserEntry: ContextIndexEntry? = null
        var lastAssistantEntry: ContextIndexEntry? = null

        messages.forEachIndexed { index, message ->
            val entry = entriesById[message.id] ?: return@forEachIndexed
            val text = messageContextText(message)
            val intent = classifyIntent(text, message.role)
            val subject = extractSubject(text)
            val entities = extractEntities(text)

            val replyTo = when (message.role) {
                "assistant" -> lastUserEntry?.messageId.orEmpty()
                else -> previousEntry?.messageId.orEmpty()
            }
            val correctionTarget = if (intent == "correction") {
                previousEntry?.messageId.orEmpty()
            } else {
                ""
            }

            val thread = chooseThread(
                message = message,
                intent = intent,
                subject = subject,
                keywords = MemoryManager.keywords(text),
                entities = entities.toSet(),
                previousEntry = previousEntry,
                lastUserEntry = lastUserEntry,
                threads = threads,
                entriesById = entriesById,
                position = index,
            ) ?: ContextThread(
                id = "thread-${++threadCounter}",
                label = subject.ifBlank {
                    MemoryManager.keywords(text).take(4).joinToString(" ").ifBlank { "conversation" }
                },
                createdAt = message.timestamp,
                lastActiveAt = message.timestamp,
            ).also { threads += it }

            entry.threadId = thread.id
            entry.intent = intent
            entry.subject = subject
            entry.entities.clear()
            entry.entities.addAll(entities.take(16))
            entry.replyToMessageId = replyTo
            entry.correctionTargetMessageId = correctionTarget

            updateThread(thread, message, entry)

            if (message.role == "user") {
                lastUserEntry = entry
            } else {
                lastAssistantEntry = entry
                resolveOpenLoop(thread)
            }
            previousEntry = entry
        }

        state.contextThreads.clear()
        state.contextThreads.addAll(
            threads
                .sortedByDescending { it.lastActiveAt }
                .take(MAX_THREADS)
                .sortedBy { it.createdAt },
        )

        val latestUser = messages.lastOrNull { it.role == "user" }
        val latestUserEntry = latestUser?.let { entriesById[it.id] }
        val latestAssistant = messages.lastOrNull { it.role == "assistant" }
        val latestAssistantEntry = latestAssistant?.let { entriesById[it.id] }

        state.dialogueContext = DialogueContext(
            activeThreadId = latestUserEntry?.threadId.orEmpty(),
            activeSubject = latestUserEntry?.subject.orEmpty(),
            lastUserIntent = latestUserEntry?.intent.orEmpty(),
            lastAssistantIntent = latestAssistantEntry?.intent.orEmpty(),
            lastUserMessageId = latestUser?.id.orEmpty(),
            lastAssistantMessageId = latestAssistant?.id.orEmpty(),
            lastQuestionMessageId = entriesById.values
                .filter { it.role == "user" && it.intent in setOf("question", "why_followup") }
                .maxByOrNull { it.position }?.messageId.orEmpty(),
            lastProposalMessageId = entriesById.values
                .filter { it.role == "user" && it.intent in setOf("proposal", "request") }
                .maxByOrNull { it.position }?.messageId.orEmpty(),
            lastRefusalMessageId = entriesById.values
                .filter { it.role == "assistant" && it.intent == "refusal" }
                .maxByOrNull { it.position }?.messageId.orEmpty(),
            lastCorrectionMessageId = entriesById.values
                .filter { it.role == "user" && it.intent == "correction" }
                .maxByOrNull { it.position }?.messageId.orEmpty(),
            linkedMessageIds = buildReplyChain(latestUserEntry, entriesById, 12).toMutableList(),
            version = 3,
        )
    }

    fun buildBundle(
        state: ConversationState,
        latestUserText: String,
        mode: PromptMode,
        semanticLimit: Int,
    ): ContextBundle {
        refresh(state)
        val messages = state.messages
        val byId = messages.associateBy { it.id }
        val entries = state.contextIndex.associateBy { it.messageId }
        val latestUser = messages.lastOrNull { it.role == "user" }
            ?: return ContextBundle("", "", "", emptyList(), emptyList(), emptyList(), emptyList(), "statement", "", emptyList())
        val latestEntry = entries[latestUser.id]
        val intent = latestEntry?.intent ?: classifyIntent(latestUserText, "user")
        val activeThread = state.contextThreads.firstOrNull { it.id == latestEntry?.threadId }

        val directCount = when (intent) {
            "why_followup", "correction", "reference", "answer", "acknowledgement", "thanks", "apology" -> 8
            else -> when (mode) {
                PromptMode.GREETING -> 2
                PromptMode.SHORT -> 4
                PromptMode.REFERENCE -> 8
                else -> 6
            }
        }
        val latestIndex = messages.indexOfLast { it.id == latestUser.id }
        val direct = if (latestIndex > 0) {
            messages.subList((latestIndex - directCount).coerceAtLeast(0), latestIndex)
        } else {
            emptyList()
        }

        val linkedIds = linkedSetOf<String>()
        buildReplyChain(latestEntry, entries, 14).forEach(linkedIds::add)
        val correctionTargetId = latestEntry?.correctionTargetMessageId.orEmpty()
        if (correctionTargetId.isNotBlank()) {
            linkedIds += correctionTargetId
            val target = entries[correctionTargetId]
            buildReplyChain(target, entries, 6).forEach(linkedIds::add)
        }
        val linked = linkedIds.mapNotNull(byId::get)
            .filterNot { it.id == latestUser.id }
            .sortedBy { messages.indexOfFirst { candidate -> candidate.id == it.id } }

        val threadMessages = activeThread?.messageIds.orEmpty()
            .mapNotNull(byId::get)
            .filterNot { it.id == latestUser.id }
            .let { selectThreadMessages(it, latestUserText, mode) }

        val semantic = if (semanticLimit > 0 && intent !in setOf("greeting", "thanks", "acknowledgement", "apology")) {
            MemoryManager.retrieveIndexedMessages(
                state = state,
                query = latestUserText,
                limit = semanticLimit,
                preferRecent = intent in setOf("why_followup", "reference", "correction"),
                neighborRadius = if (intent in setOf("why_followup", "reference", "correction")) 2 else 1,
            )
        } else {
            emptyList()
        }

        val excluded = (direct + linked + threadMessages)
            .mapTo(mutableSetOf()) { it.id }
        val semanticDistinct = semantic.filterNot { it.id in excluded || it.id == latestUser.id }

        val baseDirective = buildDirective(intent, latestUserText, latestEntry, entries, byId)
        val sceneDirective = if (state.sceneState.active && state.sceneState.premise.isNotBlank()) {
            " Active director scene is still in force; resolve ambiguous references inside that scene before searching unrelated old topics."
        } else {
            ""
        }
        val referentHints = buildReferentHints(
            latestUserText = latestUserText,
            direct = direct,
            linked = linked,
            activeThread = activeThread,
        )

        return ContextBundle(
            directive = (baseDirective + sceneDirective).trim(),
            activeThreadLabel = activeThread?.label.orEmpty(),
            activeThreadSummary = activeThread?.summary.orEmpty(),
            openLoops = activeThread?.openLoops?.takeLast(5).orEmpty(),
            directMessages = direct,
            linkedMessages = linked,
            threadMessages = threadMessages,
            currentIntent = intent,
            activeSubject = latestEntry?.subject.orEmpty(),
            referentHints = referentHints,
        ).let { bundle ->
            // We intentionally merge semantic matches into the thread context packet at the
            // caller to keep the bundle backward-compatible and easy to budget.
            bundle.copy(
                threadMessages = (bundle.threadMessages + semanticDistinct)
                    .distinctBy { it.id }
                    .sortedBy { message -> messages.indexOfFirst { it.id == message.id } },
            )
        }
    }

    private fun buildReferentHints(
        latestUserText: String,
        direct: List<ChatMessage>,
        linked: List<ChatMessage>,
        activeThread: ContextThread?,
    ): List<String> {
        val ambiguous = reference.containsMatchIn(latestUserText) ||
            Regex("""(?iu)\b(?:she|he|they|her|him|them|that one|the other one|it|this|that|those|there)\b""")
                .containsMatchIn(latestUserText)
        if (!ambiguous) return emptyList()

        val hints = linked.takeLast(4) + direct.takeLast(4)
        val snippets = hints
            .map { message ->
                val who = if (message.role == "user") "User" else "Character"
                "$who: ${normalize(messageContextText(message)).take(220)}"
            }
            .filterNot { it.endsWith(":") }
            .distinct()
            .toMutableList()
        activeThread?.label?.takeIf(String::isNotBlank)?.let {
            snippets += "Active topic: ${it.take(140)}"
        }
        return snippets.takeLast(6)
    }

    private fun chooseThread(
        message: ChatMessage,
        intent: String,
        subject: String,
        keywords: Set<String>,
        entities: Set<String>,
        previousEntry: ContextIndexEntry?,
        lastUserEntry: ContextIndexEntry?,
        threads: List<ContextThread>,
        entriesById: Map<String, ContextIndexEntry>,
        position: Int,
    ): ContextThread? {
        if (message.role == "assistant") {
            val inherited = lastUserEntry?.threadId.orEmpty()
            if (inherited.isNotBlank()) return threads.firstOrNull { it.id == inherited }
            return previousEntry?.threadId?.let { id -> threads.firstOrNull { it.id == id } }
        }

        if (previousEntry != null && isContinuationIntent(intent)) {
            return threads.firstOrNull { it.id == previousEntry.threadId }
        }

        if (previousEntry != null && isVeryShortContinuation(message.text)) {
            return threads.firstOrNull { it.id == previousEntry.threadId }
        }

        var best: ContextThread? = null
        var bestScore = Double.NEGATIVE_INFINITY
        threads.forEach { thread ->
            val threadKeywords = thread.keywords.toSet()
            val threadEntities = thread.entities.toSet()
            val keywordOverlap = keywords.intersect(threadKeywords).size
            val entityOverlap = entities.intersect(threadEntities).size
            val subjectOverlap = if (
                subject.isNotBlank() &&
                thread.label.isNotBlank() &&
                MemoryManager.keywords(subject).intersect(MemoryManager.keywords(thread.label)).isNotEmpty()
            ) 1 else 0
            val lastPosition = thread.messageIds.lastOrNull()
                ?.let(entriesById::get)
                ?.position
                ?: 0
            val distance = (position - lastPosition).coerceAtLeast(0)
            val recency = 1.0 / (1.0 + distance / 6.0)
            val previousBonus = if (thread.id == previousEntry?.threadId) 1.4 else 0.0
            val score = keywordOverlap * 4.2 +
                entityOverlap * 6.0 +
                subjectOverlap * 3.0 +
                recency * 2.2 +
                previousBonus
            if (score > bestScore) {
                best = thread
                bestScore = score
            }
        }

        val threshold = when {
            keywords.isEmpty() && entities.isEmpty() -> 5.0
            keywords.size <= 2 -> 4.6
            else -> 5.4
        }
        return best?.takeIf { bestScore >= threshold }
    }

    private fun updateThread(
        thread: ContextThread,
        message: ChatMessage,
        entry: ContextIndexEntry,
    ) {
        if (thread.messageIds.size >= MAX_THREAD_MESSAGES) {
            thread.messageIds.removeAt(0)
        }
        thread.messageIds += message.id
        thread.lastActiveAt = message.timestamp

        val combinedKeywords = (thread.keywords + entry.keywords)
            .distinct()
            .takeLast(MAX_THREAD_KEYWORDS)
        thread.keywords.clear()
        thread.keywords.addAll(combinedKeywords)

        val combinedEntities = (thread.entities + entry.entities)
            .distinctBy(String::lowercase)
            .takeLast(MAX_THREAD_ENTITIES)
        thread.entities.clear()
        thread.entities.addAll(combinedEntities)

        if (thread.label.isBlank() || thread.label == "conversation") {
            thread.label = entry.subject.ifBlank {
                entry.keywords.take(4).joinToString(" ").ifBlank { "conversation" }
            }
        }

        val role = if (message.role == "user") "User" else "Character"
        val snippet = "$role: ${normalize(messageContextText(message)).take(180)}"
        val old = thread.summary
            .split(" • ")
            .filter(String::isNotBlank)
            .takeLast(5)
            .toMutableList()
        old += snippet
        thread.summary = old.takeLast(6).joinToString(" • ").take(1100)

        if (message.role == "user" && entry.intent in setOf("question", "why_followup", "request", "proposal")) {
            val loop = normalize(message.text).take(240)
            if (loop.isNotBlank()) {
                thread.openLoops.removeAll { normalize(it).equals(loop, ignoreCase = true) }
                thread.openLoops += loop
                while (thread.openLoops.size > 8) thread.openLoops.removeAt(0)
            }
        }
    }

    private fun resolveOpenLoop(thread: ContextThread) {
        if (thread.openLoops.isNotEmpty()) {
            thread.openLoops.removeAt(thread.openLoops.lastIndex)
        }
    }

    private fun selectThreadMessages(
        messages: List<ChatMessage>,
        query: String,
        mode: PromptMode,
    ): List<ChatMessage> {
        if (messages.isEmpty()) return emptyList()
        val queryKeywords = MemoryManager.keywords(query)
        val queryPhrases = MemoryManager.phrases(query)
        val maxCount = when (mode) {
            PromptMode.GREETING -> 0
            PromptMode.SHORT -> 4
            PromptMode.REFERENCE -> 16
            PromptMode.NORMAL -> 12
            PromptMode.DEEP -> 20
            PromptMode.SCENE -> 14
        }
        if (maxCount == 0) return emptyList()

        val newest = messages.takeLast((maxCount / 2).coerceAtLeast(4))
        val newestIds = newest.mapTo(mutableSetOf()) { it.id }
        val older = messages.dropLast(newest.size)
            .mapIndexed { index, message ->
                val text = messageContextText(message)
                val overlap = queryKeywords.intersect(MemoryManager.keywords(text)).size
                val phraseOverlap = queryPhrases.intersect(MemoryManager.phrases(text)).size
                val recency = (index + 1).toDouble() / messages.size.coerceAtLeast(1)
                message to (overlap * 5.0 + phraseOverlap * 7.0 + recency)
            }
            .filter { it.second >= 3.0 }
            .sortedByDescending { it.second }
            .take((maxCount - newest.size).coerceAtLeast(0))
            .map { it.first }
        return (older + newest)
            .filterNot { it.id in newestIds && it !in newest }
            .distinctBy { it.id }
            .sortedBy { message -> messages.indexOfFirst { it.id == message.id } }
            .takeLast(maxCount)
    }

    private fun buildDirective(
        intent: String,
        latest: String,
        latestEntry: ContextIndexEntry?,
        entries: Map<String, ContextIndexEntry>,
        messages: Map<String, ChatMessage>,
    ): String {
        val previous = latestEntry?.replyToMessageId?.let(messages::get)
        val previousPrevious = latestEntry?.replyToMessageId
            ?.let(entries::get)
            ?.replyToMessageId
            ?.let(messages::get)

        return when (intent) {
            "correction" -> buildString {
                append("The newest message is a CORRECTION/CLARIFICATION. Replace the earlier misunderstanding with the user's corrected meaning. ")
                if (previousPrevious != null) {
                    append("Earlier user meaning being clarified: \"${normalize(messageContextText(previousPrevious)).take(260)}\". ")
                }
                if (previous != null) {
                    append("The character's immediately preceding interpretation was: \"${normalize(messageContextText(previous)).take(260)}\". ")
                }
                append("Acknowledge the correction naturally and respond to the corrected meaning; do not continue the mistaken interpretation.")
            }
            "why_followup" -> buildString {
                append("The newest message asks WHY/HOW about the character's immediately previous answer. ")
                if (previous != null) {
                    append("Explain or respond to this exact previous answer: \"${normalize(messageContextText(previous)).take(320)}\". ")
                }
                if (previousPrevious != null) {
                    append("That answer was responding to: \"${normalize(messageContextText(previousPrevious)).take(280)}\". ")
                }
                append("Do not switch to an older topic.")
            }
            "reference" -> "Resolve words like 'that', 'it', 'them', 'before', or 'earlier' from the linked and immediate context below. Stay on the referenced turn unless the user explicitly changes topic."
            "thanks" -> "The newest message is gratitude. Acknowledge it naturally and briefly. Do not repeat the information the user is thanking you for."
            "acknowledgement" -> "The newest message is a short acknowledgement/acceptance. React to it as the next turn. Do not repeat the previous answer or reopen an older topic."
            "apology" -> "The newest message is an apology. Respond to the apology and current relationship/mood; do not repeat the earlier subject unless needed."
            "answer" -> buildString {
                append("The newest message is an ANSWER to the character's preceding question or proposal. ")
                if (previous != null) append("The preceding character message was: \"${normalize(messageContextText(previous)).take(300)}\". ")
                append("Continue from that answer rather than treating it as a new unrelated topic.")
            }
            "proposal" -> "The newest message contains a proposal/invitation. Answer that exact proposal directly according to relationship and mood."
            "request" -> "The newest message contains a request. Address that request directly before adding anything else."
            "question" -> "Answer the newest question directly. Use older context only to resolve what the question refers to."
            "refusal" -> "The newest message is a refusal/boundary. Respect and react to that boundary; do not pressure the user."
            "affection" -> "The newest message expresses affection. Respond according to the actual relationship stage, trust, love, and mood rather than automatically escalating intimacy."
            "greeting" -> "This is a greeting. Reply to the greeting only; no old topic should be resumed unless the user mentions it."
            else -> "Treat the newest message as the active turn. Use older context only when it is genuinely relevant; never continue a stale topic merely because it appears in memory."
        }
    }

    private fun buildReplyChain(
        start: ContextIndexEntry?,
        entries: Map<String, ContextIndexEntry>,
        maxDepth: Int,
    ): List<String> {
        val result = mutableListOf<String>()
        var current = start
        var depth = 0
        val seen = mutableSetOf<String>()
        while (current != null && depth < maxDepth) {
            val nextId = current.replyToMessageId
            if (nextId.isBlank() || !seen.add(nextId)) break
            result += nextId
            current = entries[nextId]
            depth++
        }
        return result
    }

    private fun classifyIntent(text: String, role: String): String {
        val clean = text.trim()
        if (clean.isBlank()) return "statement"
        if (scene.containsMatchIn(clean)) return "scene"
        if (greeting.matches(clean)) return "greeting"
        if (thanks.matches(clean)) return "thanks"
        if (apology.matches(clean)) return "apology"
        if (whyFollowup.matches(clean)) return "why_followup"
        if (correction.containsMatchIn(clean)) return "correction"
        if (negativeAnswer.matches(clean)) return if (role == "assistant") "refusal" else "answer"
        if (acknowledgement.matches(clean)) return "acknowledgement"
        if (role == "assistant" && refusal.containsMatchIn(clean)) return "refusal"
        if (proposal.containsMatchIn(clean)) return "proposal"
        if (request.containsMatchIn(clean)) return "request"
        if (affection.containsMatchIn(clean)) return "affection"
        if (reference.containsMatchIn(clean)) return "reference"
        if (clean.endsWith("?") || questionLead.containsMatchIn(clean)) return "question"
        return "statement"
    }

    private fun isContinuationIntent(intent: String): Boolean =
        intent in setOf(
            "thanks",
            "apology",
            "why_followup",
            "correction",
            "acknowledgement",
            "answer",
            "reference",
        )

    private fun isVeryShortContinuation(text: String): Boolean {
        val clean = normalize(text)
        if (clean.length > 36) return false
        val words = clean.split(Regex("""\s+""")).filter(String::isNotBlank)
        return words.size <= 5
    }

    private fun extractSubject(text: String): String {
        val keywords = MemoryManager.keywords(text).toList()
        if (keywords.isEmpty()) return ""
        return keywords.take(6).joinToString(" ")
    }

    private fun extractEntities(text: String): List<String> {
        val result = linkedSetOf<String>()
        Regex("""["“]([^"”]{2,80})["”]""").findAll(text).forEach {
            result += normalize(it.groupValues[1]).take(80)
        }
        Regex("""\b[A-ZĂÂÎȘȚ][\p{L}'’-]{2,}\b""").findAll(text).forEach {
            result += it.value
        }
        dateOrTime.findAll(text).forEach {
            result += normalize(it.value).take(80)
        }
        return result.filter(String::isNotBlank).take(16)
    }

    private fun messageContextText(message: ChatMessage): String = buildString {
        if (message.text.isNotBlank()) append(message.text.trim())
        if (message.imagePath.isNotBlank()) {
            if (isNotEmpty()) append(" ")
            append("[photo")
            if (message.imageDescription.isNotBlank()) {
                append(": ")
                append(message.imageDescription.trim().take(500))
            }
            append("]")
        }
    }

    private fun normalize(value: String): String =
        value.replace(Regex("""\s+"""), " ").trim().take(1600)
}
