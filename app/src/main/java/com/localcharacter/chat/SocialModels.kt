package com.localcharacter.chat

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Self-description is user-authored. Never infer anatomy, gender or identity from images. */
data class SelfProfile(
    var gender: String = "unspecified", var pronouns: String = "", var age: Int = 25,
    var role: String = "friend", var bio: String = "",
) {
    fun toJson() = JSONObject().put("gender", gender).put("pronouns", pronouns)
        .put("age", age).put("role", role).put("bio", bio)
    fun prompt(name: String) = "User: $name; adult age $age; gender: $gender; pronouns: ${pronouns.ifBlank { "unspecified; use their name" }}; role: $role. ${bio.take(700)}. Do not infer their body, desires, dialogue or actions from gender or role."
    companion object {
        val roles = listOf("friend", "coworker", "neighbor", "partner", "creator", "photographer", "traveler", "mentor", "adult student", "adult teacher", "custom")
        fun fromJson(j: JSONObject) = SelfProfile(j.optString("gender", "unspecified").take(80),
            j.optString("pronouns").take(80), j.optInt("age", 25).coerceIn(18, 120),
            j.optString("role", "friend").take(120), j.optString("bio").take(1200))
    }
}

data class PhotoSharingSettings(var mode: String = "request", var publicReplies: Boolean = false, var cooldownTurns: Int = 4) {
    fun toJson() = JSONObject().put("mode", mode).put("publicReplies", publicReplies).put("cooldownTurns", cooldownTurns)
    companion object { fun fromJson(j: JSONObject) = PhotoSharingSettings(
        j.optString("mode", "request").let { if (it in setOf("never", "request", "contextual")) it else "request" },
        j.optBoolean("publicReplies", false), j.optInt("cooldownTurns", 4).coerceIn(0, 30)) }
}

data class SocialCharacter(
    val id: String = UUID.randomUUID().toString(), var handle: String = "character", var role: String = "friend",
    var settings: CharacterSettings = CharacterSettings(relationship = "friend", relationshipStage = "friend"),
    var mood: BrainState = BrainState(), var enabled: Boolean = true, var baselineMood: BrainState = BrainState(),
) {
    fun toJson() = JSONObject().put("id", id).put("handle", handle).put("role", role)
        .put("settings", settings.toJson()).put("mood", mood.toJson()).put("enabled", enabled).put("baselineMood", baselineMood.toJson())
    companion object { fun fromJson(j: JSONObject) = SocialCharacter(j.optString("id", UUID.randomUUID().toString()),
        j.optString("handle", "character").take(60), j.optString("role", "friend").take(120),
        CharacterSettings.fromJson(j.optJSONObject("settings") ?: JSONObject()),
        BrainState.fromJson(j.optJSONObject("mood") ?: JSONObject()), j.optBoolean("enabled", true),
        BrainState.fromJson(j.optJSONObject("baselineMood") ?: j.optJSONObject("mood") ?: JSONObject())) }
}

data class FeedPost(
    val id: String = UUID.randomUUID().toString(), var authorId: String = "self", var caption: String = "",
    var imagePath: String = "", var imageDescription: String = "", val createdAt: Long = System.currentTimeMillis(),
    var liked: Boolean = false, var saved: Boolean = false, var revision: Long = 0,
    var scene: SceneState = SceneState(),
) {
    fun toJson() = JSONObject().put("id", id).put("author", authorId).put("caption", caption)
        .put("imagePath", imagePath).put("description", imageDescription).put("createdAt", createdAt)
        .put("liked", liked).put("saved", saved).put("revision", revision).put("scene", scene.toJson())
    companion object { fun fromJson(j: JSONObject) = FeedPost(j.optString("id", UUID.randomUUID().toString()),
        j.optString("author", "self"), j.optString("caption").take(2400), j.optString("imagePath"),
        j.optString("description").take(1200), j.optLong("createdAt", System.currentTimeMillis()),
        j.optBoolean("liked"), j.optBoolean("saved"), j.optLong("revision"),
        SceneState.fromJson(j.optJSONObject("scene") ?: JSONObject())) }
}

data class FeedComment(
    val id: String = UUID.randomUUID().toString(), val postId: String, val authorId: String,
    var text: String, val parentId: String = "", val createdAt: Long = System.currentTimeMillis(),
    var imagePath: String = "", var liked: Boolean = false, val generated: Boolean = false,
) {
    fun toJson() = JSONObject().put("id", id).put("post", postId).put("author", authorId).put("text", text)
        .put("parent", parentId).put("createdAt", createdAt).put("imagePath", imagePath).put("liked", liked).put("generated", generated)
    companion object { fun fromJson(j: JSONObject) = FeedComment(j.optString("id", UUID.randomUUID().toString()),
        j.optString("post"), j.optString("author"), j.optString("text").take(4000), j.optString("parent"),
        j.optLong("createdAt", System.currentTimeMillis()), j.optString("imagePath"), j.optBoolean("liked"), j.optBoolean("generated")) }
}

data class SocialWorld(
    val characters: MutableList<SocialCharacter> = mutableListOf(), val posts: MutableList<FeedPost> = mutableListOf(),
    val comments: MutableList<FeedComment> = mutableListOf(), var autoOwnerReply: Boolean = true,
    var globalScene: SceneState = SceneState(), var revision: Long = 0, var schema: Int = 1,
) {
    fun toJson() = JSONObject().put("schema", schema).put("characters", JSONArray().also { a -> characters.forEach { a.put(it.toJson()) } })
        .put("posts", JSONArray().also { a -> posts.forEach { a.put(it.toJson()) } })
        .put("comments", JSONArray().also { a -> comments.forEach { a.put(it.toJson()) } })
        .put("autoOwnerReply", autoOwnerReply).put("globalScene", globalScene.toJson()).put("revision", revision)
    fun name(id: String, self: String) = if (id == "self") self else characters.firstOrNull { it.id == id }?.settings?.characterName ?: "Removed character"
    /** Delete this branch only; no detached replies remain in memory. */
    fun deleteCommentBranch(id: String) {
        val doomed = mutableSetOf(id)
        var previous: Int
        do { previous = doomed.size; comments.filter { it.parentId in doomed }.forEach { doomed += it.id } } while (previous != doomed.size)
        val postIds = comments.filter { it.id in doomed }.map { it.postId }.toSet()
        comments.removeAll { it.id in doomed }
        posts.filter { it.id in postIds }.forEach { it.revision++ }; revision++
        characters.filter { it.settings.moodEnabled && it.settings.automaticMoodEnabled }.forEach { it.mood = SocialBrain.replay(this, it, "", it.settings) }
    }
    fun deletePost(id: String) { posts.removeAll { it.id == id }; comments.removeAll { it.postId == id }; revision++
        characters.filter { it.settings.moodEnabled && it.settings.automaticMoodEnabled }.forEach { it.mood = SocialBrain.replay(this, it, "", it.settings) }
    }
    companion object {
        fun fromJson(j: JSONObject): SocialWorld {
            val w = SocialWorld(autoOwnerReply = j.optBoolean("autoOwnerReply", true),
                globalScene = SceneState.fromJson(j.optJSONObject("globalScene") ?: JSONObject()), revision = j.optLong("revision"))
            val c = j.optJSONArray("characters") ?: JSONArray(); for (i in 0 until c.length()) w.characters += SocialCharacter.fromJson(c.getJSONObject(i))
            val p = j.optJSONArray("posts") ?: JSONArray(); for (i in 0 until p.length()) w.posts += FeedPost.fromJson(p.getJSONObject(i))
            val r = j.optJSONArray("comments") ?: JSONArray(); for (i in 0 until r.length()) w.comments += FeedComment.fromJson(r.getJSONObject(i))
            return w
        }
        fun initial(base: CharacterSettings) = SocialWorld(characters = mutableListOf(
            SocialCharacter(id = "main", handle = "local." + base.characterName.lowercase().replace(Regex("[^a-z0-9]"), ""),
                role = "friend", settings = CharacterSettings.fromJson(base.toJson()))))
    }
}
