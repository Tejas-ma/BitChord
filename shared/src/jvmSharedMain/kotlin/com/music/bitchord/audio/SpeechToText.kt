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
     *
     * [onPartial] carries the best transcription so far while the listener is
     * still speaking, and [onRms] the input level as it changes (0..1), so the
     * sheet can show the words appearing and a waveform that moves with the
     * voice rather than a spinner that says nothing.
     */
    suspend fun listen(
        timeoutMs: Int = LISTEN_TIMEOUT_MS,
        onPartial: (String) -> Unit = {},
        onRms: (Float) -> Unit = {},
    ): String?

    /**
     * Stops the recogniser now, releasing the microphone.
     *
     * Cancelling the coroutine alone leaves the platform recogniser alive for
     * as long as it takes the cancellation to travel, and the green microphone
     * indicator with it; a dismissed sheet calls this first. Safe to call when
     * nothing is listening.
     */
    fun cancel() {}
}

/** Long enough to say a song name, short enough that a silent room gives up. */
const val LISTEN_TIMEOUT_MS: Int = 8_000

/** The platform's recogniser, or one that reports itself unavailable. */
expect fun speechToText(): SpeechToText
