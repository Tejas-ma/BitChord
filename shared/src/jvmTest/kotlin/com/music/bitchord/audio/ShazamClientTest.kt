package com.music.bitchord.audio

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The recognition path below the microphone, offline: the real [ShazamClient]
 * request over Ktor, against a server on loopback that answers with what the
 * discovery endpoint answered when it was recorded.
 *
 * Nothing here reaches Shazam and nothing records audio, so this is the only
 * part of the recogniser a machine with neither can check.
 */
class ShazamClientTest {

    private lateinit var server: MockWebServer
    private lateinit var http: HttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        http = HttpClient(OkHttp)
    }

    @After
    fun tearDown() {
        http.close()
        server.shutdown()
    }

    /** Where Shazam's route sits, on this test's server. */
    private val endpoint: String
        get() = server.url("/discovery/v5/en-US/GB/iphone/-/tag").toString()

    /** A signature-shaped URI: only the body's shape matters here, not its audio. */
    private val uri = "data:audio/vnd.shazam.sig;base64,3gIAAPAEAADeAgAAPAQAAN4CAADeAgAA"

    private fun answer(body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )
    }

    /** 80 000 samples at 16 kHz — the five seconds [PcmRecorder] hands over. */
    private suspend fun identify() = ShazamClient.identify(uri, 16_000, 80_000, endpoint, http)

    @Test
    fun `a recorded match names the song, its artist and its artwork`() = runBlocking {
        answer(MATCH)

        val song = identify()

        assertEquals("Blinding Lights", song?.title)
        assertEquals("The Weeknd", song?.artist)
        assertEquals("535384947", song?.shazamId)
        assertEquals("https://images.shazam.com/coverart/blinding-lights.jpg", song?.artworkUrl)
        // What the search bar is filled with afterwards.
        assertEquals("Blinding Lights The Weeknd", song?.searchQuery)
    }

    @Test
    fun `the request is the one shazam's own client sends`() = runBlocking {
        answer(MATCH)
        identify()

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        val path = request.path.orEmpty()
        assertTrue(path, path.startsWith("/discovery/v5/en-US/GB/iphone/-/tag/"))
        assertTrue(path, path.endsWith(QUERY))
        // Two uppercase UUIDs, as the app tags a listen.
        val tagIds = path.removePrefix("/discovery/v5/en-US/GB/iphone/-/tag/").substringBefore('?')
        val uuid = "[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}"
        assertTrue(tagIds, tagIds.matches(Regex("$uuid/$uuid")))

        assertEquals("IPHONE", request.getHeader("X-Shazam-Platform"))
        assertEquals("14.1.0", request.getHeader("X-Shazam-AppVersion"))
        assertEquals("en-US", request.getHeader("Accept-Language"))
        assertTrue(request.getHeader("User-Agent").orEmpty().startsWith("Shazam/14.1.0"))
        assertEquals("application/json", request.getHeader("Content-Type"))

        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val signature = body["signature"]!!.jsonObject
        assertEquals(uri, signature["uri"]!!.jsonPrimitive.content)
        assertEquals(5_000, signature["samplems"]!!.jsonPrimitive.int)
        assertTrue(body["timestamp"]!!.jsonPrimitive.long > 0)
        assertTrue(body["timezone"]!!.jsonPrimitive.content.isNotBlank())
        assertEquals("{}", body["context"].toString())
        assertEquals("{}", body["geolocation"].toString())
    }

    /**
     * The two halves meeting: the URI the fingerprinter built is the one the
     * client puts in its request, and the URI read back out of the request the
     * server received decodes to the very bands it was built from.
     *
     * The test above drives a hand-shaped URI, which says the envelope is
     * right; this one carries a real signature for real audio, so a bug in the
     * writer or the reader cannot hide behind the other half.
     */
    @Test
    fun `a fingerprinted uri goes out in the request and decodes back to its bands`() = runBlocking {
        answer(MATCH)
        val written = ShazamFingerprint.signature(onsetRichMixture())
        // The mixture has attacks to find; an empty signature would prove nothing.
        assertTrue(written.bands.sumOf { it.size } > 0)

        val song = ShazamClient.identify(
            written.toUri(),
            written.sampleRateHz,
            written.numberSamples,
            endpoint,
            http,
        )

        val signature = Json.parseToJsonElement(server.takeRequest().body.readUtf8())
            .jsonObject["signature"]!!.jsonObject
        val sent = ShazamFingerprint.decode(signature["uri"]!!.jsonPrimitive.content)

        // What arrived describes the listen the client was given: the same five
        // seconds, at the same rate, with every peak in the band it was written in.
        assertEquals(written.sampleRateHz, sent.sampleRateHz)
        assertEquals(written.numberSamples, sent.numberSamples)
        assertEquals(written.bands, sent.bands)
        assertEquals(5_000, signature["samplems"]!!.jsonPrimitive.int)
        // And the answer that came back is still the match.
        assertEquals("Blinding Lights", song?.title)
    }

    @Test
    fun `a track is taken even when matches comes back empty`() = runBlocking {
        answer("""{"matches":[],"track":{"key":"7","title":"Song","subtitle":"Artist"}}""")

        assertEquals("Song", identify()?.title)
    }

    @Test
    fun `an answer with no track is a miss, not a failure`() = runBlocking {
        answer("""{"matches":[],"tagid":"E7F3A1","timestamp":1732000000000}""")

        assertNull(identify())
    }

    @Test
    fun `a track with no title is not a match`() = runBlocking {
        answer("""{"track":{"key":"1","subtitle":"Someone"}}""")

        assertNull(identify())
    }

    @Test
    fun `an answer that is not json is a miss`() = runBlocking {
        answer("<html><body>502 Bad Gateway</body></html>")

        assertNull(identify())
    }

    private companion object {
        /** The query Shazam's iPhone client appends to every tag. */
        const val QUERY =
            "?sync=true&webv3=true&sampling=true&connected=" +
                "&shazamapiversion=v3&sharehub=true&hubv5minorversion=v5.1&hidelb=true&video=v3"

        /**
         * A recording of a discovery answer, trimmed to the fields the client
         * reads: the track's name, artist, key and cover art, inside the
         * envelope the endpoint wraps them in. The `matches` entry is what a hit
         * carries — its id is the track's key.
         */
        val MATCH = """
            {
              "matches": [
                {
                  "id": "535384947",
                  "offset": 0.0,
                  "timeskew": 0.0,
                  "frequencyskew": 0.0
                }
              ],
              "location": {},
              "timestamp": 1732000000000,
              "timezone": "Europe/London",
              "tagid": "7B1E4D2A-9C3F-4E8B-8A11-5D6F0C2B9E44",
              "track": {
                "layout": "5",
                "type": "MUSIC",
                "key": "535384947",
                "title": "Blinding Lights",
                "subtitle": "The Weeknd",
                "images": {
                  "background": "https://images.shazam.com/background/blinding-lights.jpg",
                  "coverart": "https://images.shazam.com/coverart/blinding-lights.jpg",
                  "coverarthq": "https://images.shazam.com/coverart/blinding-lights-hq.jpg"
                },
                "share": {
                  "subject": "Blinding Lights - The Weeknd",
                  "href": "https://www.shazam.com/track/535384947/blinding-lights"
                },
                "sections": [
                  {
                    "type": "SONG",
                    "metapages": [
                      { "image": "https://images.shazam.com/coverart/blinding-lights.jpg", "caption": "The Weeknd" }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()
    }
}
