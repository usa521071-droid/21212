package com.localcharacter.chat

import org.json.JSONArray
import org.json.JSONObject

/** User-authored character instructions, not inferred personality or model weights. */
data class RoleplaySettings(
    var mode: String = "auto", // texting, roleplay, narrative
    var perspective: String = "first", // first, third
    var background: String = "",
    var goals: String = "",
    var voiceExamples: String = "",
    var replyRules: String = "",
    var boundaries: String = "",
    var traits: String = "",
    var warmth: Int = 5,
    var humor: Int = 4,
    var directness: Int = 5,
    var shyness: Int = 3,
    var curiosity: Int = 5,
    var initiative: Int = 4,
    var autoReplyToScene: Boolean = true,
    var repairInvalidReply: Boolean = true,
    var showDirectorCards: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("mode", mode).put("perspective", perspective)
        .put("background", background).put("goals", goals).put("voiceExamples", voiceExamples)
        .put("replyRules", replyRules).put("boundaries", boundaries).put("traits", traits)
        .put("warmth", warmth).put("humor", humor).put("directness", directness)
        .put("shyness", shyness).put("curiosity", curiosity).put("initiative", initiative)
        .put("autoReplyToScene", autoReplyToScene).put("repairInvalidReply", repairInvalidReply)
        .put("showDirectorCards", showDirectorCards)

    companion object {
        fun fromJson(json: JSONObject): RoleplaySettings = RoleplaySettings(
            mode = json.optString("mode", "auto").takeIf { it in setOf("auto", "texting", "roleplay", "narrative") } ?: "auto",
            perspective = if (json.optString("perspective") == "third") "third" else "first",
            background = json.optString("background").take(2400),
            goals = json.optString("goals").take(1000),
            voiceExamples = json.optString("voiceExamples").take(1600),
            replyRules = json.optString("replyRules").take(1000),
            boundaries = json.optString("boundaries").take(1000),
            traits = json.optString("traits").take(1200),
            warmth = json.optInt("warmth", 5).coerceIn(0, 10),
            humor = json.optInt("humor", 4).coerceIn(0, 10),
            directness = json.optInt("directness", 5).coerceIn(0, 10),
            shyness = json.optInt("shyness", 3).coerceIn(0, 10),
            curiosity = json.optInt("curiosity", 5).coerceIn(0, 10),
            initiative = json.optInt("initiative", 4).coerceIn(0, 10),
            autoReplyToScene = json.optBoolean("autoReplyToScene", true),
            repairInvalidReply = json.optBoolean("repairInvalidReply", true),
            showDirectorCards = json.optBoolean("showDirectorCards", true),
        )
    }
}

/** Derived only from the surviving direction messages. Rebuildable after undo/delete. */
data class DirectorState(
    val fields: MutableMap<String, String> = linkedMapOf(),
    val mood: MutableMap<String, Int> = linkedMapOf(),
    val appliedMessageIds: MutableList<String> = mutableListOf(),
    var nextReply: String = "",
    var nextReplyFor: String = "",
    var schemaVersion: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("fields", JSONObject().also { o -> fields.forEach { (k, v) -> o.put(k, v) } })
        .put("mood", JSONObject().also { o -> mood.forEach { (k, v) -> o.put(k, v) } })
        .put("applied", JSONArray().also { a -> appliedMessageIds.forEach { a.put(it) } })
        .put("nextReply", nextReply).put("nextReplyFor", nextReplyFor).put("schemaVersion", schemaVersion)

    companion object {
        val fieldKeys = listOf("premise", "location", "time", "character", "traits", "goal", "relationship", "style", "perspective", "rules", "boundary", "outfit", "activity", "moodMode", "length", "cast", "userRole", "channel")
        val moodKeys = listOf("friendliness", "love", "trust", "annoyance", "tiredness", "playfulness")
        fun fromJson(json: JSONObject): DirectorState {
            val d = DirectorState(
                nextReply = json.optString("nextReply").take(1400),
                nextReplyFor = json.optString("nextReplyFor"),
                schemaVersion = json.optInt("schemaVersion", 1),
            )
            val f = json.optJSONObject("fields") ?: JSONObject()
            fieldKeys.forEach { key -> f.optString(key).takeIf { it.isNotBlank() }?.let { d.fields[key] = it.take(2000) } }
            val m = json.optJSONObject("mood") ?: JSONObject()
            moodKeys.forEach { key -> if (m.has(key)) d.mood[key] = m.optInt(key).coerceIn(0, 10) }
            val ids = json.optJSONArray("applied") ?: JSONArray()
            for (i in 0 until ids.length()) d.appliedMessageIds += ids.optString(i)
            return d
        }
    }
}
