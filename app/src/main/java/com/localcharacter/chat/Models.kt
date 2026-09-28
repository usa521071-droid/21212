package com.localcharacter.chat

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    var text: String,
    val timestamp: Long = System.currentTimeMillis(),
    var corrected: Boolean = false,
    var feedback: Int = 0,
    var imagePath: String = "",
    var imageDescription: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("role", role)
        .put("text", text)
        .put("timestamp", timestamp)
        .put("corrected", corrected)
        .put("feedback", feedback)
        .put("imagePath", imagePath)
        .put("imageDescription", imageDescription)

    companion object {
        fun fromJson(json: JSONObject): ChatMessage = ChatMessage(
            id = json.optString("id", UUID.randomUUID().toString()),
            role = json.optString("role", "user"),
            text = json.optString("text", ""),
            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
            corrected = json.optBoolean("corrected", false),
            feedback = json.optInt("feedback", 0).coerceIn(-1, 1),
            imagePath = json.optString("imagePath", ""),
            imageDescription = json.optString("imageDescription", ""),
        )
    }
}

data class CorrectionExample(
    val context: List<ChatMessage>,
    val desiredReply: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("context", JSONArray().apply { context.forEach { put(it.toJson()) } })
        .put("desiredReply", desiredReply)

    companion object {
        fun fromJson(json: JSONObject): CorrectionExample {
            val contextArray = json.optJSONArray("context") ?: JSONArray()
            val context = buildList {
                for (i in 0 until contextArray.length()) {
                    add(ChatMessage.fromJson(contextArray.getJSONObject(i)))
                }
            }
            return CorrectionExample(
                context = context,
                desiredReply = json.optString("desiredReply", ""),
            )
        }
    }
}

data class FeedbackExample(
    val messageId: String,
    val context: List<ChatMessage>,
    val reply: String,
    val rating: Int,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("messageId", messageId)
        .put("context", JSONArray().apply { context.forEach { put(it.toJson()) } })
        .put("reply", reply)
        .put("rating", rating)
        .put("createdAt", createdAt)

    companion object {
        fun fromJson(json: JSONObject): FeedbackExample {
            val contextArray = json.optJSONArray("context") ?: JSONArray()
            val context = buildList {
                for (i in 0 until contextArray.length()) {
                    add(ChatMessage.fromJson(contextArray.getJSONObject(i)))
                }
            }
            return FeedbackExample(
                messageId = json.optString("messageId", UUID.randomUUID().toString()),
                context = context,
                reply = json.optString("reply", ""),
                rating = json.optInt("rating", 0).coerceIn(-1, 1),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            )
        }
    }
}

data class MemoryItem(
    val id: String = UUID.randomUUID().toString(),
    var text: String,
    val category: String,
    val slot: String = "fact",
    val sourceMessageId: String,
    val createdAt: Long = System.currentTimeMillis(),
    var importance: Int = 1,
    var lastAccessedAt: Long = 0L,
    var accessCount: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("text", text)
        .put("category", category)
        .put("slot", slot)
        .put("sourceMessageId", sourceMessageId)
        .put("createdAt", createdAt)
        .put("importance", importance)
        .put("lastAccessedAt", lastAccessedAt)
        .put("accessCount", accessCount)

    companion object {
        fun fromJson(json: JSONObject): MemoryItem = MemoryItem(
            id = json.optString("id", UUID.randomUUID().toString()),
            text = json.optString("text", ""),
            category = json.optString("category", "fact"),
            slot = json.optString("slot", "fact"),
            sourceMessageId = json.optString("sourceMessageId", ""),
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            importance = json.optInt("importance", 1).coerceIn(1, 5),
            lastAccessedAt = json.optLong("lastAccessedAt", 0L),
            accessCount = json.optInt("accessCount", 0).coerceAtLeast(0),
        )
    }
}

data class EpisodeMemory(
    val id: String = UUID.randomUUID().toString(),
    val userMessageId: String,
    val assistantMessageId: String,
    var userText: String,
    var assistantText: String,
    val createdAt: Long = System.currentTimeMillis(),
    var importance: Int = 1,
    var feedback: Int = 0,
    var lastAccessedAt: Long = 0L,
    var accessCount: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("userMessageId", userMessageId)
        .put("assistantMessageId", assistantMessageId)
        .put("userText", userText)
        .put("assistantText", assistantText)
        .put("createdAt", createdAt)
        .put("importance", importance)
        .put("feedback", feedback)
        .put("lastAccessedAt", lastAccessedAt)
        .put("accessCount", accessCount)

    companion object {
        fun fromJson(json: JSONObject): EpisodeMemory = EpisodeMemory(
            id = json.optString("id", UUID.randomUUID().toString()),
            userMessageId = json.optString("userMessageId", ""),
            assistantMessageId = json.optString("assistantMessageId", ""),
            userText = json.optString("userText", ""),
            assistantText = json.optString("assistantText", ""),
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            importance = json.optInt("importance", 1).coerceIn(1, 5),
            feedback = json.optInt("feedback", 0).coerceIn(-1, 1),
            lastAccessedAt = json.optLong("lastAccessedAt", 0L),
            accessCount = json.optInt("accessCount", 0).coerceAtLeast(0),
        )
    }
}

data class ContextIndexEntry(
    val messageId: String,
    val role: String,
    var normalizedText: String,
    val keywords: MutableList<String> = mutableListOf(),
    val phrases: MutableList<String> = mutableListOf(),
    val timestamp: Long = System.currentTimeMillis(),
    var position: Int = 0,
    var feedback: Int = 0,
    var previousMessageId: String = "",
    var nextMessageId: String = "",
    var threadId: String = "",
    var intent: String = "statement",
    var subject: String = "",
    val entities: MutableList<String> = mutableListOf(),
    var replyToMessageId: String = "",
    var correctionTargetMessageId: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("messageId", messageId)
        .put("role", role)
        .put("normalizedText", normalizedText)
        .put("keywords", JSONArray().apply { keywords.forEach(::put) })
        .put("phrases", JSONArray().apply { phrases.forEach(::put) })
        .put("timestamp", timestamp)
        .put("position", position)
        .put("feedback", feedback)
        .put("previousMessageId", previousMessageId)
        .put("nextMessageId", nextMessageId)
        .put("threadId", threadId)
        .put("intent", intent)
        .put("subject", subject)
        .put("entities", JSONArray().apply { entities.forEach(::put) })
        .put("replyToMessageId", replyToMessageId)
        .put("correctionTargetMessageId", correctionTargetMessageId)

    companion object {
        fun fromJson(json: JSONObject): ContextIndexEntry {
            val keywordsArray = json.optJSONArray("keywords") ?: JSONArray()
            val phrasesArray = json.optJSONArray("phrases") ?: JSONArray()
            val entitiesArray = json.optJSONArray("entities") ?: JSONArray()
            return ContextIndexEntry(
                messageId = json.optString("messageId", ""),
                role = json.optString("role", "user"),
                normalizedText = json.optString("normalizedText", ""),
                keywords = MutableList(keywordsArray.length()) { index ->
                    keywordsArray.optString(index, "")
                }.filter(String::isNotBlank).toMutableList(),
                phrases = MutableList(phrasesArray.length()) { index ->
                    phrasesArray.optString(index, "")
                }.filter(String::isNotBlank).toMutableList(),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                position = json.optInt("position", 0).coerceAtLeast(0),
                feedback = json.optInt("feedback", 0).coerceIn(-1, 1),
                previousMessageId = json.optString("previousMessageId", ""),
                nextMessageId = json.optString("nextMessageId", ""),
                threadId = json.optString("threadId", ""),
                intent = json.optString("intent", "statement"),
                subject = json.optString("subject", ""),
                entities = MutableList(entitiesArray.length()) { index ->
                    entitiesArray.optString(index, "")
                }.filter(String::isNotBlank).toMutableList(),
                replyToMessageId = json.optString("replyToMessageId", ""),
                correctionTargetMessageId = json.optString("correctionTargetMessageId", ""),
            )
        }
    }
}


data class ContextThread(
    val id: String,
    var label: String = "",
    val keywords: MutableList<String> = mutableListOf(),
    val entities: MutableList<String> = mutableListOf(),
    val messageIds: MutableList<String> = mutableListOf(),
    var summary: String = "",
    val openLoops: MutableList<String> = mutableListOf(),
    var createdAt: Long = System.currentTimeMillis(),
    var lastActiveAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("label", label)
        .put("keywords", JSONArray().apply { keywords.forEach(::put) })
        .put("entities", JSONArray().apply { entities.forEach(::put) })
        .put("messageIds", JSONArray().apply { messageIds.forEach(::put) })
        .put("summary", summary)
        .put("openLoops", JSONArray().apply { openLoops.forEach(::put) })
        .put("createdAt", createdAt)
        .put("lastActiveAt", lastActiveAt)

    companion object {
        fun fromJson(json: JSONObject): ContextThread {
            val keywordArray = json.optJSONArray("keywords") ?: JSONArray()
            val entityArray = json.optJSONArray("entities") ?: JSONArray()
            val messageArray = json.optJSONArray("messageIds") ?: JSONArray()
            val loopArray = json.optJSONArray("openLoops") ?: JSONArray()
            return ContextThread(
                id = json.optString("id", UUID.randomUUID().toString()),
                label = json.optString("label", ""),
                keywords = MutableList(keywordArray.length()) { i ->
                    keywordArray.optString(i, "")
                }.filter(String::isNotBlank).toMutableList(),
                entities = MutableList(entityArray.length()) { i ->
                    entityArray.optString(i, "")
                }.filter(String::isNotBlank).toMutableList(),
                messageIds = MutableList(messageArray.length()) { i ->
                    messageArray.optString(i, "")
                }.filter(String::isNotBlank).toMutableList(),
                summary = json.optString("summary", ""),
                openLoops = MutableList(loopArray.length()) { i ->
                    loopArray.optString(i, "")
                }.filter(String::isNotBlank).toMutableList(),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                lastActiveAt = json.optLong("lastActiveAt", System.currentTimeMillis()),
            )
        }
    }
}

data class DialogueContext(
    var activeThreadId: String = "",
    var activeSubject: String = "",
    var lastUserIntent: String = "",
    var lastAssistantIntent: String = "",
    var lastUserMessageId: String = "",
    var lastAssistantMessageId: String = "",
    var lastQuestionMessageId: String = "",
    var lastProposalMessageId: String = "",
    var lastRefusalMessageId: String = "",
    var lastCorrectionMessageId: String = "",
    val linkedMessageIds: MutableList<String> = mutableListOf(),
    var version: Int = 2,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("activeThreadId", activeThreadId)
        .put("activeSubject", activeSubject)
        .put("lastUserIntent", lastUserIntent)
        .put("lastAssistantIntent", lastAssistantIntent)
        .put("lastUserMessageId", lastUserMessageId)
        .put("lastAssistantMessageId", lastAssistantMessageId)
        .put("lastQuestionMessageId", lastQuestionMessageId)
        .put("lastProposalMessageId", lastProposalMessageId)
        .put("lastRefusalMessageId", lastRefusalMessageId)
        .put("lastCorrectionMessageId", lastCorrectionMessageId)
        .put("linkedMessageIds", JSONArray().apply { linkedMessageIds.forEach(::put) })
        .put("version", version)

    companion object {
        fun fromJson(json: JSONObject): DialogueContext {
            val linkedArray = json.optJSONArray("linkedMessageIds") ?: JSONArray()
            return DialogueContext(
                activeThreadId = json.optString("activeThreadId", ""),
                activeSubject = json.optString("activeSubject", ""),
                lastUserIntent = json.optString("lastUserIntent", ""),
                lastAssistantIntent = json.optString("lastAssistantIntent", ""),
                lastUserMessageId = json.optString("lastUserMessageId", ""),
                lastAssistantMessageId = json.optString("lastAssistantMessageId", ""),
                lastQuestionMessageId = json.optString("lastQuestionMessageId", ""),
                lastProposalMessageId = json.optString("lastProposalMessageId", ""),
                lastRefusalMessageId = json.optString("lastRefusalMessageId", ""),
                lastCorrectionMessageId = json.optString("lastCorrectionMessageId", ""),
                linkedMessageIds = MutableList(linkedArray.length()) { i ->
                    linkedArray.optString(i, "")
                }.filter(String::isNotBlank).toMutableList(),
                version = json.optInt("version", 2),
            )
        }
    }
}


data class CharacterPhoto(
    val path: String,
    var caption: String = "",
    var description: String = "",
    val tags: MutableList<String> = mutableListOf(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("path", path)
        .put("caption", caption)
        .put("description", description)
        .put("tags", JSONArray().apply { tags.forEach(::put) })

    companion object {
        fun fromJson(json: JSONObject): CharacterPhoto {
            val tagArray = json.optJSONArray("tags") ?: JSONArray()
            return CharacterPhoto(
                path = json.optString("path", ""),
                caption = json.optString("caption", ""),
                description = json.optString("description", ""),
                tags = MutableList(tagArray.length()) { index ->
                    tagArray.optString(index, "")
                }.filter(String::isNotBlank).toMutableList(),
            )
        }
    }
}

data class SceneState(
    var active: Boolean = false,
    var premise: String = "",
    var relationshipOverride: String = "",
    var characterGoal: String = "",
    val directives: MutableList<String> = mutableListOf(),
    var updatedAt: Long = 0L,
    var director: DirectorState = DirectorState(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("active", active)
        .put("premise", premise)
        .put("relationshipOverride", relationshipOverride)
        .put("characterGoal", characterGoal)
        .put("directives", JSONArray().apply { directives.forEach(::put) })
        .put("updatedAt", updatedAt)
        .put("director", director.toJson())

    companion object {
        fun fromJson(json: JSONObject): SceneState {
            val directiveArray = json.optJSONArray("directives") ?: JSONArray()
            return SceneState(
                active = json.optBoolean("active", false),
                premise = json.optString("premise", ""),
                relationshipOverride = json.optString("relationshipOverride", ""),
                characterGoal = json.optString("characterGoal", ""),
                directives = MutableList(directiveArray.length()) { index ->
                    directiveArray.optString(index, "")
                }.filter(String::isNotBlank).toMutableList(),
                updatedAt = json.optLong("updatedAt", 0L),
                director = DirectorState.fromJson(json.optJSONObject("director") ?: JSONObject()),
            )
        }
    }
}

data class BrainState(
    var friendliness: Int = 7,
    var love: Int = 5,
    var trust: Int = 5,
    var annoyance: Int = 0,
    var tiredness: Int = 2,
    var playfulness: Int = 5,
    var moodLabel: String = "warm",
    var currentTopic: String = "",
    var rollingSummary: String = "",
    val openThreads: MutableList<String> = mutableListOf(),
    val recentTopics: MutableList<String> = mutableListOf(),
    var interactionCount: Int = 0,
    var lastUpdatedAt: Long = System.currentTimeMillis(),
    var lastMoodReason: String = "",
    var lastProcessedUserMessageId: String = "",
    var lastProcessedAssistantMessageId: String = "",
) {
    fun clampScores() {
        friendliness = friendliness.coerceIn(0, 10)
        love = love.coerceIn(0, 10)
        trust = trust.coerceIn(0, 10)
        annoyance = annoyance.coerceIn(0, 10)
        tiredness = tiredness.coerceIn(0, 10)
        playfulness = playfulness.coerceIn(0, 10)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("friendliness", friendliness)
        .put("love", love)
        .put("trust", trust)
        .put("annoyance", annoyance)
        .put("tiredness", tiredness)
        .put("playfulness", playfulness)
        .put("moodLabel", moodLabel)
        .put("currentTopic", currentTopic)
        .put("rollingSummary", rollingSummary)
        .put("openThreads", JSONArray().apply { openThreads.forEach(::put) })
        .put("recentTopics", JSONArray().apply { recentTopics.forEach(::put) })
        .put("interactionCount", interactionCount)
        .put("lastUpdatedAt", lastUpdatedAt)
        .put("lastMoodReason", lastMoodReason)
        .put("lastProcessedUserMessageId", lastProcessedUserMessageId)
        .put("lastProcessedAssistantMessageId", lastProcessedAssistantMessageId)

    companion object {
        fun fromJson(json: JSONObject): BrainState {
            val openThreadsArray = json.optJSONArray("openThreads") ?: JSONArray()
            val recentTopicsArray = json.optJSONArray("recentTopics") ?: JSONArray()
            return BrainState(
                friendliness = json.optInt("friendliness", 7).coerceIn(0, 10),
                love = json.optInt("love", 5).coerceIn(0, 10),
                trust = json.optInt("trust", 5).coerceIn(0, 10),
                annoyance = json.optInt("annoyance", 0).coerceIn(0, 10),
                tiredness = json.optInt("tiredness", 2).coerceIn(0, 10),
                playfulness = json.optInt("playfulness", 5).coerceIn(0, 10),
                moodLabel = json.optString("moodLabel", "warm"),
                currentTopic = json.optString("currentTopic", ""),
                rollingSummary = json.optString("rollingSummary", ""),
                openThreads = MutableList(openThreadsArray.length()) { index ->
                    openThreadsArray.optString(index, "")
                }.filter(String::isNotBlank).toMutableList(),
                recentTopics = MutableList(recentTopicsArray.length()) { index ->
                    recentTopicsArray.optString(index, "")
                }.filter(String::isNotBlank).toMutableList(),
                interactionCount = json.optInt("interactionCount", 0).coerceAtLeast(0),
                lastUpdatedAt = json.optLong("lastUpdatedAt", System.currentTimeMillis()),
                lastMoodReason = json.optString("lastMoodReason", ""),
                lastProcessedUserMessageId = json.optString("lastProcessedUserMessageId", ""),
                lastProcessedAssistantMessageId = json.optString("lastProcessedAssistantMessageId", ""),
            ).apply { clampScores() }
        }
    }
}

data class GenerationMetrics(
    val promptEvalTimeMs: Long = 0L,
    val generateTimeMs: Long = 0L,
    val tokensPerSecond: Float = 0f,
    val promptMode: String = "",
    val selectedMessages: Int = 0,
    val selectedMemories: Int = 0,
    val selectedEpisodes: Int = 0,
    val generatedAt: Long = 0L,
    val movingPromptEvalTimeMs: Long = 0L,
    val movingGenerateTimeMs: Long = 0L,
    val promptCharacters: Int = 0,
    val contextBuildTimeMs: Long = 0L,
    val performanceProfile: String = "balanced",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("promptEvalTimeMs", promptEvalTimeMs)
        .put("generateTimeMs", generateTimeMs)
        .put("tokensPerSecond", tokensPerSecond.toDouble())
        .put("promptMode", promptMode)
        .put("selectedMessages", selectedMessages)
        .put("selectedMemories", selectedMemories)
        .put("selectedEpisodes", selectedEpisodes)
        .put("generatedAt", generatedAt)
        .put("movingPromptEvalTimeMs", movingPromptEvalTimeMs)
        .put("movingGenerateTimeMs", movingGenerateTimeMs)
        .put("promptCharacters", promptCharacters)
        .put("contextBuildTimeMs", contextBuildTimeMs)
        .put("performanceProfile", performanceProfile)

    companion object {
        fun fromJson(json: JSONObject): GenerationMetrics = GenerationMetrics(
            promptEvalTimeMs = json.optLong("promptEvalTimeMs", 0L),
            generateTimeMs = json.optLong("generateTimeMs", 0L),
            tokensPerSecond = json.optDouble("tokensPerSecond", 0.0).toFloat(),
            promptMode = json.optString("promptMode", ""),
            selectedMessages = json.optInt("selectedMessages", 0),
            selectedMemories = json.optInt("selectedMemories", 0),
            selectedEpisodes = json.optInt("selectedEpisodes", 0),
            generatedAt = json.optLong("generatedAt", 0L),
            movingPromptEvalTimeMs = json.optLong("movingPromptEvalTimeMs", 0L),
            movingGenerateTimeMs = json.optLong("movingGenerateTimeMs", 0L),
            promptCharacters = json.optInt("promptCharacters", 0).coerceAtLeast(0),
            contextBuildTimeMs = json.optLong("contextBuildTimeMs", 0L).coerceAtLeast(0L),
            performanceProfile = json.optString("performanceProfile", "balanced").lowercase().let {
                if (it in setOf("fast", "balanced", "quality")) it else "balanced"
            },
        )
    }
}

data class CharacterSettings(
    var characterName: String = "Elena",
    var characterAge: Int = 30,
    var characterBody: String = "female",
    var userName: String = "You",
    var selfProfile: SelfProfile = SelfProfile(),
    var photoSharing: PhotoSharingSettings = PhotoSharingSettings(),
    var relationship: String = "close romantic partner",
    var relationshipStage: String = "auto",
    var persona: String = "Warm, playful, confident, affectionate and emotionally attentive. She writes like a real person, remembers details, can tease, and avoids customer-service language.",
    var languageRule: String = "Match the user's language. Use Romanian when the user writes Romanian and English when the user writes English.",
    var adultLanguage: Boolean = true,
    var sceneDirectionsEnabled: Boolean = true,
    var emojiStyle: String = "natural",
    var replyStyle: String = "medium",
    var typingRealism: String = "natural",
    var maxTokens: Int = 160,
    var contextSize: Int = 3072,
    var temperature: Float = 0.75f,
    var topP: Float = 0.90f,
    var topK: Int = 40,
    var cpuThreads: Int = 0, // 0 = automatic; benchmark on the actual phone.
    var modelPath: String = "",
    var modelDisplayName: String = "",
    var avatarPath: String = "",
    var characterPhotoPaths: MutableList<String> = mutableListOf(),
    var characterPhotos: MutableList<CharacterPhoto> = mutableListOf(),
    var memoryEnabled: Boolean = true,
    var moodEnabled: Boolean = true,
    var automaticMoodEnabled: Boolean = true,
    var showMoodInHeader: Boolean = false,
    var performanceProfile: String = "balanced",
    var adaptivePerformance: Boolean = true,
    var performanceVersion: Int = 40,
    var roleplay: RoleplaySettings = RoleplaySettings(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("characterName", characterName)
        .put("characterAge", characterAge)
        .put("characterBody", characterBody)
        .put("userName", userName)
        .put("selfProfile", selfProfile.toJson())
        .put("photoSharing", photoSharing.toJson())
        .put("relationship", relationship)
        .put("relationshipStage", relationshipStage)
        .put("persona", persona)
        .put("languageRule", languageRule)
        .put("adultLanguage", adultLanguage)
        .put("sceneDirectionsEnabled", sceneDirectionsEnabled)
        .put("emojiStyle", emojiStyle)
        .put("replyStyle", replyStyle)
        .put("typingRealism", typingRealism)
        .put("maxTokens", maxTokens)
        .put("contextSize", contextSize)
        .put("temperature", temperature.toDouble())
        .put("topP", topP.toDouble())
        .put("topK", topK)
        .put("cpuThreads", cpuThreads)
        .put("modelPath", modelPath)
        .put("modelDisplayName", modelDisplayName)
        .put("avatarPath", avatarPath)
        .put("characterPhotoPaths", JSONArray().apply { characterPhotoPaths.forEach(::put) })
        .put("characterPhotos", JSONArray().apply { characterPhotos.forEach { put(it.toJson()) } })
        .put("memoryEnabled", memoryEnabled)
        .put("moodEnabled", moodEnabled)
        .put("automaticMoodEnabled", automaticMoodEnabled)
        .put("showMoodInHeader", showMoodInHeader)
        .put("performanceProfile", performanceProfile)
        .put("adaptivePerformance", adaptivePerformance)
        .put("performanceVersion", performanceVersion)
        .put("roleplay", roleplay.toJson())

    companion object {
        fun fromJson(json: JSONObject): CharacterSettings {
            val photoArray = json.optJSONArray("characterPhotoPaths") ?: JSONArray()
            val photoPaths = MutableList(photoArray.length()) { index ->
                photoArray.optString(index, "")
            }.filter(String::isNotBlank).toMutableList()
            val characterPhotoArray = json.optJSONArray("characterPhotos") ?: JSONArray()
            val loadedPhotos = MutableList(characterPhotoArray.length()) { index ->
                CharacterPhoto.fromJson(characterPhotoArray.getJSONObject(index))
            }.filter { it.path.isNotBlank() }.toMutableList()
            if (loadedPhotos.isEmpty()) {
                photoPaths.forEach { path -> loadedPhotos += CharacterPhoto(path = path) }
            }
            val mergedPaths = (photoPaths + loadedPhotos.map { it.path })
                .filter(String::isNotBlank)
                .distinct()
                .toMutableList()
            return CharacterSettings(
            characterName = json.optString("characterName", "Elena"),
            characterAge = json.optInt("characterAge", 30).coerceAtLeast(18),
            characterBody = json.optString("characterBody", "female").lowercase().let {
                if (it == "male") "male" else "female"
            },
            userName = json.optString("userName", "You"),
            selfProfile = SelfProfile.fromJson(json.optJSONObject("selfProfile") ?: JSONObject()),
            photoSharing = PhotoSharingSettings.fromJson(json.optJSONObject("photoSharing") ?: JSONObject()),
            relationship = json.optString("relationship", "close romantic partner"),
            relationshipStage = json.optString("relationshipStage", "auto").lowercase().let {
                if (it in setOf("auto", "stranger", "acquaintance", "coworker", "friend", "close_friend", "dating", "partner", "married", "casual")) it else "auto"
            },
            persona = json.optString("persona", CharacterSettings().persona),
            languageRule = json.optString("languageRule", CharacterSettings().languageRule),
            adultLanguage = json.optBoolean("adultLanguage", true),
            sceneDirectionsEnabled = json.optBoolean("sceneDirectionsEnabled", true),
            emojiStyle = json.optString("emojiStyle", "natural").lowercase().let {
                if (it in setOf("none", "natural", "expressive")) it else "natural"
            },
            replyStyle = json.optString("replyStyle", "medium").lowercase().let {
                if (it in setOf("short", "medium", "long")) it else "medium"
            },
            typingRealism = json.optString("typingRealism", "natural").lowercase().let {
                if (it in setOf("clean", "natural", "casual", "minimal", "warm", "dry", "playful", "formal", "expressive")) it else "natural"
            },
            maxTokens = json.optInt("maxTokens", 160).coerceIn(24, 320),
            contextSize = json.optInt("contextSize", 3072).coerceIn(1024, 8192),
            temperature = json.optDouble("temperature", 0.75).toFloat().coerceIn(0.1f, 1.5f),
            topP = json.optDouble("topP", 0.90).toFloat().coerceIn(0.1f, 1.0f),
            topK = json.optInt("topK", 40).coerceIn(1, 100),
            cpuThreads = json.optInt("cpuThreads", 0).coerceIn(0, 16),
            modelPath = json.optString("modelPath", ""),
            modelDisplayName = json.optString("modelDisplayName", ""),
            avatarPath = json.optString("avatarPath", ""),
            memoryEnabled = json.optBoolean("memoryEnabled", true),
            moodEnabled = json.optBoolean("moodEnabled", true),
            automaticMoodEnabled = json.optBoolean("automaticMoodEnabled", true),
            showMoodInHeader = json.optBoolean("showMoodInHeader", false),
            performanceProfile = json.optString("performanceProfile", "balanced").lowercase().let {
                if (it in setOf("fast", "balanced", "quality")) it else "balanced"
            },
            adaptivePerformance = json.optBoolean("adaptivePerformance", true),
            performanceVersion = json.optInt("performanceVersion", 0),
                roleplay = RoleplaySettings.fromJson(json.optJSONObject("roleplay") ?: JSONObject()),
            characterPhotoPaths = mergedPaths,
            characterPhotos = loadedPhotos,
        )
        }
    }
}

data class ConversationState(
    val messages: MutableList<ChatMessage> = mutableListOf(),
    val corrections: MutableList<CorrectionExample> = mutableListOf(),
    val feedbackExamples: MutableList<FeedbackExample> = mutableListOf(),
    val memories: MutableList<MemoryItem> = mutableListOf(),
    val episodes: MutableList<EpisodeMemory> = mutableListOf(),
    val contextIndex: MutableList<ContextIndexEntry> = mutableListOf(),
    val contextThreads: MutableList<ContextThread> = mutableListOf(),
    var dialogueContext: DialogueContext = DialogueContext(),
    var sceneState: SceneState = SceneState(),
    var brain: BrainState = BrainState(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("messages", JSONArray().apply { messages.forEach { put(it.toJson()) } })
        .put("corrections", JSONArray().apply { corrections.forEach { put(it.toJson()) } })
        .put("feedbackExamples", JSONArray().apply { feedbackExamples.forEach { put(it.toJson()) } })
        .put("memories", JSONArray().apply { memories.forEach { put(it.toJson()) } })
        .put("episodes", JSONArray().apply { episodes.forEach { put(it.toJson()) } })
        .put("contextIndex", JSONArray().apply { contextIndex.forEach { put(it.toJson()) } })
        .put("contextThreads", JSONArray().apply { contextThreads.forEach { put(it.toJson()) } })
        .put("dialogueContext", dialogueContext.toJson())
        .put("sceneState", sceneState.toJson())
        .put("brain", brain.toJson())

    companion object {
        fun fromJson(json: JSONObject): ConversationState {
            val messagesArray = json.optJSONArray("messages") ?: JSONArray()
            val correctionsArray = json.optJSONArray("corrections") ?: JSONArray()
            val feedbackArray = json.optJSONArray("feedbackExamples") ?: JSONArray()
            val memoriesArray = json.optJSONArray("memories") ?: JSONArray()
            val episodesArray = json.optJSONArray("episodes") ?: JSONArray()
            val contextIndexArray = json.optJSONArray("contextIndex") ?: JSONArray()
            val contextThreadsArray = json.optJSONArray("contextThreads") ?: JSONArray()

            val messages = mutableListOf<ChatMessage>()
            val corrections = mutableListOf<CorrectionExample>()
            val feedbackExamples = mutableListOf<FeedbackExample>()
            val memories = mutableListOf<MemoryItem>()
            val episodes = mutableListOf<EpisodeMemory>()
            val contextIndex = mutableListOf<ContextIndexEntry>()
            val contextThreads = mutableListOf<ContextThread>()

            for (i in 0 until messagesArray.length()) {
                messages += ChatMessage.fromJson(messagesArray.getJSONObject(i))
            }
            for (i in 0 until correctionsArray.length()) {
                corrections += CorrectionExample.fromJson(correctionsArray.getJSONObject(i))
            }
            for (i in 0 until feedbackArray.length()) {
                feedbackExamples += FeedbackExample.fromJson(feedbackArray.getJSONObject(i))
            }
            for (i in 0 until memoriesArray.length()) {
                memories += MemoryItem.fromJson(memoriesArray.getJSONObject(i))
            }
            for (i in 0 until episodesArray.length()) {
                episodes += EpisodeMemory.fromJson(episodesArray.getJSONObject(i))
            }
            for (i in 0 until contextIndexArray.length()) {
                contextIndex += ContextIndexEntry.fromJson(contextIndexArray.getJSONObject(i))
            }
            for (i in 0 until contextThreadsArray.length()) {
                contextThreads += ContextThread.fromJson(contextThreadsArray.getJSONObject(i))
            }

            return ConversationState(
                messages = messages,
                corrections = corrections,
                feedbackExamples = feedbackExamples,
                memories = memories,
                episodes = episodes,
                contextIndex = contextIndex,
                contextThreads = contextThreads,
                dialogueContext = json.optJSONObject("dialogueContext")?.let(DialogueContext::fromJson)
                    ?: DialogueContext(),
                sceneState = json.optJSONObject("sceneState")?.let(SceneState::fromJson) ?: SceneState(),
                brain = json.optJSONObject("brain")?.let(BrainState::fromJson) ?: BrainState(),
            )
        }
    }
}
