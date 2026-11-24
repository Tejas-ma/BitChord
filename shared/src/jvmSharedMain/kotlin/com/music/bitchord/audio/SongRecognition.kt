package com.music.bitchord.audio

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.TimeZone
import java.util.UUID

/** A song the recogniser matched, as Shazam named it. */
data class RecognizedSong(
    val title: String,
    val artist: String,
    val artworkUrl: String? = null,
    val shazamId: String? = null,
) {
    /**
     * What to type into the search field once this is identified: the title,
     * and the artist when Shazam gave more than a bare name.
     */
    val searchQuery: String
        get() = if (artist.isBlank() || artist.equals("Unknown", true)) title else "$title $artist"
}

/**
 * What one listen produced.
 *
 * [NoMatch] is the ordinary answer for a hum, a conversation or a track
 * Shazam does not have — kept apart from [Failed] so the UI does not offer a
 * retry for something a retry cannot fix.
 */
sealed interface RecognitionResult {
    data class Match(val song: RecognizedSong) : RecognitionResult

    data object NoMatch : RecognitionResult

    data object Silent : RecognitionResult

    data object Unsupported : RecognitionResult

    data class Failed(val reason: String) : RecognitionResult
}

/**
 * Shazam's discovery endpoint, with no key and no account.
 *
 * This is the request Shazam's own mobile clients send: the signature URI
 * built by [ShazamFingerprint] in a JSON body, to the same `discovery/v5`
 * route. The answer's first `track` is the match; an empty `matches` list is
 * a miss rather than an error.
 */
object ShazamClient {

    private const val LANGUAGE = "en-US"
    private const val COUNTRY = "GB"
    private const val ENDPOINT =
        "https://amp.shazam.com/discovery/v5/$LANGUAGE/$COUNTRY/iphone/-/tag"

    private const val QUERY =
        "?sync=true&webv3=true&sampling=true&connected=" +
            "&shazamapiversion=v3&sharehub=true&hubv5minorversion=v5.1&hidelb=true&video=v3"

    /**
     * A current iPhone client's identity.
     *
     * The endpoint answers the same JSON to anything that looks like the app;
     * `web` is a different, emptier shape and `android` returns intent:// deep
     * links, so the iPhone profile is the one to present.
     */
    private const val USER_AGENT =
        "Shazam/14.1.0 (iPhone; iOS 17.4; Scale/3.00)"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
        }
    }

    /** Recognises [recording]'s audio, or answers null when Shazam has no match. */
    suspend fun identify(recording: PcmRecording): Result<RecognizedSong?> = runCatching {
        // Shazam's payload is defined around 16 kHz. A device that opened at
        // 44.1/48 kHz is resampled here, at the boundary, so the signature's
        // sample count and frame times describe the audio that was heard.
        val normalized = recording.resampledTo(SAMPLE_RATE)
        val signature = ShazamFingerprint.signature(normalized.samples, normalized.sampleRate)
        if (signature.isEmpty) return@runCatching null
        identify(signature.toUri(), normalized.sampleRate, normalized.samples.size)
    }

    /** The same, from a signature already built — for tests and for a re-send. */
    suspend fun identify(uri: String, sampleRateHz: Int, sampleCount: Int): RecognizedSong? =
        identify(uri, sampleRateHz, sampleCount, ENDPOINT, client)

    /**
     * The request itself, against [endpoint] over [http] rather than Shazam.
     *
     * Two UUIDs for the tag ids, the iPhone headers and a body of the signature
     * and its length in milliseconds — what Shazam's own client sends. Split
     * out so it can be driven without a network, which is the only way to test
     * a recogniser on a machine with no microphone and no match to find.
     */
    internal suspend fun identify(
        uri: String,
        sampleRateHz: Int,
        sampleCount: Int,
        endpoint: String,
        http: HttpClient,
    ): RecognizedSong? {
        val response = http.post(
            "$endpoint/${UUID.randomUUID().toString().uppercase()}" +
                "/${UUID.randomUUID().toString().uppercase()}$QUERY",
        ) {
            header("X-Shazam-Platform", "IPHONE")
            header("X-Shazam-AppVersion", "14.1.0")
            header("Accept", "*/*")
            header("Accept-Language", LANGUAGE)
            header("User-Agent", USER_AGENT)
            header("Content-Type", "application/json")
            setBody(requestBody(uri, sampleRateHz, sampleCount))
        }
        return parse(response.bodyAsText())
    }

    /** The JSON body the discovery endpoint takes for [uri]. */
    internal fun requestBody(
        uri: String,
        sampleRateHz: Int,
        sampleCount: Int,
        now: Long = System.currentTimeMillis(),
    ): String = buildString {
        append("{\"timezone\":\"").append(escape(TimeZone.getDefault().id)).append("\",")
        append("\"signature\":{\"uri\":\"").append(uri).append("\",")
        append("\"samplems\":").append(sampleCount * 1_000L / sampleRateHz).append("},")
        append("\"timestamp\":").append(now).append(',')
        append("\"context\":{},\"geolocation\":{}}")
    }

    /**
     * The song in a discovery answer, or null when it holds no track — which is
     * how its misses come back. The `matches` array is not consulted: a track is
     * the match, whatever that array says, and a title is what makes a track.
     */
    internal fun parse(text: String): RecognizedSong? {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val track = runCatching { root["track"]?.jsonObject }.getOrNull() ?: return null
        val title = track.text("title").orEmpty()
        if (title.isEmpty()) return null
        return RecognizedSong(
            title = title,
            artist = track.text("subtitle").orEmpty(),
            artworkUrl = runCatching {
                track["images"]?.jsonObject?.get("coverart")?.jsonPrimitive?.contentOrNull
            }.getOrNull(),
            shazamId = track.text("key"),
        )
    }

    /** A string field, trimmed; null when it is absent or not a primitive. */
    private fun JsonObject.text(key: String): String? =
        runCatching { get(key)?.jsonPrimitive?.contentOrNull }.getOrNull()?.trim()

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}

/**
 * Listens once and recognizes what is playing.
 *
 * The only thing above the microphone and the network: capture → fingerprint →
 * Shazam. A caller that wants the search to run afterwards does not need
 * anything else from here; [RecognitionResult.Match.song] carries the query.
 */
class SongRecognition(
    private val recorder: PcmRecorder = PcmRecorder(),
) {

    suspend fun listen(
        durationMs: Int = CAPTURE_MS,
        onAmplitude: (Float) -> Unit = {},
        /** Called once the capture has finished and the match request is about to start. */
        onCaptureComplete: () -> Unit = {},
    ): RecognitionResult {
        if (!recorder.isAvailable) return RecognitionResult.Unsupported
        val captured = recorder.record(durationMs, onAmplitude) ?: return RecognitionResult.Failed(
            "The microphone could not be opened",
        )
        val recording = captured.resampledTo(SAMPLE_RATE)
        if (recording.samples.isEmpty()) return RecognitionResult.Failed("Nothing was recorded")
        if (!recording.hasSignal()) return RecognitionResult.Silent
        onCaptureComplete()
        return ShazamClient.identify(recording).fold(
            onSuccess = { song -> if (song == null) RecognitionResult.NoMatch else RecognitionResult.Match(song) },
            onFailure = { RecognitionResult.Failed(it.message ?: "Could not reach Shazam") },
        )
    }

    /**
     * Releases the microphone now rather than when a cancelled coroutine next
     * resumes. The recognition sheet calls this from its dispose hook so
     * Android's green indicator clears the moment it closes.
     */
    fun stop() {
        recorder.stop()
    }
}
