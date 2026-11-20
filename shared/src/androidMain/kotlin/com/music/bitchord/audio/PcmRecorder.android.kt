package com.music.bitchord.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The phone's microphone, read straight off [AudioRecord].
 *
 * `VOICE_RECOGNITION` rather than `MIC`: it is the source the platform's own
 * recogniser uses, and on devices with a noise suppressor it hands over the
 * unprocessed signal a fingerprint needs instead of a cleaned-up one.
 */
actual class PcmRecorder actual constructor() {

    actual val isAvailable: Boolean
        get() = true

    @SuppressLint("MissingPermission")
    actual suspend fun record(durationMs: Int): ShortArray? = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return@withContext null
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE * 2 / 2)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (denied: SecurityException) {
            // No RECORD_AUDIO permission: the caller shows the ask, not a crash.
            return@withContext null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return@withContext null
        }

        val totalSamples = SAMPLE_RATE * durationMs / 1_000
        val samples = ShortArray(totalSamples)
        var written = 0
        try {
            record.startRecording()
            while (written < totalSamples) {
                val read = record.read(samples, written, (totalSamples - written).coerceAtMost(bufferSize / 2))
                if (read <= 0) break
                written += read
            }
        } catch (denied: SecurityException) {
            return@withContext null
        } finally {
            runCatching { record.stop() }
            record.release()
        }
        if (written <= 0) null else samples.copyOf(written)
    }
}
