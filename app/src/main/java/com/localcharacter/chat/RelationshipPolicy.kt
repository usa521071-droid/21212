package com.localcharacter.chat


enum class RelationshipLevel(val key: String) {
    STRANGER("stranger"),
    ACQUAINTANCE("acquaintance"),
    COWORKER("coworker"),
    FRIEND("friend"),
    CLOSE_FRIEND("close_friend"),
    DATING("dating"),
    PARTNER("partner"),
    MARRIED("married"),
    CASUAL("casual"),
}

data class RelationshipProfile(
    val level: RelationshipLevel,
    val label: String,
    val tone: String,
    val romanticAllowed: Boolean,
    val petNamesAllowed: Boolean,
    val explicitAdultRequest: Boolean,
    val adultContextActive: Boolean,
    val canContinueAdult: Boolean,
    val canInitiateAdult: Boolean,
) {
    val intimateToneAllowed: Boolean
        get() = canContinueAdult || canInitiateAdult
}

object RelationshipPolicy {
    private val adultIntent = Regex(
        """(?iu)\b(?:dirty\s*talk|sex(?:y|ual)?|naked|nude|undress|strip|horny|turn(?:ed)?\s+on|make\s+love|sleep\s+with|fuck(?:ing|ed)?|kiss\s+me|touch\s+me|boobs?|breasts?|nipples?|cock|dick|pussy|cum|moan|bedroom|vorbe\s+murdare|sex|goal[ăa]|dezbrac[ăa]|excitat[ăa]?|s[âa]ni|pula|pizd[ăa]|fute|geme)\b""",
    )
    private val married = Regex("""(?iu)\b(?:married|wife|husband|spouse|soț|soție|căsătoriți|căsătorit|căsătorită)\b""")
    private val casual = Regex("""(?iu)\b(?:fwb|friends?\s+with\s+benefits|casual|sex\s+partner|lover|amant|amantă|relație\s+casual)\b""")
    private val partner = Regex("""(?iu)\b(?:girlfriend|boyfriend|partner|couple|fianc[eé]e?|romantic\s+partner|iubită|iubit|partener[ăa]?|cuplu|logodnic[ăa]?)\b""")
    private val dating = Regex("""(?iu)\b(?:dating|seeing\s+each\s+other|new\s+relationship|crush|date|ne\s+vedem|ieșim\s+împreună|relație\s+nouă)\b""")
    private val closeFriend = Regex("""(?iu)\b(?:close\s+friend|best\s+friend|very\s+close\s+friend|prieten[ăa]\s+apropiat[ăa]?|cel\s+mai\s+bun\s+prieten|cea\s+mai\s+bună\s+prietenă)\b""")
    private val friend = Regex("""(?iu)\b(?:friend|friends|prieten|prietenă|prieteni)\b""")
    private val coworker = Regex("""(?iu)\b(?:coworker|co-worker|colleague|boss|employee|coleg|colegă|șef|șefă|angajat[ăa]?)\b""")
    private val acquaintance = Regex("""(?iu)\b(?:acquaintance|know\s+each\s+other|cunoștință|ne\s+cunoaștem)\b""")
    private val stranger = Regex("""(?iu)\b(?:stranger|just\s+met|first\s+time|new\s+person|necunoscut[ăa]?|abia\s+ne-am\s+cunoscut|prima\s+dată)\b""")

    fun resolve(
        settings: CharacterSettings,
        brain: BrainState,
        conversation: ConversationState,
        latest: String,
        mode: PromptMode,
        now: Long = System.currentTimeMillis(),
    ): RelationshipProfile {
        val level = if (conversation.sceneState.active && conversation.sceneState.relationshipOverride.isNotBlank()) {
            RelationshipLevel.entries.firstOrNull { it.key == conversation.sceneState.relationshipOverride }
                ?: resolveLevel(settings)
        } else {
            resolveLevel(settings)
        }
        val romantic = level in setOf(
            RelationshipLevel.DATING,
            RelationshipLevel.PARTNER,
            RelationshipLevel.MARRIED,
            RelationshipLevel.CASUAL,
        )
        val explicit = isAdultText(latest)
        val recentAdult = conversation.messages
            .asReversed()
            .take(8)
            .takeWhile { now - it.timestamp <= 45L * 60L * 1000L }
            .any { isAdultText(it.text) }
        val activeAdult = explicit || recentAdult

        val effectiveFriendliness = if (settings.moodEnabled) brain.friendliness else 6
        val effectiveLove = if (settings.moodEnabled) brain.love else if (romantic) 7 else 2
        val effectiveTrust = if (settings.moodEnabled) brain.trust else if (romantic) 7 else 5
        val effectiveAnnoyance = if (settings.moodEnabled) brain.annoyance else 0
        val effectiveTiredness = if (settings.moodEnabled) brain.tiredness else 2
        val effectivePlayfulness = if (settings.moodEnabled) brain.playfulness else 5

        val petNames = romantic &&
            effectiveLove >= 7 && effectiveTrust >= 5 && effectiveFriendliness >= 6 &&
            effectiveAnnoyance <= 3 && effectiveTiredness <= 7

        val stageAllowsAdult = level in setOf(
            RelationshipLevel.DATING,
            RelationshipLevel.PARTNER,
            RelationshipLevel.MARRIED,
            RelationshipLevel.CASUAL,
        )
        val moodAllowsAdult = effectiveTrust >= 5 &&
            (effectiveLove >= 5 || level == RelationshipLevel.CASUAL) &&
            effectiveAnnoyance <= 5 && effectiveTiredness <= 8
        val canContinue = settings.adultLanguage && stageAllowsAdult && activeAdult && moodAllowsAdult &&
            mode != PromptMode.GREETING
        val canInitiate = settings.adultLanguage && activeAdult &&
            level in setOf(RelationshipLevel.PARTNER, RelationshipLevel.MARRIED, RelationshipLevel.CASUAL) &&
            effectiveTrust >= 7 &&
            (effectiveLove >= 8 || (level == RelationshipLevel.CASUAL && effectivePlayfulness >= 8)) &&
            effectiveAnnoyance <= 2 && effectiveTiredness <= 6 && mode !in setOf(PromptMode.GREETING, PromptMode.SHORT)

        val rawWarmth = effectiveFriendliness + effectiveLove / 3 - effectiveAnnoyance - effectiveTiredness / 3
        val stageCap = when (level) {
            RelationshipLevel.STRANGER -> 2
            RelationshipLevel.ACQUAINTANCE, RelationshipLevel.COWORKER -> 4
            RelationshipLevel.FRIEND -> 6
            RelationshipLevel.CLOSE_FRIEND -> 7
            else -> 10
        }
        val warmth = minOf(rawWarmth, stageCap)
        val tone = when {
            effectiveAnnoyance >= 8 -> "angry and terse"
            effectiveAnnoyance >= 5 -> "cool and annoyed"
            effectiveTiredness >= 8 -> "very tired and brief"
            warmth <= 1 -> "distant"
            warmth <= 3 -> "reserved"
            warmth <= 5 -> "neutral"
            warmth <= 7 -> "friendly"
            petNames && effectiveLove >= 9 -> "loving"
            petNames -> "affectionate"
            else -> "warm"
        }

        return RelationshipProfile(
            level = level,
            label = label(level),
            tone = tone,
            romanticAllowed = romantic,
            petNamesAllowed = petNames,
            explicitAdultRequest = explicit,
            adultContextActive = activeAdult,
            canContinueAdult = canContinue,
            canInitiateAdult = canInitiate,
        )
    }

    fun prompt(
        settings: CharacterSettings,
        profile: RelationshipProfile,
        brain: BrainState,
        mode: PromptMode,
    ): String = buildString {
        append("Relationship stage: ${profile.label}. This relationship policy and the current mood override generic personality wording. ")
        append("Required tone now: ${profile.tone}. ")
        when (profile.level) {
            RelationshipLevel.STRANGER -> append("Be polite but distant. No pet names, romantic declarations, possessiveness, or sexual familiarity. ")
            RelationshipLevel.ACQUAINTANCE -> append("Be polite and lightly conversational, not intimate. No pet names or sexual familiarity. ")
            RelationshipLevel.COWORKER -> append("Keep the tone appropriate for coworkers unless the user explicitly changed the relationship. Do not assume romance. ")
            RelationshipLevel.FRIEND -> append("Be friendly and platonic. Do not act like a romantic partner. ")
            RelationshipLevel.CLOSE_FRIEND -> append("Be warm and familiar but remain platonic unless the relationship details explicitly say otherwise. ")
            RelationshipLevel.DATING -> append("Romantic warmth may develop, but do not act married or intensely possessive. ")
            RelationshipLevel.PARTNER -> append("Act like a committed partner, but the mood scores still control current warmth and patience. ")
            RelationshipLevel.MARRIED -> append("Act like a spouse, while respecting the current mood and conversation. ")
            RelationshipLevel.CASUAL -> append("Keep affection and commitment claims limited; intimacy does not automatically mean love. ")
        }
        if (profile.petNamesAllowed) {
            append("A natural pet name is allowed occasionally, not in every reply. ")
        } else {
            append("Do not use romantic pet names or declarations of love in this reply. ")
        }
        when {
            !settings.adultLanguage -> append("Do not use sexual or explicit language. ")
            mode == PromptMode.GREETING -> append("A greeting must never become sexual, mention intimate anatomy, or start dirty talk. ")
            profile.canContinueAdult -> append("Adult language may continue only because the user explicitly initiated it or the immediate conversation is already adult. Keep it consistent with the relationship and mood. ")
            else -> append("Do not initiate or continue dirty talk, sexual body references, or explicit intimacy. Adult-language permission alone is not consent to make neutral messages sexual. ")
        }
        append("Never turn a neutral message into flirting or dirty talk. Never mention breasts, genitals, arousal, or sexual actions unless the newest message or active scene clearly calls for it and the policy above allows it. ")
        if (settings.moodEnabled) {
            append("Mood is binding: friendliness ${brain.friendliness}/10, love ${brain.love}/10, trust ${brain.trust}/10, annoyance ${brain.annoyance}/10, tiredness ${brain.tiredness}/10, playfulness ${brain.playfulness}/10. ")
        }
    }.trim()

    fun resolveLevel(settings: CharacterSettings): RelationshipLevel {
        val explicit = settings.relationshipStage.lowercase().trim()
        if (explicit != "auto") {
            return RelationshipLevel.entries.firstOrNull { it.key == explicit } ?: RelationshipLevel.ACQUAINTANCE
        }
        val text = settings.relationship
        return when {
            married.containsMatchIn(text) -> RelationshipLevel.MARRIED
            casual.containsMatchIn(text) -> RelationshipLevel.CASUAL
            partner.containsMatchIn(text) -> RelationshipLevel.PARTNER
            dating.containsMatchIn(text) -> RelationshipLevel.DATING
            closeFriend.containsMatchIn(text) -> RelationshipLevel.CLOSE_FRIEND
            coworker.containsMatchIn(text) -> RelationshipLevel.COWORKER
            friend.containsMatchIn(text) -> RelationshipLevel.FRIEND
            stranger.containsMatchIn(text) -> RelationshipLevel.STRANGER
            acquaintance.containsMatchIn(text) -> RelationshipLevel.ACQUAINTANCE
            text.isBlank() -> RelationshipLevel.ACQUAINTANCE
            else -> RelationshipLevel.ACQUAINTANCE
        }
    }

    fun isRomantic(level: RelationshipLevel): Boolean = level in setOf(
        RelationshipLevel.DATING,
        RelationshipLevel.PARTNER,
        RelationshipLevel.MARRIED,
        RelationshipLevel.CASUAL,
    )

    fun isAdultText(text: String): Boolean = adultIntent.containsMatchIn(text)

    fun label(level: RelationshipLevel): String = when (level) {
        RelationshipLevel.STRANGER -> "strangers"
        RelationshipLevel.ACQUAINTANCE -> "acquaintances"
        RelationshipLevel.COWORKER -> "coworkers"
        RelationshipLevel.FRIEND -> "friends"
        RelationshipLevel.CLOSE_FRIEND -> "close friends"
        RelationshipLevel.DATING -> "dating"
        RelationshipLevel.PARTNER -> "committed partners"
        RelationshipLevel.MARRIED -> "married"
        RelationshipLevel.CASUAL -> "casual / friends with benefits"
    }
}
