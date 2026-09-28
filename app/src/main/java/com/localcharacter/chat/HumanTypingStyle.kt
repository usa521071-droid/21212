package com.localcharacter.chat

/** Semantic style is prompted, not fabricated by corrupting the generated text. */
object HumanTypingStyle {
    val values = listOf("clean", "natural", "casual", "minimal", "warm", "dry", "playful", "formal", "expressive")
    val labels = listOf("Clean", "Natural", "Casual", "Minimal", "Warm", "Dry humor", "Playful", "Formal", "Expressive")
    fun prompt(style: String) = when (style) {
        "clean" -> "Writing: clear everyday spelling and punctuation, not corporate prose."
        "casual" -> "Writing: relaxed phone messages, occasional lower-case or missing final period; keep names, facts and meaning intact. No forced misspellings."
        "minimal" -> "Writing: brief, direct, few words when enough; still answer every substantive question."
        "warm" -> "Writing: thoughtful and kind, never overfamiliar beyond the relationship or current mood."
        "dry" -> "Writing: understated, occasional dry humor; not insults or nonstop jokes."
        "playful" -> "Writing: light playful phrasing only when mood and relationship allow; a serious question deserves a serious answer."
        "formal" -> "Writing: composed, polite, complete sentences; avoid filler and canned salutations."
        "expressive" -> "Writing: expressive and conversational; vary rhythm, no excessive capitals or punctuation. Respect emoji settings."
        else -> "Writing: natural mobile conversation, relaxed punctuation, no forced typos or overwritten paragraphs."
    }
    fun apply(text: String, settings: CharacterSettings, interactionCount: Int, sceneMode: Boolean): String {
        if (text.isBlank() || settings.typingRealism !in setOf("natural", "casual")) return text
        // Do not alter links, numbers, proper names, actions, negation or quotations.
        if (sceneMode || text.contains("http") || text.any(Char::isDigit) || text.contains('"')) return text
        val seed = (text.hashCode().toLong() + interactionCount).let { kotlin.math.abs(it) }
        return if (text.length < 160 && text.endsWith('.') && !text.endsWith("...") && seed % 3L == 0L) text.dropLast(1) else text
    }
}
