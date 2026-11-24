package com.music.bitchord.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.roundToInt

/**
 * The desktop's microphone, read through `javax.sound.sampled`.
 *
 * The requested line is 16-bit mono little-endian, but the opened line's own
 * [AudioFormat] is the authority: some mixers refuse 16 kHz and hand back a
 * 44.1/48 kHz line instead. The returned [PcmRecording] carries that actual
 * rate, so the recognisers resample from it rather than mislabelling it.
 *
 * A machine with no capture device (or no permission to use one) answers
 * [isAvailable] false rather than throwing.
 */
actual class PcmRecorder actual constructor() {

    actual val isAvailable: Boolean
        get() = RATES.any { rate ->
            runCatching { AudioSystem.isLineSupported(lineInfo(rate)) }.getOrDefault(false)
        }

    /** The line in flight, if any, so [stop] can close it from another thread. */
    private var active: TargetDataLine? = null

    actual fun stop() {
        val line = active ?: return
        active = null
        runCatching { line.stop() }
        runCatching { line.close() }
    }

    actual suspend fun record(durationMs: Int, onAmplitude: (Float) -> Unit): PcmRecording? =
        withContext(Dispatchers.IO) {
            if (durationMs <= 0) return@withContext null
            val line = openLine() ?: return@withContext null
            active = line
            val format = line.format
            val rate = format.sampleRate.roundToInt()
            val channels = format.channels
            val bytesPerSample = format.sampleSizeInBits / 8
            val frameSize = format.frameSize
            if (rate <= 0 || channels <= 0 || bytesPerSample != 2 || frameSize <= 0) {
                release(line)
                return@withContext null
            }

            val framesWanted = (rate.toLong() * durationMs / 1_000L).toInt()
            if (framesWanted <= 0) {
                release(line)
                return@withContext null
            }
            val samples = ShortArray(framesWanted)
            // Multiple of the frame size so read() never splits a frame.
            val bytes = ByteArray(maxOf(4_096, frameSize * 512).let { it - it % frameSize })
            var written = 0
            try {
                line.start()
                while (written < framesWanted) {
                    val maxBytes = minOf(bytes.size, (framesWanted - written) * frameSize)
                    if (maxBytes <= 0) break
                    val read = line.read(bytes, 0, maxBytes)
                    if (read <= 0) break
                    val frames = read / frameSize
                    if (frames <= 0) break
                    val chunkStart = written
                    for (frame in 0 until frames) {
                        var sum = 0
                        for (channel in 0 until channels) {
                            val at = frame * frameSize + channel * bytesPerSample
                            val first = bytes[at].toInt() and 0xFF
                            val second = bytes[at + 1].toInt() and 0xFF
                            val value = if (format.isBigEndian) {
                                ((first shl 8) or second).toShort()
                            } else {
                                ((second shl 8) or first).toShort()
                            }
                            sum += value.toInt()
                        }
                        samples[written++] = (sum / channels).toShort()
                        if (written >= framesWanted) break
                    }
                    if (written > chunkStart) onAmplitude(amplitudeOf(samples, chunkStart, written))
                }
            } catch (unavailable: Exception) {
                return@withContext null
            } finally {
                active = null
                release(line)
            }
            if (written <= 0) null else PcmRecording(samples.copyOf(written), rate)
        }

    /**
     * Opens the first supported line, or null when none is available.
     *
     * The fallback rates are the same set the phone tries. The line's returned
     * format may still differ from the requested one; [record] reads it back.
     */
    private fun openLine(): TargetDataLine? {
        for (rate in RATES) {
            val format = formatAt(rate)
            if (!runCatching { AudioSystem.isLineSupported(DataLine.Info(TargetDataLine::class.java, format)) }
                    .getOrDefault(false)
            ) {
                continue
            }
            val line = try {
                AudioSystem.getLine(DataLine.Info(TargetDataLine::class.java, format)) as? TargetDataLine
            } catch (unavailable: Exception) {
                null
            } ?: continue
            try {
                line.open(format)
                return line
            } catch (unavailable: Exception) {
                runCatching { line.close() }
            }
        }
        return null
    }

    private fun release(line: TargetDataLine) {
        runCatching { line.stop() }
        runCatching { line.close() }
    }

    private fun lineInfo(rate: Int): DataLine.Info =
        DataLine.Info(TargetDataLine::class.java, formatAt(rate))

    private fun formatAt(rate: Int): AudioFormat =
        AudioFormat(rate.toFloat(), 16, 1, true, false)

    private companion object {
        /**
         * Requested in order of usefulness. 16 kHz needs no resampling; 48 and
         * 44.1 kHz are the rates a desktop mixer most often exposes when 16 is
         * refused.
         */
        val RATES = intArrayOf(SAMPLE_RATE, 48_000, 44_100, 32_000, 8_000)
    }
}
