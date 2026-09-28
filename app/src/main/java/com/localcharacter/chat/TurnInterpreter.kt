package com.localcharacter.chat

/** Intent hints, not a replacement for the LLM's semantic reasoning. Raw user text stays unchanged. */
data class TurnIntent(val mode: PromptMode, val task: String, val query: String)

object TurnInterpreter {
    private val greeting = Regex("""^(hi+|hey+|hello+|salut|buna|hei|ciao|good morning|good evening|neata)(\s+(there|again|elena))?$""")
    private val thanks = Regex("""^(thanks|thank you|thx|ty|mersi|multumesc)(\s+(so much|a lot|mult|again))?$""")
    private val confirmation = Regex("""^(ok|okay|yes|yeah|yep|sure|no|nope|da|nu|bine|do it|go on|continue|continua)$""")
    private val followup = Regex("""\b(why not|why|how so|what do you mean|what about|that one|the other|last time|earlier|before|remember|which one|de ce|cum asa|care|mai devreme|iti amintesti)\b""")
    private val correction = Regex("""\b(i meant|i mean|no i|not that|actually|correction|rather than|voiam sa spun|ma refeream|nu asta)\b""")
    private val references = Regex("""\b(it|that|this|them|her|him|there|those|astea|asta|aia|acolo)\b""")

    fun resolve(raw: String, settings: CharacterSettings, state: ConversationState): TurnIntent {
        val parsed = if (settings.sceneDirectionsEnabled) SceneDirector.parse(raw) else SceneInput(emptyList(), raw, false)
        val spoken = parsed.dialogue
        val plain = SceneDirector.fold(spoken).replace(Regex("[^\\p{L}\\p{N}' ]+"), " ")
            .replace(Regex("\\s+"), " ").trim()
        val query = spoken.ifBlank { parsed.directions.joinToString(" ") }
        return when {
            spoken.isBlank() && parsed.directions.isNotEmpty() -> TurnIntent(PromptMode.SCENE,
                "Apply the director's setup or requested next reply now. Begin/continue from the scene; do not say 'understood', quote the instructions, or ask the user to repeat them.", query)
            greeting.matches(plain) -> TurnIntent(PromptMode.GREETING,
                "Respond to this greeting briefly and in character. It is not a request for a scene recap or an introduction.", query)
            thanks.matches(plain) -> TurnIntent(PromptMode.SHORT,
                "Acknowledge the thanks briefly. Do not repeat the explanation being thanked for.", query)
            confirmation.matches(plain) -> TurnIntent(PromptMode.REFERENCE,
                "This short response answers the immediately preceding question or proposal. Accept/reject/continue THAT action; do not invent a different question. If it was only an acknowledgement, acknowledge briefly.", query)
            correction.containsMatchIn(plain) -> TurnIntent(PromptMode.REFERENCE,
                "The user is correcting a misunderstanding. Use the corrected meaning and discard the mistaken interpretation. Do not repeat the old claim as current fact.", query)
            followup.containsMatchIn(plain) || (plain.length < 140 && references.containsMatchIn(plain)) -> TurnIntent(PromptMode.REFERENCE,
                "Resolve the follow-up against the immediately preceding exchange and the matching earlier topic. Explain the specific referenced point, not an unrelated memory. If two referents remain plausible, ask one short clarifying question.", query)
            plain.length > 300 || Regex("\\b(in detail|explain fully|step by step|detaliat)\\b").containsMatchIn(plain) -> TurnIntent(PromptMode.DEEP,
                "Answer all parts of the newest request with useful detail. Keep the user's established facts and the active scene consistent.", query)
            SceneDirector.responseMode(settings, state) != "texting" -> TurnIntent(PromptMode.SCENE,
                "React to the latest action/dialogue and advance one coherent beat. Leave the user's choices and reactions for the user. Do not recap the scene or restart it.", query)
            else -> TurnIntent(PromptMode.NORMAL,
                "Answer the actual question or react to the newest message directly. Use remembered information only when it is relevant. Do not substitute a generic acknowledgement.", query)
        }
    }
}
