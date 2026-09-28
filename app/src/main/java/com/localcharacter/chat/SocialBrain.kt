package com.localcharacter.chat

/** Replay source-linked public interactions so edits/deletions cannot leave phantom affection. */
object SocialBrain {
    fun replay(world: SocialWorld, actor: SocialCharacter, pendingTarget: String, settings: CharacterSettings): BrainState {
        if (!settings.moodEnabled || !settings.automaticMoodEnabled) return actor.mood.copy()
        val ids = world.comments.filter { it.authorId == actor.id && it.generated }.map { it.parentId }.toMutableSet()
        if (pendingTarget.isNotBlank()) ids += pendingTarget
        val state = ConversationState(brain = actor.baselineMood.copy())
        val localSettings = settings.copy(memoryEnabled = false)
        world.comments.filter { it.authorId == "self" && it.id in ids }.takeLast(500).sortedBy { it.createdAt }.forEach { c ->
            val input = ChatMessage(id = c.id, role = "user", text = c.text, timestamp = c.createdAt)
            state.messages += input
            BrainEngine.prepareForReply(localSettings, state, input, c.createdAt)
        }
        return state.brain
    }
}
