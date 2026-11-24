package com.music.bitchord.audio

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * ACRCloud's identify endpoint, with the listener's own key pair.
 *
 * This is the half Shazam's fingerprinter cannot do: a hum, a whistle or a
 * sung line is not the recording the service holds, so there is no signature to
 * match — ACRCloud's acoustic matcher is the one that answers a melody sung
 * from memory. It needs a project on their side, which is why it is an opt-in
 * pair of keys rather than something the app ships; with no keys the sheet
 * hands the hum to Google instead.
 *
 * The request is the one their own sample clients send: form data with the raw
 * 16-bit PCM, an HMAC-SHA1 signature over the request's first lines, and the
 * timestamp it was signed for.
 */
object AcrCloudClient {

    private const val PATH = "/v1/identify"
    private const val DATA_TYPE = "audio"
    private const val SIGNATURE_VERSION = "1"
    private const val USER_AGENT = "BitChord/1.0"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 25_000
            connectTimeoutMillis = 10_000
        }
    }

    /**
     * Recognises [recording], or answers null when ACRCloud has no match.
     *
     * A caller that has no keys should not get here at all — the app's
     * `hasValidAcrCloudConfig` gate sees to that — but a blank one is answered
     * as "nothing was found" rather than sent.
     */
    suspend fun identify(
        recording: PcmRecording,
        host: String,
        accessKey: String,
        accessSecret: String,
    ): Result<RecognizedSong?> = runCatching {
        val endpoint = endpointFor(host) ?: return@runCatching null
        if (accessKey.isBlank() || accessSecret.isBlank()) return@runCatching null
        // ACRCloud's audio body is 16-bit PCM at 16 kHz. The recorder may have
        // opened 44.1/48 kHz, so resample once here rather than sending samples
        // whose header says nothing about their rate.
        val normalized = recording.resampledTo(SAMPLE_RATE)
        if (normalized.samples.isEmpty()) return@runCatching null
        val timestamp = System.currentTimeMillis() / 1_000
        val signature = signature(accessSecret, accessKey, timestamp)
        val pcm = pcmBytes(normalized.samples)
        val response = client.submitFormWithBinaryData(
            url = "$endpoint$PATH",
            formData = formData {
                append("access_key", accessKey)
                append("data_type", DATA_TYPE)
                append("signature_version", SIGNATURE_VERSION)
                append("timestamp", timestamp.toString())
                append("signature", signature)
                append("sample_bytes", pcm.size.toString())
                append("sample", pcm, Headers.build {
                    append(HttpHeaders.ContentType, "application/octet-stream")
                    append(HttpHeaders.ContentDisposition, "filename=\"sample.raw\"")
                })
            },
        ) {
            headers.append(HttpHeaders.UserAgent, USER_AGENT)
        }
        parse(response.bodyAsText())
    }

    /**
     * Where [host] names, as a full endpoint base, or null when it is not an
     * address at all.
     *
     * Accepts what someone would paste out of the dashboard — with a scheme,
     * with a trailing slash, with the path already on it — since a mistyped
     * field should not read as "no match found".
     */
    internal fun endpointFor(host: String): String? {
        var value = host.trim()
        if (value.isEmpty()) return null
        value = value.removePrefix("https://").removePrefix("http://").trimEnd('/')
        value = value.removeSuffix(PATH)
        value = value.trimEnd('/')
        if (value.isEmpty() || value.contains(' ') || !value.contains('.')) return null
        return "https://$value"
    }

    /** ACRCloud's `signature`: base64(HMAC-SHA1(secret, request-lines)). */
    internal fun signature(
        accessSecret: String,
        accessKey: String,
        timestamp: Long,
        method: String = "POST",
        path: String = PATH,
        dataType: String = DATA_TYPE,
        signatureVersion: String = SIGNATURE_VERSION,
    ): String {
        val toSign = buildString {
            append(method).append('\n')
            append(path).append('\n')
            append(accessKey).append('\n')
            append(dataType).append('\n')
            append(signatureVersion).append('\n')
            append(timestamp)
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(accessSecret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return java.util.Base64.getEncoder()
            .encodeToString(mac.doFinal(toSign.toByteArray(Charsets.UTF_8)))
    }

    /** The samples as the little-endian 16-bit bytes ACRCloud's `audio` type means. */
    internal fun pcmBytes(samples: ShortArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        for (index in samples.indices) {
            val value = samples[index].toInt()
            bytes[index * 2] = (value and 0xFF).toByte()
            bytes[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    /**
     * The first song in an identify answer, or null when it holds none.
     *
     * A miss and a failure both come back as `status.code` 1001 or 0 with no
     * `music` array. Only the song matters to the caller: it is about to search
     * for the title and artist it names.
     */
    internal fun parse(text: String): RecognizedSong? {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val metadata = runCatching { root["metadata"]?.jsonObject }.getOrNull() ?: return null
        val music = runCatching { metadata["music"] as? JsonArray }.getOrNull() ?: return null
        val track = runCatching { music.jsonArray.firstOrNull()?.jsonObject }.getOrNull() ?: return null
        val title = track.text("title").orEmpty()
        if (title.isEmpty()) return null
        val artist = runCatching {
            (track["artists"] as? JsonArray)
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.text("name")
                .orEmpty()
        }.getOrDefault("")
        return RecognizedSong(title = title, artist = artist)
    }

    private fun JsonObject.text(key: String): String? =
        runCatching { get(key)?.jsonPrimitive?.contentOrNull }.getOrNull()?.trim()
}
