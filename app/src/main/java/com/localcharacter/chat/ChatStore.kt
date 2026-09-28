package com.localcharacter.chat

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ChatStore(context: Context) {
    companion object {
        private val cacheLock = Any()

        @Volatile
        private var cachedSettings: CharacterSettings? = null

        @Volatile
        private var cachedConversation: ConversationState? = null

        @Volatile
        private var cachedGenerationMetrics: GenerationMetrics? = null
    }

    private val prefs = context.applicationContext
        .getSharedPreferences("private_character_chat", Context.MODE_PRIVATE)
    private val crypto = LocalCrypto()

    var settings: CharacterSettings = synchronized(cacheLock) {
        cachedSettings ?: loadSettingsFromDisk().also { cachedSettings = it }
    }
        private set

    var conversation: ConversationState = synchronized(cacheLock) {
        cachedConversation ?: loadConversationFromDisk().also { cachedConversation = it }
    }
        private set

    var generationMetrics: GenerationMetrics = synchronized(cacheLock) {
        cachedGenerationMetrics ?: loadGenerationMetricsFromDisk().also {
            cachedGenerationMetrics = it
        }
    }
        private set

    fun saveSettings(next: CharacterSettings) {
        settings = next
        synchronized(cacheLock) { cachedSettings = next }
        putEncrypted("settings", next.toJson().toString())
    }

    /**
     * Performs the full index/memory maintenance pass and then persists. This is
     * intentionally called from the background reply service, not for every UI
     * keystroke/send operation.
     */
    fun saveConversation() {
        if (settings.memoryEnabled) {
            MemoryManager.refresh(conversation)
        } else {
            MemoryManager.refreshContext(conversation)
        }
        persistConversationOnly()
    }

    /** Persist the current object graph without rebuilding the 500-message index. */
    fun persistConversationOnly() {
        synchronized(cacheLock) { cachedConversation = conversation }
        putEncrypted("conversation", conversation.toJson().toString())
    }

    fun saveGenerationMetrics(next: GenerationMetrics) {
        generationMetrics = next
        synchronized(cacheLock) { cachedGenerationMetrics = next }
        putEncrypted("generation_metrics", next.toJson().toString())
    }

    fun clearConversation() {
        conversation = ConversationState()
        generationMetrics = GenerationMetrics()
        FastContextCache.invalidate()
        synchronized(cacheLock) {
            cachedConversation = conversation
            cachedGenerationMetrics = generationMetrics
        }
        persistConversationOnly()
        saveGenerationMetrics(generationMetrics)
    }

    fun markGenerationStarted() {
        prefs.edit()
            .putBoolean("generation_pending", true)
            .putLong("generation_started_at", System.currentTimeMillis())
            .apply()
    }

    fun markGenerationFinished() {
        prefs.edit()
            .putBoolean("generation_pending", false)
            .remove("generation_started_at")
            .apply()
    }

    fun isGenerationPending(): Boolean {
        if (!prefs.getBoolean("generation_pending", false)) return false
        val started = prefs.getLong("generation_started_at", 0L)
        if (started <= 0L || System.currentTimeMillis() - started > 15 * 60 * 1000L) {
            markGenerationFinished()
            return false
        }
        return true
    }

    fun shouldWarnForSlowModel(path: String): Boolean =
        prefs.getString("slow_model_warning_path", null) != path

    fun markSlowModelWarningShown(path: String) {
        prefs.edit().putString("slow_model_warning_path", path).apply()
    }

    /** Old releases silently deleted messages here. Retrieval filtering must never delete user data. */
    fun removeBrokenAssistantMessages(): Int = 0

    fun isAgeConfirmed(): Boolean = prefs.getBoolean("age_confirmed", false)

    fun confirmAge() {
        prefs.edit().putBoolean("age_confirmed", true).apply()
    }

    private fun loadSettingsFromDisk(): CharacterSettings = try {
        val raw = getDecrypted("settings")
        val loaded = if (raw.isNullOrBlank()) {
            CharacterSettings()
        } else {
            CharacterSettings.fromJson(JSONObject(raw))
        }
        if (loaded.performanceVersion < 40) {
            loaded.copy(
                maxTokens = loaded.maxTokens.coerceIn(24, 320),
                contextSize = loaded.contextSize.coerceIn(1024, 8192),
                temperature = loaded.temperature,
                memoryEnabled = loaded.memoryEnabled,
                performanceProfile = loaded.performanceProfile.lowercase().let {
                    if (it in setOf("fast", "balanced", "quality")) it else "balanced"
                },
                adaptivePerformance = loaded.adaptivePerformance,
                performanceVersion = 40,
            )
        } else {
            loaded
        }
    } catch (_: Exception) {
        CharacterSettings()
    }

    private fun loadConversationFromDisk(): ConversationState = try {
        val raw = getDecrypted("conversation")
        val loaded = if (raw.isNullOrBlank()) {
            ConversationState()
        } else {
            ConversationState.fromJson(JSONObject(raw))
        }
        if (settings.memoryEnabled) {
            MemoryManager.refresh(loaded)
        } else {
            MemoryManager.refreshContext(loaded)
        }
        // Upgrade old concatenated director state using surviving source messages.
        if (loaded.sceneState.active && loaded.sceneState.director.appliedMessageIds.isEmpty()) {
            SceneDirector.rebuildFromHistory(settings, loaded)
        }
        BrainEngine.recalculateMood(loaded.brain)
        val latestUser = loaded.messages.lastOrNull { it.role == "user" }?.text.orEmpty()
        BrainEngine.refreshContext(settings, loaded, latestUser)
        loaded
    } catch (_: Exception) {
        ConversationState()
    }

    private fun loadGenerationMetricsFromDisk(): GenerationMetrics = try {
        val raw = getDecrypted("generation_metrics")
        if (raw.isNullOrBlank()) {
            GenerationMetrics()
        } else {
            GenerationMetrics.fromJson(JSONObject(raw))
        }
    } catch (_: Exception) {
        GenerationMetrics()
    }

    private fun putEncrypted(key: String, plaintext: String) {
        prefs.edit().putString(key, "enc1:" + crypto.encrypt(plaintext)).apply()
    }

    private fun getDecrypted(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return if (stored.startsWith("enc1:")) {
            crypto.decrypt(stored.removePrefix("enc1:"))
        } else {
            // One-time compatibility with an older plaintext project build.
            stored
        }
    }
}

private class LocalCrypto {
    companion object {
        private const val KEY_ALIAS = "private_character_chat_aes_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(1 + cipher.iv.size + ciphertext.size)
        packed[0] = cipher.iv.size.toByte()
        System.arraycopy(cipher.iv, 0, packed, 1, cipher.iv.size)
        System.arraycopy(ciphertext, 0, packed, 1 + cipher.iv.size, ciphertext.size)
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String {
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.isNotEmpty()) { "Encrypted data is empty." }
        val ivSize = packed[0].toInt() and 0xFF
        require(ivSize in 12..32 && packed.size > 1 + ivSize) {
            "Encrypted data is malformed."
        }
        val iv = packed.copyOfRange(1, 1 + ivSize)
        val ciphertext = packed.copyOfRange(1 + ivSize, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
