package com.localcharacter.chat

/** Decisions are deterministic and testable; the LLM cannot override a refusal or share setting. */
object PhotoSharePolicy {
    private val request = Regex("""(?iu)\b(?:send|show|share|see|trimite|arata|arată|vreau)\b[^\n]{0,75}\b(?:photo|picture|pic|selfie|image|poza|poză|fotografie|imagine)\b|^\s*(?:a |one |another )?(?:photo|pic|selfie|poza|poză)\s*\??$""")
    private val refusal = Regex("""(?iu)\b(?:can't|cannot|won't|not now|rather not|maybe later|another time|not yet|not comfortable|don't want|do not want|no photos|nu vreau|nu pot|nu acum)\b""")
    private val noRequest = Regex("""(?iu)\b(?:don't|do not|stop|no need|never|nu)\b[^\n]{0,45}\b(?:send|show|share|photo|picture|pic|trimite|poza|poză)\b""")
    private val generic = setOf("send", "show", "share", "see", "me", "your", "you", "my", "please", "photo", "photos", "picture", "pictures", "pic", "pics", "image", "one", "another", "can", "could", "would", "now", "of", "a", "the", "some", "from", "want", "like", "to", "get", "trimite", "poza", "poze", "imagine", "fotografie", "vreau", "mi")
    fun prompt(settings: PhotoSharingSettings): String = when (settings.mode) {
        "never" -> "Photo sharing is OFF. Do not emit photo markers or promise a photo."
        "contextual" -> "Only existing relevant gallery images may be attached using [[SEND_PHOTO:matching tag]]. Do not attach an unrelated image, repeat a recent photo, bypass a refusal, invent an image or claim to inspect pixels. Respect public/private sharing."
        else -> "Attach an existing gallery image only after the user explicitly requests a photo and you accept: [[SEND_PHOTO:matching tag]]. No unsolicited photos, invented images, pixel inspection claims or promises without an attachment."
    }
    fun claimsAttachment(text: String): Boolean = Regex("""(?iu)\b(?:here(?: is|'s|’s)|i(?: have|'ve|’ve)? attached|sending you)\b[^\n]{0,45}\b(?:photo|picture|pic|selfie|image)\b""").containsMatchIn(text)
    fun choose(settings: PhotoSharingSettings, photos: List<CharacterPhoto>, userText: String, reply: String,
               markerTag: String = "", public: Boolean = false, recentPaths: List<String> = emptyList(),
               turnsSincePhoto: Int = Int.MAX_VALUE): CharacterPhoto? {
        if (settings.mode == "never" || (public && !settings.publicReplies)) return null
        if (refusal.containsMatchIn(reply) || noRequest.containsMatchIn(userText)) return null
        if (Regex("""(?iu)^\s*(?:no|nope|nu)[.!?, …]*$""").matches(reply)) return null
        val asked = request.containsMatchIn(userText)
        if (asked && markerTag.isBlank()) {
            val affirmative = Regex("""(?iu)\b(?:sure|okay|ok|yes|of course|certainly|here|da|sigur|uite)\b""").containsMatchIn(reply)
            if (!affirmative || reply.trimEnd().endsWith('?')) return null
        }
        if (!asked && (settings.mode != "contextual" || markerTag.isBlank())) return null
        if (!asked && turnsSincePhoto < settings.cooldownTurns) return null
        val available = photos.filterNot { it.path in recentPaths }
        if (available.isEmpty()) return null
        val wanted = MemoryManager.keywords(userText).minus(generic)
        val marker = MemoryManager.keywords(markerTag).minus(generic)
        val terms = if (asked && wanted.isNotEmpty()) wanted else marker
        if (!asked && terms.isEmpty()) return null
        if (terms.isEmpty()) return available.firstOrNull()
        return available.map { photo ->
            val tags = MemoryManager.keywords(photo.tags.joinToString(" "))
            val info = MemoryManager.keywords("${photo.caption} ${photo.description}")
            photo to (terms.intersect(tags).size * 5 + terms.intersect(info).size * 2)
        }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }
}
