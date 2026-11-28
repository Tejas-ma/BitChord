package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.sources.TrackMatcher

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The automatic-addon switch, at the one place every automatic lookup passes
 * through: the playback source list and the shared resolve engine that races
 * it.
 *
 * Off, an addon is not merely skipped after being asked — it is never reached,
 * which is what the `asked` flags below prove. On, the same list puts it back
 * at the head of the walk, so turning the switch back on restores the old
 * behaviour without touching the stored addon.
 */
class AutoAddonUsageTest {

    private companion object {
        const val TITLE = "Paniyon Sa"
        const val ARTIST = "Atif Aslam"

        /** The other source a track falls through to once the addon is out of the walk. */
        val JIOSAAVN = StreamFormat(codec = "aac", kbps = 320)

        /** Better than [JIOSAAVN], so an addon that is present wins on quality, not on timing. */
        val ADDON = StreamFormat(codec = "flac", sampleRateHz = 44_100, bitDepth = 16)
    }

    private class AnsweringSource(
        override val configId: String,
        override val kind: SourceKind,
        private val format: StreamFormat,
    ) : MusicSource {
        override val displayName = configId

        var asked = false
            private set

        override suspend fun health() = SourceHealth.Ok()

        override suspend fun search(
            query: String,
            limit: Int,
            waitForAll: Boolean,
            request: StreamRequest?,
            target: TrackMatcher.Target?,
        ): List<Song> {
            asked = true
            return listOf(
                Song(
                    videoId = "$configId-1",
                    title = TITLE,
                    artist = ARTIST,
                    thumbnailUrl = null,
                    durationText = "2:00",
                ),
            )
        }

        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream =
            SourceStream(url = "https://$configId/$trackId", format = format)
    }

    private fun target() = TrackMatcher.Target(TITLE, ARTIST, durationSec = 120)

    @Test
    fun `automatic addon usage off never asks the addon and resolves from the next source`() = runBlocking {
        val addon = AnsweringSource("addon", SourceKind.ADDON, ADDON)
        val jio = AnsweringSource("jio", SourceKind.JIOSAAVN, JIOSAAVN)

        val eligible = SourceRegistry.playbackEligible(
            listOf(addon, jio),
            ceiling = AudioQuality.LOSSLESS,
            automaticAddons = false,
        )
        assertEquals(listOf(SourceKind.JIOSAAVN), eligible.map { it.kind })

        val answer = SourceResolver.bestAcross(
            eligible,
            target(),
            StreamRequest.Lossless,
            waitForAll = true,
            strictLength = true,
            requireSharedArtist = true,
            driftSec = 5,
        )

        assertNotNull("the remaining source must still serve the track", answer)
        assertEquals("jio", answer!!.first.configId)
        assertEquals("aac", answer.second.format.codec)
        assertFalse("the addon must not be contacted", addon.asked)
        assertTrue(jio.asked)
    }

    @Test
    fun `automatic addon usage on puts the addon back in the walk and lets it win`() = runBlocking {
        val addon = AnsweringSource("addon", SourceKind.ADDON, ADDON)
        val jio = AnsweringSource("jio", SourceKind.JIOSAAVN, JIOSAAVN)

        val eligible = SourceRegistry.playbackEligible(
            listOf(addon, jio),
            ceiling = AudioQuality.LOSSLESS,
            automaticAddons = true,
        )
        assertEquals(listOf(SourceKind.ADDON, SourceKind.JIOSAAVN), eligible.map { it.kind })

        val answer = SourceResolver.bestAcross(
            eligible,
            target(),
            StreamRequest.Lossless,
            waitForAll = true,
            strictLength = true,
            requireSharedArtist = true,
            driftSec = 5,
        )

        assertNotNull(answer)
        assertEquals("addon", answer!!.first.configId)
        assertEquals("flac", answer.second.format.codec)
        assertTrue(addon.asked)
    }

    /** The ceiling still applies to the addons that remain in the walk. */
    @Test
    fun `an addon is not admitted above the connection's ceiling even when automatic usage is on`() {
        val addon = AnsweringSource("addon", SourceKind.ADDON, ADDON)

        val eligible = SourceRegistry.playbackEligible(
            listOf(addon),
            ceiling = AudioQuality.MEDIUM,
            automaticAddons = true,
        )

        assertTrue(eligible.isEmpty())
    }
}
