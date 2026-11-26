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
 * 44.1/48 kHz line instead, and a few expose only a stereo line. The returned
 * [PcmRecording] carries that actual rate, so the recognisers resample from it
 * rather than mislabelling it.
 *
 * Candidates are probed rather than assumed: each rate is asked for with
 * [AudioSystem.isLineSupported] before a line is opened, mono first and stereo
 * after it, which is what lets a mixer that has no mono 48 kHz line still
 * record.
 *
 * A machine with no capture device (or no permission to use one) answers
 * [isAvailable] false rather than throwing.
 */
actual class PcmRecorder actual constructor() {

    actual val isAvailable: Boolean
        get() = CANDIDATES.any { (rate, channels) ->
            runCatching { AudioSystem.isLineSupported(lineInfo(rate, channels)) }
                .getOrDefault(false)
        }

    /**
     * The line in flight, if any, so [stop] can close it from another thread.
     *
     * Volatile because the capture loop (an IO thread) publishes it and the
     * sheet's dispose (the UI thread) reads it: without the barrier the close
     * path could still see null and leave the line open.
     */
    @Volatile
    private var active: TargetDataLine? = null

    actual fun stop() {
        val line = active ?: return
        active = null
        runCatching { line.stop() }
        runCatching { line.flush() }
        runCatching { line.close() }
    }

    actual suspend fun record(durationMs: Int, onAmplitude: (Float) -> Unit): PcmRecording? =
        withContext(Dispatchers.IO) {
            if (durationMs <= 0) return@withContext null
            // A device that has no usable line at one rate may still have one
            // at the next, so a failure falls through to the next candidate
            // instead of ending the capture.
            for ((rate, channels) in CANDIDATES) {
                val line = openLine(rate, channels) ?: continue
                val recording = readLine(line, durationMs, onAmplitude)
                if (recording != null) return@withContext recording
            }
            null
        }

    /** Reads one window from [line], or null when this line produced nothing. */
    private fun readLine(
        line: TargetDataLine,
        durationMs: Int,
        onAmplitude: (Float) -> Unit,
    ): PcmRecording? {
        active = line
        val format = line.format
        val rate = format.sampleRate.roundToInt()
        val channels = format.channels
        val bytesPerSample = format.sampleSizeInBits / 8
        val frameSize = format.frameSize
        if (rate <= 0 || channels <= 0 || bytesPerSample != 2 || frameSize <= 0) {
            active = null
            release(line)
            return null
        }

        val framesWanted = (rate.toLong() * durationMs / 1_000L).toInt()
        if (framesWanted <= 0) {
            active = null
            release(line)
            return null
        }
        val samples = ShortArray(framesWanted)
        // Multiple of the frame size so read() never splits a frame.
        val bytes = ByteArray(maxOf(4_096, frameSize * 512).let { it - it % frameSize })
        var written = 0
        try {
            line.start()
            if (!line.isRunning) return null
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
            return null
        } finally {
            active = null
            release(line)
        }
        return if (written <= 0) null else PcmRecording(samples.copyOf(written), rate)
    }

    /**
     * Opens the line for one candidate, or null when the mixer refuses it.
     *
     * The line's returned format may still differ from the requested one;
     * [readLine] reads it back and tags the samples with it.
     */
    private fun openLine(rate: Int, channels: Int): TargetDataLine? {
        val format = formatAt(rate, channels)
        val info = DataLine.Info(TargetDataLine::class.java, format)
        if (!runCatching { AudioSystem.isLineSupported(info) }.getOrDefault(false)) return null
        val line = try {
            AudioSystem.getLine(info) as? TargetDataLine
        } catch (unavailable: Exception) {
            null
        } ?: return null
        return try {
            line.open(format)
            line
        } catch (unavailable: Exception) {
            runCatching { line.close() }
            null
        }
    }

    /**
     * Stops the line, discards what is still buffered and closes it.
     *
     * Flush rather than drain: drain waits for the buffered frames to be
     * played, which on a capture line nobody is reading means the close hangs
     * for as long as the buffer lasts. Nothing needs those last frames — the
     * capture window is already full when this runs.
     */
    private fun release(line: TargetDataLine) {
        runCatching { line.stop() }
        runCatching { line.flush() }
        runCatching { line.close() }
    }

    private fun lineInfo(rate: Int, channels: Int): DataLine.Info =
        DataLine.Info(TargetDataLine::class.java, formatAt(rate, channels))

    private fun formatAt(rate: Int, channels: Int): AudioFormat =
        AudioFormat(rate.toFloat(), 16, channels, true, false)

    private companion object {
        /**
         * Probed in order of usefulness. 16 kHz needs no resampling; 48 and
         * 44.1 kHz are the rates a desktop mixer most often exposes when 16 is
         * refused. Mono is preferred because the recognisers mix down anyway,
         * with the stereo line of the same rate as its fallback.
         */
        val CANDIDATES = listOf(
            SAMPLE_RATE to 1,
            48_000 to 1,
            44_100 to 1,
            32_000 to 1,
            16_000 to 2,
            48_000 to 2,
            44_100 to 2,
        )
    }
}
