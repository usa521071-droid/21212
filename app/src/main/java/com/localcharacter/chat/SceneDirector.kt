package com.localcharacter.chat

import java.text.Normalizer

/** Asterisks are a control channel. Their content is never attributed to spoken dialogue. */
data class SceneInput(
    val directions: List<String>,
    val dialogue: String,
    val clearRequested: Boolean,
    val warnings: List<String> = emptyList(),
)

object SceneDirector {
    // Kept for legacy callers. parse() handles escaped stars and does not interpret **bold**.
    val DIRECTION_REGEX = Regex("""(?s)(?<![\\*])\*([^*]{1,4000})\*(?!\*)""")
    private val clear = Regex("""(?i)^(?:clear|reset|end|stop)\s+(?:the\s+)?scene$|^(?:normal chat|back to texting)$""")
    private val aliases = mapOf(
        "scene" to "premise", "setting" to "premise", "setup" to "premise", "premise" to "premise",
        "character" to "character", "role" to "character", "char" to "character",
        "location" to "location", "place" to "location", "time" to "time",
        "traits" to "traits", "personality" to "traits", "goal" to "goal", "intent" to "goal",
        "relationship" to "relationship", "relation" to "relationship", "mood" to "mood",
        "style" to "style", "mode" to "style", "format" to "style", "perspective" to "perspective",
        "rules" to "rules", "boundary" to "boundary", "boundaries" to "boundary",
        "outfit" to "outfit", "activity" to "activity", "reply" to "next", "respond" to "next",
        "cast" to "cast", "participants" to "cast", "user role" to "userRole", "my role" to "userRole", "channel" to "channel",
        "next reply" to "next", "say" to "next", "length" to "length",
    )
    private val moodAliases = mapOf(
        "friendly" to "friendliness", "friendliness" to "friendliness", "warmth" to "friendliness",
        "love" to "love", "affection" to "love", "trust" to "trust",
        "annoyed" to "annoyance", "annoyance" to "annoyance", "anger" to "annoyance",
        "tired" to "tiredness", "tiredness" to "tiredness", "playful" to "playfulness", "playfulness" to "playfulness",
    )

    fun parse(text: String): SceneInput {
        val directions = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val spoken = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (text[i] == '\\' && i + 1 < text.length && text[i + 1] == '*') {
                spoken.append('*'); i += 2; continue
            }
            if (text[i] != '*' || (i + 1 < text.length && text[i + 1] == '*') ||
                (i > 0 && !text[i - 1].isWhitespace() && text[i - 1] != '*')) {
                if (text[i] == '*' && i + 1 < text.length && text[i + 1] == '*') {
                    // Leave Markdown emphasis literal, rather than turning it into a director command.
                    val end = text.indexOf("**", i + 2)
                    if (end >= 0) { spoken.append(text.substring(i, end + 2)); i = end + 2; continue }
                }
                spoken.append(text[i++]); continue
            }
            var end = i + 1
            while (end < text.length && (text[end] != '*' || text[end - 1] == '\\')) end++
            if (end >= text.length) {
                warnings += "Close the scene direction with a second * before sending."
                spoken.append(text.substring(i)); break
            }
            val instruction = text.substring(i + 1, end).replace("\\*", "*").trim()
            when {
                instruction.isBlank() -> spoken.append(text.substring(i, end + 1))
                instruction.length > 4000 -> warnings += "One direction is limited to 4,000 characters. Split the setup into separate directions."
                else -> { directions += instruction; spoken.append(' ') }
            }
            i = end + 1
        }
        return SceneInput(directions, spoken.toString().trim(), directions.any { clear.matches(fold(it)) }, warnings)
    }

    /** Apply once per source message. Repeating generation must not replay mood/setup events. */
    fun applyDirectives(
        settings: CharacterSettings,
        state: ConversationState,
        rawMessage: String,
        now: Long = System.currentTimeMillis(),
        sourceMessageId: String = state.messages.lastOrNull { it.role == "user" && it.text == rawMessage }?.id
            ?: "direct:${rawMessage.hashCode()}",
    ): SceneInput {
        val parsed = parse(rawMessage)
        if (!settings.sceneDirectionsEnabled || parsed.directions.isEmpty() || parsed.warnings.isNotEmpty()) return parsed
        if (sourceMessageId in state.sceneState.director.appliedMessageIds) return parsed
        parsed.directions.forEach { raw ->
            val command = fold(raw)
            if (clear.matches(command)) {
                state.sceneState = SceneState(director = DirectorState())
            } else if (command == "clear reply") {
                state.sceneState.director.nextReply = ""
                state.sceneState.director.nextReplyFor = ""
            } else {
                val keyAndValue = split(raw)
                val scene = state.sceneState
                val d = scene.director
                when (keyAndValue.first) {
                    "next" -> {
                        d.nextReply = keyAndValue.second.take(1400)
                        d.nextReplyFor = sourceMessageId
                    }
                    "mood" -> {
                        val value = keyAndValue.second
                        if (fold(value) == "clear") d.mood.clear()
                        else Regex("""(?i)([\p{L}]+)\s*[:=]?\s*(-?\d{1,2})(?:\s*/\s*10)?""")
                            .findAll(value).forEach { m ->
                                moodAliases[fold(m.groupValues[1])]?.let { key ->
                                    d.mood[key] = m.groupValues[2].toInt().coerceIn(0, 10)
                                }
                            }
                    }
                    "style" -> {
                        val v = fold(keyAndValue.second)
                        d.fields["style"] = when {
                            v in setOf("text", "texting", "messages", "chat", "text only") -> "texting"
                            v in setOf("roleplay", "rp", "actions", "action + dialogue", "mixed") -> "roleplay"
                            v in setOf("narrative", "story", "narration") -> "narrative"
                            else -> keyAndValue.second.take(200)
                        }
                        scene.active = true
                    }
                    "free" -> {
                        scene.directives.removeAll { it.equals(raw, ignoreCase = true) }
                        scene.directives += raw.take(1400)
                        while (scene.directives.size > 8) scene.directives.removeAt(0)
                        val described = Regex("""(?i)\b(?:(?:she|he|you)(?: is| are|'s) (?:a |an |my |your )?|we(?: are|'re) )(friends?|close friends?|coworkers?|girlfriend|boyfriend|married|strangers?|dating)\b""").find(fold(raw))
                        if (described != null && "relationship" !in d.fields) {
                            d.fields["relationship"] = described.groupValues[1]
                            scene.relationshipOverride = relationshipKey(described.groupValues[1])
                        }
                        scene.active = true
                    }
                    else -> {
                        // Last explicit value wins: location/goal/etc cannot accumulate contradictions.
                        d.fields[keyAndValue.first] = keyAndValue.second.take(2000)
                        scene.active = true
                    }
                }
                if (d.fields.containsKey("relationship")) {
                    scene.relationshipOverride = relationshipKey(d.fields["relationship"].orEmpty())
                }
                scene.characterGoal = d.fields["goal"].orEmpty()
                scene.premise = listOf(d.fields["premise"].orEmpty(), scene.directives.joinToString("; "))
                    .filter { it.isNotBlank() }.joinToString("; ").take(4000)
                scene.updatedAt = now
            }
        }
        state.sceneState.director.appliedMessageIds += sourceMessageId
        while (state.sceneState.director.appliedMessageIds.size > 600) state.sceneState.director.appliedMessageIds.removeAt(0)
        return parsed
    }

    fun shouldGenerate(settings: CharacterSettings, parsed: SceneInput): Boolean {
        if (!settings.sceneDirectionsEnabled) return true
        if (parsed.dialogue.isNotBlank()) return true
        if (parsed.directions.isEmpty()) return false
        if (parsed.directions.any { split(it).first == "next" }) return true
        val sceneSetup = parsed.directions.any { split(it).first in setOf("free", "premise", "character", "location", "goal", "activity") && !clear.matches(fold(it)) }
        return sceneSetup && settings.roleplay.autoReplyToScene
    }

    /** Active scene does not automatically mean physical narration. This is a separate user choice. */
    fun responseMode(settings: CharacterSettings, state: ConversationState): String =
        state.sceneState.director.fields["style"]?.takeIf { settings.sceneDirectionsEnabled && it in setOf("texting", "roleplay", "narrative") }
            ?: settings.roleplay.mode.let { if (it == "auto") { if (state.sceneState.active && settings.sceneDirectionsEnabled) "roleplay" else "texting" } else it }

    fun effectiveBrain(settings: CharacterSettings, state: ConversationState): BrainState {
        val brain = state.brain.copy()
        if (!settings.moodEnabled || !settings.sceneDirectionsEnabled) return brain
        state.sceneState.director.mood.forEach { (key, value) ->
            when (key) {
                "friendliness" -> brain.friendliness = value
                "love" -> brain.love = value
                "trust" -> brain.trust = value
                "annoyance" -> brain.annoyance = value
                "tiredness" -> brain.tiredness = value
                "playfulness" -> brain.playfulness = value
            }
        }
        brain.clampScores()
        return brain
    }

    fun nextReply(state: ConversationState, messageId: String): String =
        state.sceneState.director.nextReply.takeIf { state.sceneState.director.nextReplyFor == messageId }.orEmpty()

    fun prompt(state: SceneState): String = buildString {
        if (state.active) {
            appendLine("SCENE FACTS (fictional, not spoken; later explicit values override earlier ones):")
            if (state.premise.isNotBlank()) appendLine(state.premise.take(2400))
            state.director.fields.filterKeys { it !in setOf("premise", "style", "length", "perspective") }
                .forEach { (k, v) -> appendLine("$k: $v") }
            append("Continue from these facts. Do not echo the setup or turn it into the user's speech.")
        }
    }.trim()

    fun rebuildFromHistory(settings: CharacterSettings, state: ConversationState) {
        state.sceneState = SceneState()
        state.messages.filter { it.role == "user" && it.text.contains('*') }.forEach { m ->
            applyDirectives(settings, state, m.text, m.timestamp, m.id)
        }
    }

    fun isSceneOnly(text: String): Boolean = parse(text).let { it.directions.isNotEmpty() && it.dialogue.isBlank() }
    fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace('’', '\'').lowercase().trim()
    private fun split(raw: String): Pair<String, String> {
        val m = Regex("""^([\p{L} ]{2,24})\s*[:=]\s*([\s\S]+)$""").find(raw.trim())
        val key = m?.groupValues?.get(1)?.let(::fold)?.let(aliases::get)
        if (key != null) return key to m.groupValues[2].trim()
        val nextReply = Regex("""(?i)^(?:for (?:the )?next reply|next reply|reply|respond|answer|say|(?:she|he|the character|you) (?:should |must |will )?(?:replies|responds|answers|says|reply|respond|answer|say))\b""")
        return if (nextReply.containsMatchIn(fold(raw))) "next" to raw.trim() else "free" to raw.trim()
    }
    private fun relationshipKey(value: String): String = when (fold(value).replace(' ', '_')) {
        "strangers", "stranger" -> "stranger"
        "coworker", "coworkers", "colleague" -> "coworker"
        "friends", "friend" -> "friend"
        "close_friends", "close_friend", "best_friend" -> "close_friend"
        "dating" -> "dating"
        "partner", "committed", "girlfriend", "boyfriend" -> "partner"
        "married", "wife", "husband" -> "married"
        "casual", "friends_with_benefits" -> "casual"
        else -> "acquaintance"
    }
}
