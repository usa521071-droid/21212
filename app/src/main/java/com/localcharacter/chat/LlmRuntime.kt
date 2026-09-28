package com.localcharacter.chat

object LlmRuntime {
    val engine: LocalLlmEngine by lazy { LocalLlmEngine() }

    @Volatile
    var appVisible: Boolean = false
}
