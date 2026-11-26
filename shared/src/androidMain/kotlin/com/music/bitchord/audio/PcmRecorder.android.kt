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
 * The requested format is 16 kHz mono 16-bit, but the hardware is allowed to
 * choose a different rate: the constructor and [AudioRecord.sampleRate] are
 * both consulted, and the returned [PcmRecording] carries the rate that was
 * actually opened. Callers resample that to the recognisers' 16 kHz rather than
 * treating 44.1/48 kHz samples as if they were 16 kHz.
 *
 * `UNPROCESSED` is preferred over `VOICE_RECOGNITION` and then `MIC` because the
 * raw capsule signal is what a fingerprint and ACRCloud's matcher want. If the
 * source is unavailable, the next source is tried; if the requested rate is
 * unavailable, the next rate is tried before giving up. A line that initialises
 * but never reaches [AudioRecord.RECORDSTATE_RECORDING] is released and the
 * next candidate is tried rather than being read from, which is what used to
 * leave the sheet showing a flat waveform for the whole capture.
 */
actual class PcmRecorder actual constructor() {

    actual val isAvailable: Boolean
        get() = true

    /**
     * The capture in flight, if any, so [stop] can release it from another
     * thread.
     *
     * Volatile because the capture loop (an IO thread) publishes it and the
     * sheet's dispose (the main thread) reads it: without the barrier the
     * close path could still see null and leave the microphone open.
     */
    @Volatile
    private var active: AudioRecord? = null

    actual fun stop() {
        val record = active ?: return
        active = null
        runCatching { record.stop() }
        runCatching { record.release() }
    }

    @SuppressLint("MissingPermission")
    actual suspend fun record(durationMs: Int, onAmplitude: (Float) -> Unit): PcmRecording? =
        withContext(Dispatchers.IO) {
            if (durationMs <= 0) return@withContext null
            // Source and rate are tried in order: a device may refuse the raw
            // capsule source, refuse 16 kHz, or accept a line and then never
            // start recording. Each is a different reason to fall through to
            // the next candidate instead of giving up on the microphone.
            for (source in SOURCES) {
                for (rate in RATES) {
                    val capture = open(source, rate) ?: continue
                    val recording = readCapture(capture, durationMs, onAmplitude)
                    if (recording != null) return@withContext recording
                }
            }
            null
        }

    /**
     * Reads one capture window from [capture], or null when this candidate
     * failed to start or produced nothing.
     *
     * The window is measured at the rate the device actually opened, so five
     * seconds means five seconds even when it is 48 kHz.
     */
    @SuppressLint("MissingPermission")
    private fun readCapture(
        capture: Capture,
        durationMs: Int,
        onAmplitude: (Float) -> Unit,
    ): PcmRecording? {
        val record = capture.record
        active = record
        val framesWanted = (capture.sampleRate.toLong() * durationMs / 1_000L).toInt()
        if (framesWanted <= 0) {
            active = null
            release(record)
            return null
        }
        val mono = ShortArray(framesWanted)
        // AudioRecord's read() takes sample counts, not bytes. The buffer size
        // it was opened with is bytes, so halve it for 16-bit samples.
        val readBuffer = ShortArray(maxOf(1, capture.bufferSize / 2))
        var written = 0
        try {
            record.startRecording()
            // startRecording() can return without the hardware actually
            // running; reading then answers silence for the whole window. That
            // is a different failure from a quiet room, so this candidate is
            // abandoned here and the caller tries the next one.
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return null
            while (written < framesWanted) {
                val maxShorts = minOf(readBuffer.size, (framesWanted - written) * capture.channels)
                if (maxShorts <= 0) break
                val read = record.read(readBuffer, 0, maxShorts)
                if (read <= 0) break
                val frames = read / capture.channels
                if (frames <= 0) break
                val chunkStart = written
                for (frame in 0 until frames) {
                    // The constructor asks for mono, but a device is free to
                    // give an interleaved stereo line; average rather than
                    // taking one channel and halving the level.
                    var sum = 0
                    val at = frame * capture.channels
                    for (channel in 0 until capture.channels) sum += readBuffer[at + channel]
                    mono[written++] = (sum / capture.channels).toShort()
                    if (written >= framesWanted) break
                }
                if (written > chunkStart) onAmplitude(amplitudeOf(mono, chunkStart, written))
            }
        } catch (denied: SecurityException) {
            return null
        } catch (unavailable: IllegalStateException) {
            return null
        } finally {
            // Always both, whether the loop finished, threw or the coroutine was
            // cancelled: Android's microphone indicator is held for exactly as
            // long as this object is recording.
            active = null
            release(record)
        }
        return if (written <= 0) null else PcmRecording(mono.copyOf(written), capture.sampleRate)
    }

    /**
     * Opens one source and rate, or null when this pair cannot be used.
     *
     * The real rate is read back from the object after it reaches
     * [AudioRecord.STATE_INITIALIZED], because that is the only value the
     * samples can honestly be tagged with. The buffer is twice the device's
     * declared minimum: a single-minimum buffer overruns between reads and
     * shows up as dropped frames in the capture.
     */
    @SuppressLint("MissingPermission")
    private fun open(source: Int, requestedRate: Int): Capture? {
        val minBuffer = AudioRecord.getMinBufferSize(
            requestedRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return null
        // At least twice the device minimum, and never under half a second of
        // mono 16-bit, so a slow first read does not starve the line.
        val bufferSize = maxOf(minBuffer * 2, requestedRate * 2)
        val record = try {
            AudioRecord(
                source,
                requestedRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (denied: SecurityException) {
            // Permission is process-wide; another source or rate will fail the
            // same way.
            return null
        } catch (unsupported: IllegalArgumentException) {
            return null
        }

        val initialized = record.state == AudioRecord.STATE_INITIALIZED
        // getAudioFormat() is the int encoding; getFormat() is the AudioFormat
        // object whose getEncoding() is the same value.
        val encoding = record.format.encoding
        val actualRate = record.sampleRate
        val channels = record.channelCount
        if (!initialized || encoding != AudioFormat.ENCODING_PCM_16BIT ||
            actualRate <= 0 || channels <= 0
        ) {
            release(record)
            return null
        }
        return Capture(record, actualRate, channels, bufferSize)
    }

    private fun release(record: AudioRecord) {
        runCatching { record.stop() }
        runCatching { record.release() }
    }

    private data class Capture(
        val record: AudioRecord,
        val sampleRate: Int,
        val channels: Int,
        val bufferSize: Int,
    )

    private companion object {
        /**
         * UNPROCESSED first: the raw capsule signal the recognisers prefer.
         * VOICE_RECOGNITION is the next best — it still delivers the heard
         * audio, with the platform's speech tuning — and MIC is the last
         * source every device has.
         */
        val SOURCES = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )

        /**
         * The requested rates, in the order a device is asked for them. The
         * returned recording carries whichever one the device actually opened.
         */
        val RATES = intArrayOf(SAMPLE_RATE, 48_000, 44_100, 32_000, 8_000)
    }
}
