package com.music.bitchord.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.sin

class ShazamFingerprintTest {

    @Test
    fun `notes with attacks produce peaks in the bands they fall in`() {
        val signature = ShazamFingerprint.signature(onsetRichMixture())
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
        val uri = ShazamFingerprint.signature(onsetRichMixture()).toUri()
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
        val uri = ShazamFingerprint.signature(onsetRichMixture()).toUri()
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
        val signature = ShazamFingerprint.signature(onsetRichMixture())
        signature.bands.forEach { peaks ->
            assertTrue(peaks.zipWithNext().all { (a, b) -> b.fftPassNumber >= a.fftPassNumber })
        }
    }

    @Test
    fun `the base64 payload decodes back to the bands that were written`() {
        val written = ShazamFingerprint.signature(onsetRichMixture())
        val read = ShazamFingerprint.decode(written.toUri())

        assertEquals(written.sampleRateHz, read.sampleRateHz)
        assertEquals(written.numberSamples, read.numberSamples)
        // Every peak, in its band, with the frame and bin it was written at.
        assertEquals(written.bands, read.bands)
        assertTrue(read.bands.sumOf { it.size } > 0)
    }

    @Test
    fun `a frame gap too wide for one byte is escaped and comes back at its own frame`() {
        val written = ShazamFingerprint.Signature(
            sampleRateHz = SAMPLE_RATE,
            numberSamples = SAMPLE_RATE * 5,
            bands = listOf(
                listOf(
                    ShazamFingerprint.Peak(fftPassNumber = 0, magnitude = 100, correctedBin = 24_000),
                    ShazamFingerprint.Peak(fftPassNumber = 1_000, magnitude = 65_535, correctedBin = 7),
                ),
                emptyList(),
                emptyList(),
                emptyList(),
            ),
        )

        assertEquals(written, ShazamFingerprint.decode(written.toUri()))
    }

    @Test
    fun `an empty signature round-trips to four empty bands`() {
        val written = ShazamFingerprint.signature(ShortArray(SAMPLE_RATE * 5))
        val read = ShazamFingerprint.decode(written.toUri())

        assertEquals(4, read.bands.size)
        assertTrue(read.isEmpty)
        assertEquals(SAMPLE_RATE * 5, read.numberSamples)
    }

    @Test
    fun `a payload whose crc no longer matches is refused`() {
        val prefix = "data:audio/vnd.shazam.sig;base64,"
        val uri = ShazamFingerprint.signature(onsetRichMixture()).toUri()
        val bytes = java.util.Base64.getDecoder().decode(uri.removePrefix(prefix))
        bytes[bytes.size - 1] = (bytes.last().toInt() xor 0x01).toByte()

        val corrupted = prefix + java.util.Base64.getEncoder().encodeToString(bytes)
        try {
            ShazamFingerprint.decode(corrupted)
            fail("A signature with a broken CRC decoded")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("CRC"))
        }
    }

    private fun u32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)
}
