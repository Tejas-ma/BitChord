package com.music.bitchord.audio

import kotlin.math.abs

/**
 * Sixteen-bit mono PCM at 16 kHz, the rate Shazam's fingerprinter and every
 * speech recogniser in the app are built around.
 *
 * The platform supplies the microphone (see the Android and desktop actuals);
 * everything above this line works on the samples alone, which is what lets
 * the fingerprinter be exercised by a test with a synthetic buffer.
 */
expect class PcmRecorder() {

    /**
     * Whether this platform can record at all. False where the build has no
     * microphone backend, before any permission has been asked for.
     */
    val isAvailable: Boolean

    /**
     * Records [durationMs] of microphone audio and returns it as 16 kHz mono
     * samples, or null when the microphone could not be opened (no permission,
     * no device, another app holding it).
     *
     * Runs for its whole duration rather than stopping early on silence: an
     * identified song is often quiet at the start.
     */
    suspend fun record(durationMs: Int = CAPTURE_MS): ShortArray?
}

/** How long one capture lasts — the window Shazam's own clients send. */
const val CAPTURE_MS: Int = 5_000

/** The rate every sample this package produces is at. */
const val SAMPLE_RATE: Int = 16_000

/** Whether [samples] hold anything but silence, so a dead microphone is told apart from a quiet room. */
fun PcmRecording.hasSignal(): Boolean {
    if (samples.isEmpty()) return false
    var sum = 0.0
    for (sample in samples) sum += abs(sample.toInt())
    val mean = sum / samples.size
    // About -50 dBFS: a quiet room still has a noise floor above this, and a
    // muted or unplugged microphone sits at exactly zero.
    return mean > 300.0
}

/** One microphone capture, with the rate its [samples] are at. */
data class PcmRecording(
    val samples: ShortArray,
    val sampleRate: Int = SAMPLE_RATE,
) {
    /** How long the capture lasts, for the `samplems` Shazam is sent. */
    val durationMs: Long get() = samples.size * 1_000L / sampleRate
}
