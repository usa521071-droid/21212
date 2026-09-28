package com.localcharacter.chat

import kotlin.math.roundToInt

data class ContextPerformancePlan(
    val profile: String,
    val contextCharacterBudget: Int,
    val semanticLimit: Int,
    val memoryLimit: Int,
    val episodeLimit: Int,
    val correctionLimit: Int,
    val positiveFeedbackLimit: Int,
    val negativeFeedbackLimit: Int,
    val recentAssistantLimit: Int,
    val maxOutputTokens: Int,
    val adaptiveScale: Double,
)

/**
 * Keeps prompt construction and output length bounded as a conversation grows.
 * The configured llama context is treated as a ceiling rather than a target to
 * fill on every turn.
 */
object PerformanceController {
    fun plan(
        settings: CharacterSettings,
        mode: PromptMode,
        previous: GenerationMetrics,
    ): ContextPerformancePlan {
        val profile = normalizedProfile(settings.performanceProfile)
        val scale = if (settings.adaptivePerformance) adaptiveScale(previous) else 1.0

        val baseContext = when (profile) {
            "fast" -> when (mode) {
                PromptMode.GREETING -> 0
                PromptMode.SHORT -> 420
                PromptMode.REFERENCE -> 1_250
                PromptMode.NORMAL -> 1_450
                PromptMode.DEEP -> 2_300
                PromptMode.SCENE -> 1_750
            }
            "quality" -> when (mode) {
                PromptMode.GREETING -> 0
                PromptMode.SHORT -> 900
                PromptMode.REFERENCE -> 3_300
                PromptMode.NORMAL -> 4_200
                PromptMode.DEEP -> 6_300
                PromptMode.SCENE -> 4_700
            }
            else -> when (mode) {
                PromptMode.GREETING -> 0
                PromptMode.SHORT -> 650
                PromptMode.REFERENCE -> 2_050
                PromptMode.NORMAL -> 2_450
                PromptMode.DEEP -> 3_750
                PromptMode.SCENE -> 2_850
            }
        }

        // Leave room for the stable system prompt, newest message and output.
        val contextCeiling = ((settings.contextSize.coerceIn(1024, 8192) - 700) * 2.7)
            .roundToInt()
            .coerceAtLeast(700)
        val budget = (baseContext * scale)
            .roundToInt()
            .coerceAtMost(contextCeiling)
            .coerceAtLeast(if (mode == PromptMode.GREETING) 0 else 280)

        val semanticBase = when (profile) {
            "fast" -> when (mode) {
                PromptMode.GREETING, PromptMode.SHORT -> 0
                PromptMode.REFERENCE -> 6
                PromptMode.NORMAL -> 4
                PromptMode.DEEP -> 8
                PromptMode.SCENE -> 5
            }
            "quality" -> when (mode) {
                PromptMode.GREETING, PromptMode.SHORT -> 0
                PromptMode.REFERENCE -> 16
                PromptMode.NORMAL -> 13
                PromptMode.DEEP -> 20
                PromptMode.SCENE -> 14
            }
            else -> when (mode) {
                PromptMode.GREETING, PromptMode.SHORT -> 0
                PromptMode.REFERENCE -> 10
                PromptMode.NORMAL -> 8
                PromptMode.DEEP -> 12
                PromptMode.SCENE -> 9
            }
        }
        val semanticLimit = if (scale < 0.70) {
            (semanticBase * 0.65).roundToInt().coerceAtLeast(if (semanticBase == 0) 0 else 2)
        } else {
            semanticBase
        }

        val memoryLimit = when {
            mode in setOf(PromptMode.GREETING, PromptMode.SHORT) -> 0
            profile == "fast" -> 2
            profile == "quality" -> 5
            else -> 3
        }
        val episodeLimit = when {
            mode in setOf(PromptMode.GREETING, PromptMode.SHORT) -> 0
            profile == "fast" -> 1
            profile == "quality" -> 4
            else -> 2
        }
        val exampleLimit = if (profile == "quality") 2 else 1

        return ContextPerformancePlan(
            profile = profile,
            contextCharacterBudget = budget,
            semanticLimit = semanticLimit,
            memoryLimit = memoryLimit,
            episodeLimit = episodeLimit,
            correctionLimit = exampleLimit,
            positiveFeedbackLimit = exampleLimit,
            negativeFeedbackLimit = exampleLimit,
            recentAssistantLimit = when (mode) {
                PromptMode.GREETING -> 0
                PromptMode.SHORT -> 1
                PromptMode.REFERENCE -> 3
                PromptMode.NORMAL -> 3
                PromptMode.DEEP, PromptMode.SCENE -> 4
            },
            maxOutputTokens = outputTokenLimit(settings, mode, profile),
            adaptiveScale = scale,
        )
    }

    fun updatedMetrics(
        previous: GenerationMetrics,
        result: EngineReply,
        plan: PromptPlan,
    ): GenerationMetrics = GenerationMetrics(
        promptEvalTimeMs = result.promptEvalTimeMs,
        generateTimeMs = result.generateTimeMs,
        tokensPerSecond = result.tokensPerSecond,
        promptMode = plan.mode.name,
        selectedMessages = plan.selectedMessages,
        selectedMemories = plan.selectedMemories,
        selectedEpisodes = plan.selectedEpisodes,
        generatedAt = System.currentTimeMillis(),
        movingPromptEvalTimeMs = movingAverage(
            previous.movingPromptEvalTimeMs.takeIf { it > 0L } ?: previous.promptEvalTimeMs,
            result.promptEvalTimeMs,
        ),
        movingGenerateTimeMs = movingAverage(
            previous.movingGenerateTimeMs.takeIf { it > 0L } ?: previous.generateTimeMs,
            result.generateTimeMs,
        ),
        promptCharacters = plan.promptCharacters,
        contextBuildTimeMs = plan.contextBuildTimeMs,
        performanceProfile = plan.performanceProfile,
    )

    fun diagnostics(metrics: GenerationMetrics): String {
        if (metrics.generatedAt <= 0L) return "No reply timing data yet."
        val promptSeconds = metrics.promptEvalTimeMs / 1_000.0
        val generationSeconds = metrics.generateTimeMs / 1_000.0
        return buildString {
            append("Profile: ${normalizedProfile(metrics.performanceProfile)}")
            append("\nPrompt: ${"%.1f".format(promptSeconds)} s")
            append("\nGeneration: ${"%.1f".format(generationSeconds)} s")
            append("\nSpeed: ${"%.1f".format(metrics.tokensPerSecond)} tokens/s")
            append("\nPrompt size: ${metrics.promptCharacters} characters")
            append("\nContext build: ${metrics.contextBuildTimeMs} ms")
            append("\nSelected: ${metrics.selectedMessages} messages, ${metrics.selectedMemories} facts, ${metrics.selectedEpisodes} exchanges")
        }
    }

    private fun normalizedProfile(value: String): String = value.lowercase().let {
        if (it in setOf("fast", "balanced", "quality")) it else "balanced"
    }

    private fun adaptiveScale(previous: GenerationMetrics): Double {
        val sample = when {
            previous.movingPromptEvalTimeMs > 0L -> previous.movingPromptEvalTimeMs
            previous.promptEvalTimeMs > 0L -> previous.promptEvalTimeMs
            else -> 0L
        }
        return when {
            sample >= 45_000L -> 0.45
            sample >= 25_000L -> 0.60
            sample >= 12_000L -> 0.75
            sample >= 6_000L -> 0.88
            else -> 1.0
        }
    }

    private fun outputTokenLimit(
        settings: CharacterSettings,
        mode: PromptMode,
        profile: String,
    ): Int {
        val configured = settings.maxTokens.coerceIn(16, 320)
        val profileMaximum = when (profile) {
            "fast" -> minOf(configured, 120)
            "quality" -> configured
            else -> minOf(configured, 200)
        }
        val styleMaximum = when (settings.replyStyle) {
            "short" -> minOf(profileMaximum, 72)
            "long" -> profileMaximum
            else -> minOf(profileMaximum, 160)
        }
        return when (mode) {
            PromptMode.GREETING -> 12
            PromptMode.SHORT -> minOf(styleMaximum, 32)
            PromptMode.REFERENCE -> minOf(styleMaximum, if (profile == "quality") 140 else 96)
            PromptMode.NORMAL -> minOf(styleMaximum, if (profile == "quality") 180 else 120)
            PromptMode.DEEP -> minOf(profileMaximum, if (profile == "quality") 260 else 180)
            PromptMode.SCENE -> minOf(profileMaximum, if (profile == "quality") 200 else 140)
        }.coerceAtLeast(12)
    }

    private fun movingAverage(previous: Long, sample: Long): Long = when {
        sample <= 0L -> previous
        previous <= 0L -> sample
        else -> (previous * 0.70 + sample * 0.30).roundToInt().toLong()
    }
}
