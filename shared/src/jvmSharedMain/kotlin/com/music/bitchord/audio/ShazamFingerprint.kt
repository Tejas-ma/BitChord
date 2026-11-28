package com.music.bitchord.audio

import java.util.Base64
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shazam's acoustic signature, generated the way SongRec's reference
 * fingerprinter does — the format Shazam's own clients send, and the only one
 * its discovery endpoint accepts.
 *
 * The pipeline: a 2048-sample Hann-windowed FFT every hop that keeps a 125 Hz
 * frame rate (128 samples at the normal 16 kHz), frequency-domain peak
 * spreading, peak picking against a neighbourhood spread in both time and
 * frequency, and then the peaks are packed into a
 * `data:audio/vnd.shazam.sig;base64,…` URI with the container header and CRC-32
 * the endpoint validates. The hop and the bin-to-Hz conversion both use the
 * supplied rate, so a non-16 kHz buffer is not silently given 16 kHz time and
 * frequency axes.
 *
 * No key, no account and no service of ours: the whole recogniser is this file
 * and one POST.
 */
object ShazamFingerprint {

    /** The four bands Shazam stores peaks in, in the order the payload writes them. */
    enum class Band(val fromHz: Int, val toHz: Int) {
        B250_520(250, 519),
        B520_1450(520, 1449),
        B1450_3500(1450, 3499),
        B3500_5500(3500, 5500),
    }

    /** One peak: the frame it was found in, its magnitude, and its interpolated bin. */
    data class Peak(
        val fftPassNumber: Int,
        val magnitude: Int,
        val correctedBin: Int,
    )

    /** A decoded signature: the peaks per band, and how many samples they came from. */
    data class Signature(
        val sampleRateHz: Int,
        val numberSamples: Int,
        val bands: List<List<Peak>>,
    ) {
        val isEmpty: Boolean get() = bands.all(List<Peak>::isEmpty)

        /** The `data:audio/vnd.shazam.sig;base64,…` URI the discovery endpoint takes. */
        fun toUri(): String = DATA_URI_PREFIX + Base64.getEncoder().encodeToString(toBinary())
    }

    private const val FFT_SIZE = 2048
    /** Frames per second the reference fingerprinter uses at every rate. */
    private const val FRAME_RATE_HZ = 125
    private const val BINS = FFT_SIZE / 2 + 1
    private const val RING = 256
    private const val PEAK_AGE = 46
    private const val SPREAD_AGE = 49
    private const val DATA_URI_PREFIX = "data:audio/vnd.shazam.sig;base64,"

    /** The 48-byte header and the two-word band preamble that follow it. */
    private const val HEADER_BYTES = 56

    /**
     * Builds the signature of [samples] at [sampleRateHz].
     *
     * The normal path feeds this a [PcmRecording] resampled to [SAMPLE_RATE];
     * the rate argument and the derived hop are still honoured so the
     * fingerprint is correct if a caller has a buffer at another rate.
     *
     * The buffer is processed whole; callers hand over the five or six seconds
     * worth recording, rather than SongRec's twelve, because that is what this
     * app captures.
     */
    fun signature(samples: ShortArray, sampleRateHz: Int = SAMPLE_RATE): Signature {
        val window = HANNING
        val fft = RealFft(FFT_SIZE)
        // At 16 kHz this is exactly the reference's 128-sample hop. At another
        // rate it keeps the same 8 ms frame spacing, so the frame numbers in
        // the payload still describe real time rather than a shortened axis.
        val hop = (sampleRateHz.toDouble() / FRAME_RATE_HZ).roundToInt().coerceAtLeast(1)

        // Ring of the last 256 frames' power spectra, and of their spread
        // versions, exactly as the reference does: peaks are picked against
        // frames that are 46 and 49 frames old, so the two rings are read that
        // far back once enough frames exist.
        val ring = Array(RING) { FloatArray(BINS) }
        val spread = Array(RING) { FloatArray(BINS) }
        val bandPeaks = Array(Band.entries.size) { mutableListOf<Peak>() }

        val reordered = FloatArray(FFT_SIZE)
        val history = IntArray(FFT_SIZE)
        var historyIndex = 0
        var spectra = 0
        var spreadIndex = 0

        var offset = 0
        while (offset + hop <= samples.size) {
            for (i in 0 until hop) history[(historyIndex + i) and (FFT_SIZE - 1)] = samples[offset + i].toInt()
            historyIndex = (historyIndex + hop) and (FFT_SIZE - 1)

            // Oldest sample first, times the Hann window.
            for (i in 0 until FFT_SIZE) {
                reordered[i] = history[(i + historyIndex) and (FFT_SIZE - 1)].toFloat() * window[i]
            }
            val power = ring[spectra % RING]
            fft.powerSpectrum(reordered, power)

            // Spread in frequency: each bin takes the max of itself and the two
            // above it, so a peak stands out from its own skirt.
            val spreadRow = spread[spreadIndex % RING]
            power.copyInto(spreadRow)
            for (position in 0..BINS - 3) {
                spreadRow[position] = max(spreadRow[position], spreadRow[position + 1])
                spreadRow[position] = max(spreadRow[position], spreadRow[position + 2])
            }
            // Spread in time, forwards only, over the frames 1, 3 and 6 back.
            for (position in 0 until BINS) {
                for (former in SPREAD_BACK) {
                    val other = spread[(spreadIndex - former) and (RING - 1)]
                    other[position] = max(other[position], spreadRow[position])
                }
            }

            spectra++
            spreadIndex = (spreadIndex + 1) and (RING - 1)

            if (spectra >= PEAK_AGE) {
                pickPeaks(ring, spread, spreadIndex, spectra, bandPeaks, sampleRateHz)
            }
            offset += hop
        }

        return Signature(
            sampleRateHz = sampleRateHz,
            numberSamples = samples.size,
            bands = bandPeaks.map { it.toList() },
        )
    }

    private fun pickPeaks(
        ring: Array<FloatArray>,
        spread: Array<FloatArray>,
        spreadIndex: Int,
        spectra: Int,
        out: Array<MutableList<Peak>>,
        sampleRateHz: Int,
    ) {
        // The spectrum as it was 46 frames ago, against the spread one 49
        // frames ago: a peak has to be a local maximum among its neighbours in
        // both, which is what keeps vibrato and harmonic skirts out.
        val plain = ring[(spreadIndex - PEAK_AGE) and (RING - 1)]
        val neighbourhood = spread[(spreadIndex - SPREAD_AGE) and (RING - 1)]

        for (bin in 10..BINS - 11) {
            val magnitude = plain[bin]
            if (magnitude < 1f / 64f) continue
            if (magnitude < neighbourhood[bin - 1]) continue

            var maxNeighbour = 0f
            for (delta in FREQUENCY_NEIGHBOURS) {
                maxNeighbour = max(maxNeighbour, neighbourhood[bin + delta])
            }
            if (magnitude <= maxNeighbour) continue

            var maxOther = maxNeighbour
            for (delta in TIME_NEIGHBOURS) {
                val other = spread[(spreadIndex + delta) and (RING - 1)]
                maxOther = max(maxOther, other[bin - 1])
            }
            if (magnitude <= maxOther) continue

            // Interpolate the peak's true position between neighbouring bins,
            // so a tone that falls between bins is not quantised to one.
            // ln() is Double-only in kotlin.math; the reference works in f32, so
            // cast at the call and come straight back.
            fun level(power: Float): Float =
                ln(power.toDouble()).coerceAtLeast(1.0 / 64.0).toFloat() * 1477.3f + 6144f

            val peakMagnitude = level(magnitude)
            val before = level(plain[bin - 1])
            val after = level(plain[bin + 1])
            val variation = peakMagnitude * 2f - before - after
            if (variation <= 0f) continue
            val correction = (after - before) * 32f / variation
            val correctedBin = (bin shl 6) + correction.toInt()

            // correctedBin is 64× the FFT bin, so divide the bin width by 64
            // as well. The rate is the buffer's own; a hard-coded 16 kHz here
            // shifted every band when a device captured at 44.1 or 48 kHz.
            val hz = correctedBin * (sampleRateHz.toFloat() / FFT_SIZE / 64f)
            val band = Band.entries.firstOrNull { hz >= it.fromHz && hz <= it.toHz } ?: continue
            out[band.ordinal] += Peak(
                fftPassNumber = spectra - PEAK_AGE,
                magnitude = peakMagnitude.toInt().coerceIn(0, 65_535),
                correctedBin = correctedBin.coerceIn(0, 65_535),
            )
        }
    }

    /** The signature as the bytes inside the base64 URI, header and CRC included. */
    fun Signature.toBinary(): ByteArray {
        val out = BinaryWriter()
        // RawSignatureHeader: magic, CRC-32 (written last), size-minus-header,
        // second magic, three void words, sample-rate id shifted left 27, two
        // more void words, samples + rate*0.24, and the constant tail.
        out.u32(0xcafe2580.toInt())
        out.u32(0)
        out.u32(0)
        out.u32(0x94119c00.toInt())
        repeat(3) { out.u32(0) }
        out.u32((sampleRateId(sampleRateHz) shl 27))
        repeat(2) { out.u32(0) }
        out.u32(numberSamples + (sampleRateHz * 0.24f).toInt())
        out.u32((15 shl 19) + 0x40000)

        out.u32(0x40000000)
        out.u32(0)
        bands.forEachIndexed { index, peaks ->
            if (peaks.isEmpty()) return@forEachIndexed
            val payload = BinaryWriter()
            var previousPass = 0
            for (peak in peaks) {
                val delta = peak.fftPassNumber - previousPass
                if (delta >= 255) {
                    payload.u8(0xff)
                    payload.u32(peak.fftPassNumber)
                } else {
                    payload.u8(delta)
                }
                payload.u16(peak.magnitude)
                payload.u16(peak.correctedBin)
                previousPass = peak.fftPassNumber
            }
            val bytes = payload.toByteArray()
            out.u32(0x60030040 + index)
            out.u32(bytes.size)
            out.bytes(bytes)
            repeat((4 - bytes.size % 4) % 4) { out.u8(0) }
        }

        val buffer = out.toByteArray()
        // size_minus_header: the header is 48 bytes.
        putU32(buffer, 8, buffer.size - 48)
        putU32(buffer, 52, buffer.size - 48)
        val crc = Crc32.of(buffer, from = 8, until = buffer.size)
        putU32(buffer, 4, crc)
        return buffer
    }

    /**
     * The signature [uri] carries, read back: what [toUri] wrote.
     *
     * The header and the CRC are checked rather than assumed, so a payload
     * mangled on the way — a truncated body, a half-written capture — is refused
     * instead of decoded into nonsense. A band the writer skipped comes back
     * empty, and each chunk is placed by its own id rather than by its order.
     */
    fun decode(uri: String): Signature {
        require(uri.startsWith(DATA_URI_PREFIX)) { "Not a Shazam signature URI" }
        val bytes = Base64.getDecoder().decode(uri.removePrefix(DATA_URI_PREFIX))
        require(bytes.size >= HEADER_BYTES) { "Signature is shorter than its header" }
        require(u32(bytes, 0) == 0xcafe2580.toInt() && u32(bytes, 12) == 0x94119c00.toInt()) {
            "Signature header does not match"
        }
        require(u32(bytes, 4) == Crc32.of(bytes, from = 8)) { "Signature CRC does not match" }

        val rate = sampleRateHz(u32(bytes, 28) ushr 27)
        val bands = List(Band.entries.size) { mutableListOf<Peak>() }
        var at = HEADER_BYTES
        while (at + 8 <= bytes.size) {
            val band = u32(bytes, at) - 0x60030040
            val size = u32(bytes, at + 4)
            require(band in bands.indices && size >= 0 && at + 8 + size <= bytes.size) {
                "Signature holds a band chunk that does not fit"
            }
            readPeaks(bytes, at + 8, size, bands[band])
            at += 8 + size + (4 - size % 4) % 4
        }
        return Signature(
            sampleRateHz = rate,
            // Written as the count plus a quarter-second of rate; that is what comes back.
            numberSamples = u32(bytes, 40) - (rate * 0.24f).toInt(),
            bands = bands,
        )
    }

    /** The peaks in one band chunk: a delta per frame, escaped to an absolute one when it overflows. */
    private fun readPeaks(bytes: ByteArray, from: Int, size: Int, into: MutableList<Peak>) {
        val end = from + size
        var at = from
        var previousPass = 0
        while (at < end) {
            val lead = bytes[at].toInt() and 0xFF
            at += 1
            val pass = if (lead == 0xFF) {
                if (at + 4 > end) return
                u32(bytes, at).also { at += 4 }
            } else {
                previousPass + lead
            }
            if (at + 4 > end) return
            into += Peak(
                fftPassNumber = pass,
                magnitude = u16(bytes, at),
                correctedBin = u16(bytes, at + 2),
            )
            at += 4
            previousPass = pass
        }
    }

    private fun u16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, at: Int): Int =
        u16(bytes, at) or (u16(bytes, at + 2) shl 16)

    private fun sampleRateHz(id: Int): Int = when (id) {
        1 -> 8_000
        2 -> 11_025
        4 -> 32_000
        5 -> 44_100
        6 -> 48_000
        else -> SAMPLE_RATE
    }

    private fun sampleRateId(rate: Int): Int = when (rate) {
        8_000 -> 1
        11_025 -> 2
        16_000 -> 3
        32_000 -> 4
        44_100 -> 5
        48_000 -> 6
        else -> 3
    }

    private fun putU32(target: ByteArray, at: Int, value: Int) {
        target[at] = value.toByte()
        target[at + 1] = (value ushr 8).toByte()
        target[at + 2] = (value ushr 16).toByte()
        target[at + 3] = (value ushr 24).toByte()
    }

    /** Frames whose spread a peak is compared against, relative to the current one. */
    private val SPREAD_BACK = intArrayOf(1, 3, 6)

    /** Neighbouring bins in the 49-frame-old spread spectrum. */
    private val FREQUENCY_NEIGHBOURS = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)

    /** Frames around the current one — some ahead, most behind, as the reference has them. */
    private val TIME_NEIGHBOURS = intArrayOf(-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249)

    /** `0.5 * (1 - cos(2πn / 2047))`, the Hann window SongRec's table holds. */
    private val HANNING = FloatArray(FFT_SIZE) { index ->
        (0.5 * (1.0 - cos(2.0 * PI * index / (FFT_SIZE - 1)))).toFloat()
    }

    /** A tiny growable little-endian byte writer; avoids a dependency for 20 lines. */
    private class BinaryWriter {
        private var buffer = ByteArray(256)
        private var size = 0

        fun u8(value: Int) {
            ensure(1)
            buffer[size++] = value.toByte()
        }

        fun u16(value: Int) {
            ensure(2)
            buffer[size++] = value.toByte()
            buffer[size++] = (value ushr 8).toByte()
        }

        fun u32(value: Int) {
            ensure(4)
            repeat(4) { shift -> buffer[size++] = (value ushr (shift * 8)).toByte() }
        }

        fun bytes(value: ByteArray) {
            ensure(value.size)
            value.copyInto(buffer, size)
            size += value.size
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)

        private fun ensure(count: Int) {
            if (size + count <= buffer.size) return
            var next = buffer.size * 2
            while (next < size + count) next *= 2
            buffer = buffer.copyOf(next)
        }
    }

    private object Crc32 {
        private val table = IntArray(256) { index ->
            var value = index
            repeat(8) { value = if (value and 1 != 0) 0xEDB88320.toInt() xor (value ushr 1) else value ushr 1 }
            value
        }

        fun of(data: ByteArray, from: Int = 0, until: Int = data.size): Int {
            var crc = -1
            for (index in from until until) {
                crc = table[(crc xor data[index].toInt()) and 0xFF] xor (crc ushr 8)
            }
            return crc.inv()
        }
    }

    /**
     * A real-input radix-2 FFT and its magnitude spectrum.
     *
     * Private and dependency-free: the signature is the only caller, it always
     * asks for 2048 points, and a table-driven Cooley-Tukey is a page of code.
     */
    private class RealFft(private val size: Int) {
        private val levels = Integer.numberOfTrailingZeros(size)
        private val cosTable = DoubleArray(size / 2) { cos(2.0 * PI * it / size) }
        private val sinTable = DoubleArray(size / 2) { kotlin.math.sin(2.0 * PI * it / size) }
        private val re = DoubleArray(size)
        private val im = DoubleArray(size)

        fun powerSpectrum(windowed: FloatArray, out: FloatArray) {
            for (i in 0 until size) {
                re[i] = windowed[i].toDouble()
                im[i] = 0.0
            }
            transform()
            for (bin in out.indices) {
                out[bin] = ((re[bin] * re[bin] + im[bin] * im[bin]) / (1 shl 17).toDouble())
                    .toFloat()
                    .coerceAtLeast(1e-10f)
            }
        }

        private fun transform() {
            // Bit-reversed permutation.
            var j = 0
            for (i in 1 until size) {
                var bit = size shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j or bit
                if (i < j) {
                    re[i] = re[j].also { re[j] = re[i] }
                    im[i] = im[j].also { im[j] = im[i] }
                }
            }
            var step = 2
            while (step <= size) {
                val half = step / 2
                val tableStep = size / step
                for (start in 0 until size step step) {
                    var k = 0
                    for (offset in 0 until half) {
                        val wRe = cosTable[k]
                        val wIm = -sinTable[k]
                        val a = start + offset
                        val b = a + half
                        val tRe = re[b] * wRe - im[b] * wIm
                        val tIm = re[b] * wIm + im[b] * wRe
                        re[b] = re[a] - tRe
                        im[b] = im[a] - tIm
                        re[a] += tRe
                        im[a] += tIm
                        k += tableStep
                    }
                }
                step = step shl 1
            }
        }
    }
}
