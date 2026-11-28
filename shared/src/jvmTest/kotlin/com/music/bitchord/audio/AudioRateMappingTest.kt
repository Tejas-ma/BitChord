package com.music.bitchord.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class AudioRateMappingTest {

    @Test
    fun `resampling 48 kHz to 16 kHz preserves the one-second duration`() {
        val source = PcmRecording(ShortArray(48_000) { index ->
            (12_000.0 * sin(2.0 * PI * 1_000.0 * index / 48_000.0)).toInt().toShort()
        }, sampleRate = 48_000)

        val target = source.resampledTo(SAMPLE_RATE)

        assertEquals(SAMPLE_RATE, target.sampleRate)
        assertEquals(16_000, target.samples.size)
        assertEquals(1_000L, target.durationMs)
    }

    @Test
    fun `a 48 kHz fingerprint maps a 3 kHz burst to the 3 kHz band`() {
        val samples = ShortArray(48_000 * 2) { index ->
            val burst = if (index % 4_000 < 1_500) 9_000.0 else 0.0
            (burst * sin(2.0 * PI * 3_000.0 * index / 48_000.0)).toInt().toShort()
        }

        val signature = ShazamFingerprint.signature(samples, sampleRateHz = 48_000)
        val band = signature.bands[ShazamFingerprint.Band.B1450_3500.ordinal]

        assertTrue(band.isNotEmpty())
        // correctedBin is 64 × the FFT bin: 3000 * 2048 / 48000 * 64 = 8192.
        assertTrue(band.any { it.correctedBin in 7_900..8_500 })
    }

    @Test
    fun `resampling 48 kHz removes content above the new Nyquist`() {
        val tone12k = ShortArray(48_000) { index ->
            (9_000.0 * sin(2.0 * PI * 12_000.0 * index / 48_000.0)).toInt().toShort()
        }

        val target = PcmRecording(tone12k, sampleRate = 48_000).resampledTo(SAMPLE_RATE)
        val middle = target.samples.copyOfRange(2_000, target.samples.size - 2_000)

        // 12 kHz is above the 8 kHz Nyquist; a clean decimator attenuates it
        // rather than folding it down into the fingerprint bands.
        assertTrue(rms(middle) < 1_000.0)
    }

    @Test
    fun `resampling a lower rate longer than one second keeps the duration`() {
        val source = PcmRecording(ShortArray(8_000 * 10), sampleRate = 8_000)
        val target = source.resampledTo(SAMPLE_RATE)

        assertEquals(SAMPLE_RATE, target.sampleRate)
        assertEquals(160_000, target.samples.size)
        assertEquals(10_000L, target.durationMs)
    }

    private fun rms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (sample in samples) {
            val value = sample / 32_768.0
            sum += value * value
        }
        return sqrt(sum / samples.size) * 32_768.0
    }
}
