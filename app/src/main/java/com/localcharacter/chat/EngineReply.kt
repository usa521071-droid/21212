package com.localcharacter.chat

data class EngineReply(
    val text: String,
    val tokensPerSecond: Float,
    val promptEvalTimeMs: Long,
    val generateTimeMs: Long,
    val threadCount: Int,
    val tokensGenerated: Int = 0,
)
