package com.music.bitchord.audio

/**
 * Speech-to-text for voice search.
 *
 * Android answers with the platform recogniser — on-device, no key, no account,
 * and the same one the keyboard's microphone key uses. The desktop has no
 * equivalent, so its actual says so and the UI offers Identify Song alone.
 */
interface SpeechToText {
    val isAvailable: Boolean

    /**
     * Listens once and returns what was said, or null when nothing was heard
     * or the recogniser was cancelled.
     */
    suspend fun listen(timeoutMs: Int = LISTEN_TIMEOUT_MS): String?
}

/** Long enough to say a song name, short enough that a silent room gives up. */
const val LISTEN_TIMEOUT_MS: Int = 8_000

/** The platform's recogniser, or one that reports itself unavailable. */
expect fun speechToText(): SpeechToText
