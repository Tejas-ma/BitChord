package com.music.bitchord.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Microphone capture, returned at the rate the device actually opened rather
 * than at the rate the app asked for.
 *
 * The hardware does not always honour a requested sample rate, and a device
 * that cannot open 16 kHz may fall back to 44.1 or 48 kHz. Returning a
 * [PcmRecording] lets the caller resample from that true rate before the
 * fingerprinter or ACRCloud sees the samples; pretending the samples are 16 kHz
 * shortens the fingerprint's time axis and moves every peak's frequency.
 *
 * The platform supplies the microphone (see the Android and desktop actuals);
 * everything above this line works on the samples alone, which is what lets
 * the fingerprinter be exercised by a test with a synthetic buffer.
 */
expect class PcmRecorder() {

    /**
     * Whether this platform can record at all. False where the build has no
     * microphone backend, before any permission has been asked for.
     */
    val isAvailable: Boolean

    /**
     * Records [durationMs] of microphone audio and returns it tagged with the
     * rate the device actually produced. The platform actuals downmix any
     * extra channels to mono and reject a non-16-bit encoding before returning;
     * [PcmRecording] therefore always holds mono signed 16-bit samples.
     *
     * The capture lasts [durationMs] at that true rate. Callers that need the
     * recognisers' 16 kHz mono format should call [PcmRecording.resampledTo].
     *
     * Runs for its whole duration rather than stopping early on silence: an
     * identified song is often quiet at the start.
     *
     * [onAmplitude] is handed the level of each buffer as it arrives, 0..1,
     * for the live waveform the recognition sheet draws while it listens. It
     * is called off the recording thread, so a caller driving Compose state
     * does not need to hop threads itself.
     */
    suspend fun record(
        durationMs: Int = CAPTURE_MS,
        onAmplitude: (Float) -> Unit = {},
        /**
         * How far into the capture [onCheckpoint] is handed the samples so far,
         * or 0 for no checkpoint. Placed inside the early window a recogniser
         * tries first, so a match can end the capture before its full length.
         */
        checkpointMs: Int = 0,
        /**
         * Called once, on the recording thread, with the capture so far at
         * [checkpointMs]. It must not block — the read loop is waiting on it —
         * so a caller that fingerprints the snapshot hands it to another
         * coroutine and returns; calling [stop] from there ends the capture.
         */
        onCheckpoint: ((PcmRecording) -> Unit)? = null,
    ): PcmRecording?

    /**
     * Releases the microphone at once if a capture is in flight, without
     * waiting for [record] to reach its own finally block.
     *
     * Dismissing the sheet has to clear Android's green microphone indicator
     * immediately, which means the hardware has to stop now rather than when a
     * cancelled coroutine next resumes. Safe to call when nothing is recording.
     */
    fun stop()
}

/**
 * How long one recognition capture may run for.
 *
 * Progressive rather than all-or-nothing: the recogniser is handed a window at
 * [EARLY_CHECK_MS] and the capture only runs on to this length when that window
 * did not match. A track that answers at five seconds is answered at five
 * seconds, while a quiet intro gets the full ten instead of being cut off.
 */
const val CAPTURE_MS: Int = 10_000

/** The early window a recogniser checks before letting the capture run on. */
const val EARLY_CHECK_MS: Int = 5_000

/**
 * What is discarded at the start of every capture.
 *
 * `startRecording()` returns before the hardware is feeding the line, and the
 * platform's automatic gain control needs a moment to settle: the first few
 * hundred milliseconds are usually a ramp in from silence. Keeping them only
 * dilutes the fingerprint, so the lead-in is read and dropped.
 */
const val LEAD_IN_TRIM_MS: Int = 300

/** The level a quiet capture is brought up to before it is fingerprinted. */
const val TARGET_RMS: Double = 0.12

/** The most a capture is amplified; beyond this the noise floor is raised too far. */
const val MAX_CAPTURE_GAIN: Double = 4.0

/** Where the limiter starts rounding off, as a fraction of full scale. */
private const val SOFT_KNEE: Double = 0.9

/** The target processing rate: 16 kHz mono 16-bit, what Shazam and ACRCloud expect. */
const val SAMPLE_RATE: Int = 16_000

/** Whether [samples] hold anything but silence, so a dead microphone is told apart from a quiet room. */
fun PcmRecording.hasSignal(): Boolean {
    if (samples.isEmpty()) return false
    var sum = 0.0
    for (sample in samples) sum += abs(sample.toInt())
    val mean = sum / samples.size
    // About -50 dBFS: a quiet room still has a noise floor above this, and a
    // muted or unplugged microphone sits at exactly zero.
    return mean > 300.0
}

/**
 * The live level of [samples] as a fraction of full scale, 0..1.
 *
 * Root mean square, not peak: the recognition sheet's waveform is scaled by
 * this, and a peak meter spends most of a voice at full height while an RMS
 * meter follows the loudness actually heard. The display gain is there
 * because speech sits far below full scale; it is clamped, so it can fill the
 * waveform but never overflow it.
 */
fun amplitudeOf(samples: ShortArray, from: Int = 0, to: Int = samples.size): Float {
    if (to <= from) return 0f
    var sum = 0.0
    for (index in from until to) {
        val value = samples[index] / 32_768.0
        sum += value * value
    }
    val rms = sqrt(sum / (to - from)).toFloat()
    return (rms * 4.2f).coerceIn(0f, 1f)
}

/**
 * This capture brought gently up toward [TARGET_RMS] before it is fingerprinted.
 *
 * A phone lying on a table next to a speaker hears the track well below full
 * scale, and Shazam's peaks are found on a level-relative scale, so a quiet
 * capture loses the peaks that would have matched. Amplification is capped at
 * [MAX_CAPTURE_GAIN] — any more lifts the room's noise floor into the same
 * band as the music — and never attenuates: a loud capture is already the best
 * input the fingerprinter can get. The top of the range is rounded off rather
 * than left to wrap or clip hard, so no sample ever leaves ±1.0.
 */
fun PcmRecording.gainNormalized(
    targetRms: Double = TARGET_RMS,
    maxGain: Double = MAX_CAPTURE_GAIN,
): PcmRecording {
    if (samples.isEmpty()) return this
    var sum = 0.0
    for (sample in samples) {
        val value = sample / 32_768.0
        sum += value * value
    }
    val rms = sqrt(sum / samples.size)
    // A dead microphone stays dead: amplifying exact zeros only amplifies noise.
    if (rms <= 1e-9) return this
    val gain = (targetRms / rms).coerceIn(1.0, maxGain)
    if (gain <= 1.001) return this
    val out = ShortArray(samples.size)
    for (index in samples.indices) {
        var value = samples[index] / 32_768.0 * gain
        val magnitude = abs(value)
        if (magnitude > SOFT_KNEE) {
            val sign = if (value < 0) -1.0 else 1.0
            // tanh from the knee to the ceiling: continuous at the knee, and
            // asymptotic to 1.0, so nothing reaches the wrap point.
            value = sign * (SOFT_KNEE + (1.0 - SOFT_KNEE) * tanh((magnitude - SOFT_KNEE) / (1.0 - SOFT_KNEE)))
        }
        out[index] = (value.coerceIn(-1.0, 1.0) * 32_767.0).roundToInt().toShort()
    }
    return PcmRecording(out, sampleRate)
}

/** One microphone capture, with the rate its [samples] are actually at. */
data class PcmRecording(
    val samples: ShortArray,
    val sampleRate: Int = SAMPLE_RATE,
) {
    /** How long the capture lasts, for the `samplems` Shazam is sent. */
    val durationMs: Long get() = samples.size * 1_000L / sampleRate

    /**
     * This recording at [targetRate], band-limited to the new Nyquist.
     *
     * A windowed-sinc kernel rather than dropping or repeating samples:
     * decimating 48 kHz to 16 kHz without a low-pass folds everything above
     * 8 kHz back into the fingerprint's bands, where it looks like real peaks.
     * The kernel is also used for 44.1 kHz's non-integer ratio and for
     * upsampling a device that only opened 8 kHz; normalising by the summed
     * weights keeps the level steady at the edges.
     *
     * The result's duration is preserved to within one sample, and callers get
     * a recording whose [sampleRate] is exactly [targetRate].
     */
    fun resampledTo(targetRate: Int): PcmRecording {
        if (targetRate <= 0 || sampleRate <= 0 || samples.isEmpty() || sampleRate == targetRate) {
            return this
        }
        val outputSize = ((samples.size.toLong() * targetRate + sampleRate / 2) / sampleRate)
            .toInt()
            .coerceAtLeast(1)
        val step = sampleRate.toDouble() / targetRate
        // Relative to the source's Nyquist: below 1 when decimating, 1 when upsampling.
        val cutoff = minOf(1.0, targetRate.toDouble() / sampleRate)
        // Keep roughly 32 zero crossings of the sinc at every ratio. A fixed
        // 16-source-sample radius looks like 32 taps at 16 kHz, but at 48 kHz
        // its cutoff is one third of the source Nyquist, so the transition
        // band would be three times too wide and 8–16 kHz would fold back
        // into the recognisers' bands. Widening the radius with the cutoff
        // keeps the anti-alias filter's shape constant.
        val radius = 16.0 / cutoff
        val out = ShortArray(outputSize)
        for (index in out.indices) {
            val center = index * step
            val first = floor(center - radius).toInt()
            val last = ceil(center + radius).toInt()
            var sum = 0.0
            var weight = 0.0
            for (source in first..last) {
                if (source !in samples.indices) continue
                val distance = center - source
                val angle = PI * distance * cutoff
                val sinc = if (abs(angle) < 1e-9) 1.0 else sin(angle) / angle
                // A Hann taper keeps the truncated sinc from ringing at the kernel edge.
                val window = 0.5 + 0.5 * cos(PI * distance / radius)
                val coefficient = sinc * window
                sum += samples[source] * coefficient
                weight += coefficient
            }
            out[index] = if (weight == 0.0) {
                0
            } else {
                (sum / weight).roundToInt().coerceIn(-32_768, 32_767).toShort()
            }
        }
        return PcmRecording(out, targetRate)
    }
}
