package com.music.bitchord.audio

import kotlin.math.PI
import kotlin.math.sin

/**
 * Something with the texture of music rather than a test tone: notes with hard
 * attacks, so their spectra change sharply from one frame to the next.
 *
 * A steady sine produces no peaks at all, by design: the spread spectrum at
 * bin-1 already covers the peak, so a test tone cannot stand in for a recording
 * here. The picker is looking for onsets.
 *
 * Shared by the fingerprinter and client tests, so the signature whose bands
 * are read back is built from the same five seconds the picker is asserted on.
 */
internal fun onsetRichMixture(): ShortArray {
    val hz = doubleArrayOf(320.0, 440.0, 660.0, 880.0, 1_100.0, 1_320.0, 1_760.0, 2_200.0, 2_640.0, 3_520.0, 4_180.0)
    val samples = ShortArray(SAMPLE_RATE * 5)
    val noteLength = SAMPLE_RATE / 3
    for (i in samples.indices) {
        val note = i / noteLength
        val at = i % noteLength
        val frequency = hz[note % hz.size]
        // 60 ms attack-decay over a sustain: an onset the picker can see.
        val envelope = when {
            at < 900 -> at / 900.0 * 12_000
            else -> 12_000.0 * (1.0 - at.toDouble() / noteLength) * 0.5
        }
        samples[i] = (envelope * sin(2.0 * PI * frequency * i / SAMPLE_RATE)).toInt().toShort()
    }
    return samples
}
