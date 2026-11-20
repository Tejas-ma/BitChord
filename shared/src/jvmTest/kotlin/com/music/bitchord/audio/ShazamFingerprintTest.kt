package com.music.bitchord.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.sin

class ShazamFingerprintTest {

    /**
     * Something with the texture of music rather than a test tone: notes with
     * hard attacks, so their spectra change sharply from one frame to the next.
     *
     * A steady sine produces no peaks at all, by design: the spread spectrum at
     * bin-1 already covers the peak, so a test tone cannot stand in for a
     * recording here. The picker is looking for onsets.
     */
    private fun mixture(): ShortArray {
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

    @Test
    fun `notes with attacks produce peaks in the bands they fall in`() {
        val signature = ShazamFingerprint.signature(mixture())
        assertFalse(signature.isEmpty)
        // The mixture spans 320 Hz to 4.2 kHz, so peaks must survive at both ends
        // of the range the picker keeps.
        assertTrue(signature.bands[ShazamFingerprint.Band.B250_520.ordinal].isNotEmpty())
        assertTrue(signature.bands[ShazamFingerprint.Band.B3500_5500.ordinal].isNotEmpty())
    }

    @Test
    fun `a 3 kHz burst lands in the band that holds 3 kHz`() {
        val samples = ShortArray(SAMPLE_RATE * 2)
        for (i in samples.indices) {
            val burst = if (i % 4_000 < 1_500) 9_000.0 else 0.0
            samples[i] = (burst * sin(2.0 * PI * 3_000.0 * i / SAMPLE_RATE)).toInt().toShort()
        }
        val signature = ShazamFingerprint.signature(samples)
        val band = signature.bands[ShazamFingerprint.Band.B1450_3500.ordinal]
        assertTrue(band.isNotEmpty())
        // Bins are 0.12207 Hz wide; 3 kHz is bin 24576.
        assertTrue(band.any { it.correctedBin in 24_000..25_200 })
    }

    @Test
    fun `silence produces an empty signature and no match`() {
        val signature = ShazamFingerprint.signature(ShortArray(SAMPLE_RATE * 5))
        assertTrue(signature.isEmpty)
        assertEquals("", signature.let { if (it.isEmpty) "" else it.toUri() })
        assertFalse(PcmRecording(ShortArray(SAMPLE_RATE * 5)).hasSignal())
    }

    @Test
    fun `the uri is a base64 shazam signature with a valid header and crc`() {
        val uri = ShazamFingerprint.signature(mixture()).toUri()
        assertTrue(uri.startsWith("data:audio/vnd.shazam.sig;base64,"))
        val bytes = java.util.Base64.getDecoder()
            .decode(uri.removePrefix("data:audio/vnd.shazam.sig;base64,"))

        assertEquals(0xcafe2580.toInt(), u32(bytes, 0))
        assertEquals(0x94119c00.toInt(), u32(bytes, 12))
        assertEquals(bytes.size - 48, u32(bytes, 8))
        assertEquals(bytes.size - 48, u32(bytes, 52))

        val crc = CRC32().apply { update(bytes, 8, bytes.size - 8) }
        assertEquals(crc.value.toInt(), u32(bytes, 4))

        // Sample-rate id 3 (16 kHz) sits in the top five bits of the word at offset 28.
        assertEquals(3, u32(bytes, 28) ushr 27)
        // Sample count plus sample_rate * 0.24, which decodes back to the input length.
        val samples = u32(bytes, 40) - (SAMPLE_RATE * 0.24f).toInt()
        assertEquals(SAMPLE_RATE * 5, samples)
    }

    @Test
    fun `bands are written in order with the reference band ids`() {
        val uri = ShazamFingerprint.signature(mixture()).toUri()
        val bytes = java.util.Base64.getDecoder()
            .decode(uri.removePrefix("data:audio/vnd.shazam.sig;base64,"))

        var offset = 56
        val ids = mutableListOf<Int>()
        while (offset + 8 <= bytes.size) {
            val id = u32(bytes, offset)
            val size = u32(bytes, offset + 4)
            ids += id - 0x60030040
            offset += 8 + size + (4 - size % 4) % 4
        }
        assertEquals(offset, bytes.size)
        assertEquals(ids.sorted(), ids)
        assertTrue(ids.all { it in 0..3 })
    }

    @Test
    fun `peaks are written in non-decreasing frame order per band`() {
        val signature = ShazamFingerprint.signature(mixture())
        signature.bands.forEach { peaks ->
            assertTrue(peaks.zipWithNext().all { (a, b) -> b.fftPassNumber >= a.fftPassNumber })
        }
    }

    private fun u32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)
}
