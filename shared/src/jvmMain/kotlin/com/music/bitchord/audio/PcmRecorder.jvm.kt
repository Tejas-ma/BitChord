package com.music.bitchord.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/**
 * The desktop's microphone, read through `javax.sound.sampled`.
 *
 * The format is the one the phone captures in — 16 kHz, 16-bit, mono,
 * little-endian — so the fingerprinter and the recogniser see the same thing
 * on both platforms. A machine with no capture device (or no permission to use
 * one) answers [isAvailable] false rather than throwing.
 */
actual class PcmRecorder actual constructor() {

    actual val isAvailable: Boolean
        get() = runCatching {
            AudioSystem.isLineSupported(
                DataLine.Info(
                    TargetDataLine::class.java,
                    AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false),
                ),
            )
        }.getOrDefault(false)

    actual suspend fun record(durationMs: Int): ShortArray? = withContext(Dispatchers.IO) {
        val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
        val info = DataLine.Info(TargetDataLine::class.java, format)
        val line = try {
            (AudioSystem.getLine(info) as? TargetDataLine)?.apply { open(format) }
        } catch (unavailable: Exception) {
            null
        } ?: return@withContext null

        val totalSamples = SAMPLE_RATE * durationMs / 1_000
        val samples = ShortArray(totalSamples)
        val bytes = ByteArray(4_096)
        var written = 0
        try {
            line.start()
            while (written < totalSamples) {
                val read = line.read(bytes, 0, minOf(bytes.size, (totalSamples - written) * 2))
                if (read <= 0) break
                var index = 0
                while (index + 1 < read && written < totalSamples) {
                    // Little-endian signed 16-bit, as the AudioFormat above asks for.
                    val low = bytes[index].toInt() and 0xFF
                    val high = bytes[index + 1].toInt()
                    samples[written++] = ((high shl 8) or low).toShort()
                    index += 2
                }
            }
        } catch (unavailable: Exception) {
            return@withContext null
        } finally {
            runCatching { line.stop() }
            line.close()
        }
        if (written <= 0) null else samples.copyOf(written)
    }
}
