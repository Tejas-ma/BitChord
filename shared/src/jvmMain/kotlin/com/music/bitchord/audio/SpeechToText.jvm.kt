package com.music.bitchord.audio

/**
 * The desktop has no built-in speech recogniser that needs no key, so voice
 * search reports itself unavailable there and the UI offers Identify Song
 * instead. Kept as an actual rather than a compile-time flag so the shared UI
 * can ask the same question on both platforms.
 */
actual fun speechToText(): SpeechToText = UnavailableSpeechToText

private object UnavailableSpeechToText : SpeechToText {
    override val isAvailable: Boolean = false

    override suspend fun listen(
        timeoutMs: Int,
        onPartial: (String) -> Unit,
        onRms: (Float) -> Unit,
    ): String? = null
}
