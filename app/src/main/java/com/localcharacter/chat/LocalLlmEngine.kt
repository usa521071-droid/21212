package com.localcharacter.chat

import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class LocalLlmEngine {
    private val nativeMutex = Mutex()
    @Volatile private var releaseRequested = false

    @Volatile
    private var model: LlamaModel? = null

    @Volatile
    private var loadedPath: String = ""

    @Volatile
    private var loadedSignature: String = ""

    @Volatile
    var loadedThreadCount: Int = 0
        private set

    val isLoaded: Boolean
        get() = model != null

    suspend fun ensureLoaded(settings: CharacterSettings) = nativeMutex.withLock { loadLocked(settings) }

    private suspend fun loadLocked(settings: CharacterSettings) {
            val file = File(settings.modelPath)
            require(file.exists() && file.isFile) {
                "The selected GGUF model file no longer exists."
            }

            val threads = if (settings.cpuThreads > 0) settings.cpuThreads.coerceIn(1, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
                else chooseThreadCount(file, settings.performanceProfile)
            val signature = listOf(
                file.absolutePath,
                file.length(),
                file.lastModified(),
                settings.contextSize,
                settings.temperature,
                settings.topP,
                settings.topK,
                settings.performanceProfile,
                threads,
            ).joinToString("|")

            if (
                model != null &&
                loadedPath == file.absolutePath &&
                loadedSignature == signature
            ) {
                return
            }

            releaseModel()
            releaseRequested = false
            model = Llama.loadModel(
                modelPath = file.absolutePath,
                config = LlamaConfig(
                    contextSize = settings.contextSize,
                    threads = threads,
                    gpuLayers = 0,
                    temperature = settings.temperature,
                    topP = settings.topP,
                    topK = settings.topK,
                    seed = -1,
                ),
            )
            loadedPath = file.absolutePath
            loadedSignature = signature
            loadedThreadCount = threads
    }

    /** Atomically select the model/configuration and generate, even when DM/feed callers alternate. */
    suspend fun completeFor(settings: CharacterSettings, prompt: String, systemPrompt: String, maxTokens: Int): EngineReply = nativeMutex.withLock {
        loadLocked(settings)
        completeLocked(prompt, systemPrompt, maxTokens)
    }

    suspend fun complete(
        prompt: String,
        systemPrompt: String,
        maxTokens: Int,
    ): EngineReply = nativeMutex.withLock { completeLocked(prompt, systemPrompt, maxTokens) }

    private suspend fun completeLocked(prompt: String, systemPrompt: String, maxTokens: Int): EngineReply {
        try {
        val active = requireNotNull(model) { "No model is loaded." }
        val result = Llama.complete(
            model = active,
            prompt = prompt,
            systemPrompt = systemPrompt,
            maxTokens = maxTokens,
        )
        return EngineReply(
            text = result.text.trim(),
            tokensPerSecond = result.tokensPerSecond,
            promptEvalTimeMs = result.promptEvalTimeMs,
            generateTimeMs = result.generateTimeMs,
            threadCount = loadedThreadCount,
            tokensGenerated = result.tokensGenerated,
        )
        } finally {
            if (releaseRequested) { releaseRequested = false; releaseModel() }
        }
    }

    fun unload() {
        if (nativeMutex.tryLock()) {
            try { releaseModel() } finally { nativeMutex.unlock() }
        } else {
            // Never free the native handle while JNI is reading it.
            releaseRequested = true
        }
    }

    @Synchronized
    private fun releaseModel() {
        model?.let(Llama::releaseModel)
        model = null
        loadedPath = ""
        loadedSignature = ""
        loadedThreadCount = 0
    }

    private fun chooseThreadCount(file: File, profileValue: String): Int {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        val size = file.length()
        val base = when {
            size >= 2_000_000_000L -> 4
            size >= 900_000_000L -> 5
            else -> 6
        }
        val desired = when (profileValue.lowercase()) {
            "fast" -> (base + 1).coerceAtMost(7)
            "quality" -> base
            else -> base
        }
        return desired.coerceAtMost(cores).coerceAtLeast(2)
    }
}
