package com.localcharacter.chat

import java.io.File

object CharacterPhotoSelector {
    fun available(settings: CharacterSettings): List<CharacterPhoto> {
        val described = settings.characterPhotos
            .filter { photo -> File(photo.path).let { it.exists() && it.isFile && it.length() > 0L } }
        val known = described.mapTo(mutableSetOf()) { it.path }
        val legacy = settings.characterPhotoPaths
            .filterNot { it in known }
            .map(::File)
            .filter { it.exists() && it.isFile && it.length() > 0L }
            .map { CharacterPhoto(path = it.absolutePath) }
        return described + legacy
    }

    fun select(
        settings: CharacterSettings,
        query: String,
        interactionCount: Int,
        messageCount: Int,
    ): CharacterPhoto? {
        val photos = available(settings)
        if (photos.isEmpty()) return null
        val normalizedQuery = query.lowercase()
        val queryTokens = MemoryManager.keywords(normalizedQuery)

        val scored = photos.mapIndexed { index, photo ->
            val searchable = listOf(
                photo.caption,
                photo.description,
                photo.tags.joinToString(" "),
            ).filter(String::isNotBlank).joinToString(" ").lowercase()
            val photoTokens = MemoryManager.keywords(searchable)
            val overlap = queryTokens.intersect(photoTokens).size
            val exactTag = photo.tags.count { tag ->
                tag.isNotBlank() && normalizedQuery.contains(tag.lowercase())
            }
            val score = overlap * 5 + exactTag * 8
            Triple(photo, score, index)
        }
        val best = scored.maxWithOrNull(
            compareBy<Triple<CharacterPhoto, Int, Int>> { it.second }
                .thenBy { -it.third },
        )
        if (best != null && best.second > 0) return best.first

        val index = (interactionCount + messageCount).mod(photos.size)
        return photos[index]
    }
}
