package com.localcharacter.chat

data class ConversationEditResult(
    val deletedMessageIds: Set<String>,
    val removedCount: Int,
)

object ConversationEditor {
    fun deleteAssistantMessage(
        settings: CharacterSettings,
        state: ConversationState,
        messageId: String,
    ): ConversationEditResult {
        val index = state.messages.indexOfFirst { it.id == messageId && it.role == "assistant" }
        return deleteRange(settings, state, index, index)
    }

    fun deleteUserTurn(
        settings: CharacterSettings,
        state: ConversationState,
        messageId: String,
    ): ConversationEditResult {
        val index = state.messages.indexOfFirst { it.id == messageId && it.role == "user" }
        if (index < 0) return ConversationEditResult(emptySet(), 0)
        var end = index
        var cursor = index + 1
        while (cursor < state.messages.size && state.messages[cursor].role == "assistant") {
            end = cursor
            cursor++
        }
        return deleteRange(settings, state, index, end)
    }

    fun deleteFromMessage(
        settings: CharacterSettings,
        state: ConversationState,
        messageId: String,
    ): ConversationEditResult {
        val index = state.messages.indexOfFirst { it.id == messageId }
        if (index < 0) return ConversationEditResult(emptySet(), 0)
        return deleteRange(settings, state, index, state.messages.lastIndex)
    }

    private fun deleteRange(
        settings: CharacterSettings,
        state: ConversationState,
        startIndex: Int,
        endIndex: Int,
    ): ConversationEditResult {
        if (startIndex !in state.messages.indices) {
            return ConversationEditResult(emptySet(), 0)
        }
        val safeEnd = endIndex.coerceIn(startIndex, state.messages.lastIndex)
        val deleted = state.messages.subList(startIndex, safeEnd + 1).toList()
        val deletedIds = deleted.mapTo(linkedSetOf()) { it.id }
        state.messages.subList(startIndex, safeEnd + 1).clear()
        MemoryManager.purgeDeletedReferences(state, deletedIds)
        SceneDirector.rebuildFromHistory(settings, state)
        BrainEngine.rebuildFromHistory(settings, state)
        return ConversationEditResult(deletedIds, deleted.size)
    }
}
