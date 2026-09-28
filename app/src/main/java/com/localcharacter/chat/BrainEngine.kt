package com.localcharacter.chat

import java.util.Calendar
import kotlin.math.abs

object BrainEngine {
    private val directedAffection = Regex(
        """(?iu)\b(?:i love you|love you|love u|i miss you|miss you|you are beautiful|you're beautiful|you are cute|you're cute|baby|babe|darling|sweetheart|te iubesc|mi-e dor de tine|ești frumoasă|ești frumos|iubita mea|iubitul meu)\b""",
    )
    private val warmPositive = Regex(
        """(?iu)\b(?:thank you|thanks|please|good job|well done|amazing|wonderful|nice|sweet|kind|mulțumesc|mersi|te rog|bravo|super|minunat|drăguț|drăguță)\b""",
    )
    private val hostility = Regex(
        """(?iu)\b(?:idiot|stupid|shut up|hate you|annoying|useless|fuck you|fuck off|moron|prost|proastă|taci|te urăsc|enervant|enervantă|inutil|idioată|idiotule)\b""",
    )
    private val apology = Regex(
        """(?iu)\b(?:sorry|i apologize|my fault|forgive me|scuze|îmi pare rău|iertare|vina mea)\b""",
    )
    private val playful = Regex(
        """(?iu)(?:\b(?:lol|haha|hehe|joke|tease|kidding|glumesc|haha|hehe)\b|[😂🤣😜😉])""",
    )
    private val morning = Regex(
        """(?iu)\b(?:good morning|morning|bună dimineața|neata|neața)\b""",
    )
    private val night = Regex(
        """(?iu)\b(?:good night|sleep well|go to sleep|noapte bună|dormi bine|culcă-te)\b""",
    )
    private val futureThread = Regex(
        """(?iu)\b(?:tomorrow|tonight|later|friday|saturday|sunday|next week|we will|we're going to|we should|remember to|don't forget|mâine|diseară|mai târziu|vineri|sâmbătă|duminică|săptămâna viitoare|vom|o să|ar trebui|ține minte|nu uita)\b""",
    )
    private val resolveThread = Regex(
        """(?iu)\b(?:done|finished|cancel that|forget that|never mind|nevermind|gata|terminat|anulează|uită asta|lasă)\b""",
    )
    private val directQuestion = Regex("""\?\s*$""")
    private val allCapsWord = Regex("""\b[A-ZĂÂÎȘȚ]{4,}\b""")

    fun prepareForReply(
        settings: CharacterSettings,
        state: ConversationState,
        latestUserMessage: ChatMessage,
        now: Long = System.currentTimeMillis(),
    ) {
        if (state.brain.lastProcessedUserMessageId == latestUserMessage.id) {
            refreshContext(settings, state, latestUserMessage.text, now)
            return
        }

        if (settings.moodEnabled && settings.automaticMoodEnabled) {
            applyTimeDecay(state.brain, now)
            applyUserTone(settings, state, SceneDirector.parse(latestUserMessage.text).dialogue, now)
        }

        if (settings.moodEnabled) {
            state.brain.interactionCount++
        }
        state.brain.lastProcessedUserMessageId = latestUserMessage.id
        state.brain.lastUpdatedAt = now
        state.brain.clampScores()
        recalculateMood(state.brain)
        refreshContext(settings, state, latestUserMessage.text, now)
    }

    fun finishReply(
        settings: CharacterSettings,
        state: ConversationState,
        userMessage: ChatMessage,
        assistantMessage: ChatMessage,
        now: Long = System.currentTimeMillis(),
    ) {
        if (state.brain.lastProcessedAssistantMessageId != assistantMessage.id) {
            if (settings.moodEnabled && settings.automaticMoodEnabled) {
                if (state.brain.interactionCount > 0 && state.brain.interactionCount % 8 == 0) {
                    state.brain.tiredness += 1
                    state.brain.lastMoodReason = "long conversation"
                }
            }
            state.brain.lastProcessedAssistantMessageId = assistantMessage.id
            state.brain.lastUpdatedAt = now
        }

        if (settings.memoryEnabled) {
            MemoryManager.refresh(state)
        }
        state.brain.clampScores()
        recalculateMood(state.brain)
        refreshContext(settings, state, userMessage.text, now)
        if (
            directQuestion.containsMatchIn(userMessage.text.trim()) &&
            !futureThread.containsMatchIn(userMessage.text)
        ) {
            state.brain.openThreads.removeAll { similar(it, userMessage.text) }
        }
    }

    fun onFeedback(
        settings: CharacterSettings,
        state: ConversationState,
        rating: Int,
    ) {
        if (!settings.moodEnabled || !settings.automaticMoodEnabled) return
        when {
            rating > 0 -> {
                state.brain.trust += 1
                state.brain.friendliness += 1
                state.brain.annoyance -= 1
                state.brain.lastMoodReason = "positive feedback"
            }
            rating < 0 -> {
                // Disliking a generated reply teaches style only. It does not make
                // the fictional character angry with or less trusting of the user.
                state.brain.lastMoodReason = "response correction"
            }
        }
        state.brain.clampScores()
        recalculateMood(state.brain)
    }

    fun resetMood(state: ConversationState) {
        val preservedContext = state.brain.copy(
            friendliness = 7,
            love = 5,
            trust = 5,
            annoyance = 0,
            tiredness = 2,
            playfulness = 5,
            moodLabel = "warm",
            interactionCount = 0,
            lastUpdatedAt = System.currentTimeMillis(),
            lastMoodReason = "reset",
            lastProcessedUserMessageId = "",
            lastProcessedAssistantMessageId = "",
        )
        state.brain = preservedContext
    }

    fun clearBrainMemory(state: ConversationState) {
        MemoryManager.clear(state)
        state.brain.rollingSummary = ""
        state.brain.currentTopic = ""
        state.brain.openThreads.clear()
        state.brain.recentTopics.clear()
    }

    /** Rebuilds mood and continuity after messages are deleted or a branch is redone. */
    fun rebuildFromHistory(settings: CharacterSettings, state: ConversationState) {
        val previous = state.brain
        state.brain = if (settings.moodEnabled && settings.automaticMoodEnabled) {
            BrainState()
        } else {
            BrainState(
                friendliness = previous.friendliness,
                love = previous.love,
                trust = previous.trust,
                annoyance = previous.annoyance,
                tiredness = previous.tiredness,
                playfulness = previous.playfulness,
                moodLabel = previous.moodLabel,
                lastMoodReason = "conversation edited",
            )
        }

        val history = state.messages.takeLast(MemoryManager.MAX_INDEX_MESSAGES)
        val firstTimestamp = history.firstOrNull()?.timestamp ?: System.currentTimeMillis()
        state.brain.lastUpdatedAt = firstTimestamp

        history.forEach { message ->
            when (message.role) {
                "user" -> {
                    if (settings.moodEnabled && settings.automaticMoodEnabled) {
                        applyTimeDecay(state.brain, message.timestamp)
                        applyUserTone(settings, state, SceneDirector.parse(message.text).dialogue, message.timestamp)
                    }
                    if (settings.moodEnabled) state.brain.interactionCount++
                    state.brain.lastProcessedUserMessageId = message.id
                    state.brain.lastUpdatedAt = message.timestamp
                    updateOpenThreads(state.brain, message.text)
                    val topics = MemoryManager.keywords(message.text).take(4).toList()
                    if (topics.isNotEmpty()) {
                        state.brain.currentTopic = topics.joinToString(", ")
                        val topicText = topics.joinToString(" ")
                        state.brain.recentTopics.removeAll { it.equals(topicText, ignoreCase = true) }
                        state.brain.recentTopics += topicText
                        while (state.brain.recentTopics.size > 12) state.brain.recentTopics.removeAt(0)
                    }
                }
                "assistant" -> {
                    if (settings.moodEnabled && settings.automaticMoodEnabled) {
                        if (state.brain.interactionCount > 0 && state.brain.interactionCount % 8 == 0) {
                            state.brain.tiredness += 1
                        }
                    }
                    state.brain.lastProcessedAssistantMessageId = message.id
                    state.brain.lastUpdatedAt = message.timestamp
                }
            }
        }

        if (settings.memoryEnabled) {
            MemoryManager.rebuild(state)
        } else {
            MemoryManager.clear(state)
        }
        state.brain.rollingSummary = if (settings.memoryEnabled) buildRollingSummary(state) else ""
        state.brain.clampScores()
        recalculateMood(state.brain)
    }

    fun recalculateMood(brain: BrainState) {
        brain.clampScores()
        brain.moodLabel = when {
            brain.annoyance >= 8 -> "angry"
            brain.annoyance >= 5 -> "annoyed"
            brain.tiredness >= 9 -> "exhausted"
            brain.tiredness >= 7 -> "tired"
            brain.friendliness <= 2 -> "distant"
            brain.trust <= 3 -> "guarded"
            brain.love >= 8 && brain.trust >= 7 && brain.friendliness >= 6 -> "loving"
            brain.playfulness >= 8 && brain.friendliness >= 6 && brain.annoyance <= 2 -> "playful"
            brain.friendliness >= 8 && brain.love >= 6 -> "affectionate"
            brain.friendliness >= 8 -> "friendly"
            else -> "calm"
        }
    }

    fun moodPrompt(settings: CharacterSettings, brain: BrainState): String {
        if (!settings.moodEnabled) return ""
        return buildString {
            append("The emotional state is a binding behavior contract, not a subtle suggestion. ")
            append("Scores (0–10): friendliness ${brain.friendliness}, love ${brain.love}, trust ${brain.trust}, ")
            append("annoyance ${brain.annoyance}, tiredness ${brain.tiredness}, playfulness ${brain.playfulness}; mood ${brain.moodLabel}. ")
            when {
                brain.annoyance >= 8 -> append("Be curt and clearly angry; no flirting, pet names, enthusiasm, or sexual tone. ")
                brain.annoyance >= 5 -> append("Be cooler, less patient, and brief; do not act affectionate or sexually eager. ")
                brain.tiredness >= 8 -> append("Use low-energy, short replies; no bubbly paragraph and no energetic dirty talk. ")
                brain.friendliness <= 2 -> append("Be distant and restrained; do not act friendly merely because the persona is friendly. ")
                brain.friendliness <= 4 -> append("Be reserved rather than warm. ")
                brain.love >= 8 && brain.trust >= 7 -> append("Affection may be strong only if the relationship stage is romantic. ")
                brain.friendliness >= 8 -> append("Be openly friendly, while still obeying relationship boundaries. ")
            }
            if (brain.love <= 3) append("Do not use romantic declarations or loving pet names. ")
            if (brain.trust <= 3) append("Be guarded and avoid vulnerable or intimate claims. ")
            if (brain.playfulness <= 3) append("Do not tease, wink, or act playfully. ")
            if (brain.playfulness >= 8 && brain.annoyance <= 2) append("Gentle verbal teasing is allowed if the relationship permits it. ")
            append("Mood and relationship rules override the generic persona. Never list these scores unless asked.")
        }
    }

    fun headerMood(settings: CharacterSettings, state: ConversationState): String =
        if (settings.moodEnabled && settings.showMoodInHeader) state.brain.moodLabel else ""

    fun refreshContext(
        settings: CharacterSettings,
        state: ConversationState,
        latestText: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val topics = MemoryManager.keywords(latestText)
            .take(8)
            .toList()
        if (topics.isNotEmpty()) {
            state.brain.currentTopic = topics.joinToString(", ")
            val topicText = topics.joinToString(" ")
            state.brain.recentTopics.removeAll { it.equals(topicText, ignoreCase = true) }
            state.brain.recentTopics += topicText
            while (state.brain.recentTopics.size > 30) {
                state.brain.recentTopics.removeAt(0)
            }
        }

        updateOpenThreads(state.brain, latestText)
        if (settings.memoryEnabled) {
            MemoryManager.refresh(state)
            state.brain.rollingSummary = buildRollingSummary(state)
        } else {
            state.brain.rollingSummary = ""
        }
    }

    private fun applyUserTone(settings: CharacterSettings, state: ConversationState, text: String, now: Long) {
        val brain = state.brain
        val relationshipLevel = RelationshipPolicy.resolveLevel(settings)
        val affection = directedAffection.containsMatchIn(text)
        val positive = warmPositive.containsMatchIn(text)
        val negative = hostility.containsMatchIn(text)
        val apologetic = apology.containsMatchIn(text)
        val playfulTone = playful.containsMatchIn(text)
        val upperIntensity = allCapsWord.findAll(text).count() >= 2

        when {
            negative -> {
                brain.annoyance += if (upperIntensity) 3 else 2
                brain.friendliness -= 1
                brain.trust -= 1
                brain.playfulness -= 1
                brain.lastMoodReason = "hostile message"
            }
            apologetic -> {
                brain.annoyance -= 2
                brain.trust += 1
                brain.friendliness += 1
                brain.lastMoodReason = "apology"
            }
            affection -> {
                if (RelationshipPolicy.isRomantic(relationshipLevel)) {
                    brain.love += 2
                    brain.trust += 1
                } else {
                    brain.trust += 1
                }
                brain.friendliness += 1
                brain.annoyance -= 1
                brain.lastMoodReason = "affection"
            }
            positive -> {
                brain.friendliness += 1
                brain.trust += 1
                brain.annoyance -= 1
                brain.lastMoodReason = "warm message"
            }
        }

        if (playfulTone) {
            brain.playfulness += 1
            brain.friendliness += 1
            brain.lastMoodReason = "playful message"
        }
        if (RelationshipPolicy.isAdultText(text) && RelationshipPolicy.isRomantic(relationshipLevel)) {
            brain.playfulness += 1
            if (brain.trust >= 5) brain.love += 1
            brain.lastMoodReason = "intimate conversation"
        }
        if (morning.containsMatchIn(text)) {
            brain.tiredness -= 2
            brain.lastMoodReason = "morning"
        }
        if (night.containsMatchIn(text)) {
            brain.tiredness += 1
            brain.lastMoodReason = "late conversation"
        }

        val hour = Calendar.getInstance().apply { timeInMillis = now }
            .get(Calendar.HOUR_OF_DAY)
        when (hour) {
            in 0..4 -> brain.tiredness = maxOf(brain.tiredness, 6)
            5, 6 -> brain.tiredness = maxOf(brain.tiredness, 4)
            22, 23 -> brain.tiredness = maxOf(brain.tiredness, 4)
        }

        if (text.length >= 700) {
            brain.tiredness += 1
        }
        brain.clampScores()
    }

    private fun applyTimeDecay(brain: BrainState, now: Long) {
        val elapsed = (now - brain.lastUpdatedAt).coerceAtLeast(0L)
        val hours = elapsed / 3_600_000L
        when {
            hours >= 24 -> {
                brain.annoyance = 0
                brain.tiredness = 2
                brain.playfulness = (brain.playfulness + 1).coerceAtMost(10)
            }
            hours >= 8 -> {
                brain.annoyance -= 3
                brain.tiredness -= 3
            }
            hours >= 3 -> {
                brain.annoyance -= 1
                brain.tiredness -= 1
            }
        }
        brain.clampScores()
    }

    private fun updateOpenThreads(brain: BrainState, text: String) {
        val normalized = text.replace(Regex("""\s+"""), " ").trim().take(260)
        if (resolveThread.containsMatchIn(normalized)) {
            if (brain.openThreads.isNotEmpty()) {
                brain.openThreads.removeAt(brain.openThreads.lastIndex)
            }
            return
        }

        if (futureThread.containsMatchIn(normalized)) {
            brain.openThreads.removeAll { similar(it, normalized) }
            brain.openThreads += normalized
        } else if (directQuestion.containsMatchIn(normalized) && normalized.length >= 20) {
            brain.openThreads.removeAll { similar(it, normalized) }
            brain.openThreads += normalized
        }
        while (brain.openThreads.size > 30) {
            brain.openThreads.removeAt(0)
        }
    }

    private fun buildRollingSummary(state: ConversationState): String {
        val importantMemories = state.memories
            .sortedWith(
                compareByDescending<MemoryItem> { it.importance }
                    .thenByDescending { it.createdAt },
            )
            .distinctBy { it.slot + "|" + it.text.lowercase() }
            .take(20)
            .map { it.text }

        val recentEpisodes = state.episodes
            .takeLast(12)
            .map {
                "User said “${it.userText.take(160)}”; character replied “${it.assistantText.take(160)}”."
            }

        return buildString {
            if (importantMemories.isNotEmpty()) {
                append("Known facts and continuity: ")
                append(importantMemories.joinToString(" | "))
            }
            if (recentEpisodes.isNotEmpty()) {
                if (isNotEmpty()) append(" ")
                append("Recent exchange recap: ")
                append(recentEpisodes.joinToString(" "))
            }
            if (state.brain.openThreads.isNotEmpty()) {
                if (isNotEmpty()) append(" ")
                append("Ongoing plans or unresolved points: ")
                append(state.brain.openThreads.takeLast(8).joinToString(" | "))
            }
        }.replace(Regex("""\s+"""), " ").trim().take(2400)
    }

    private fun similar(a: String, b: String): Boolean {
        val aTokens = MemoryManager.keywords(a)
        val bTokens = MemoryManager.keywords(b)
        if (aTokens.isEmpty() || bTokens.isEmpty()) return a.equals(b, ignoreCase = true)
        val overlap = aTokens.intersect(bTokens).size
        val denominator = minOf(aTokens.size, bTokens.size).coerceAtLeast(1)
        return overlap.toDouble() / denominator.toDouble() >= 0.65
    }
}
