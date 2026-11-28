package com.music.bitchord.data.search

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedSearchTest {

    private fun song(id: String, title: String = id) =
        Song(videoId = id, title = title, artist = "Artist", thumbnailUrl = null)

    private class Fake(
        override val id: String,
        override val label: String,
        override val kind: SearchSourceKind,
        private val delayMs: Long = 0,
        private val answer: suspend (String) -> List<Song>,
    ) : UnifiedSearchProvider {
        override suspend fun search(query: String, limit: Int): List<Song> {
            if (delayMs > 0) delay(delayMs)
            return answer(query).take(limit)
        }
    }

    @Test
    fun `every source is asked and each batch arrives before the slow one`() = runBlocking {
        val engine = UnifiedSearchEngine(
            providers = {
                listOf(
                    Fake("fast", "Fast", SearchSourceKind.YOUTUBE) { listOf(song("y1")) },
                    Fake("slow", "Slow", SearchSourceKind.ADDON, delayMs = 500) { listOf(song("a1")) },
                )
            },
            timeoutMs = 5_000,
        )

        val events = engine.search("query").toList()
        val batches = events.filterIsInstance<UnifiedSearchEvent.Batch>()
        assertEquals(2, batches.size)
        // The fast catalogue's batch is emitted while the slow one is still busy.
        assertTrue(events.indexOf(batches[0]) < events.indexOf(batches[1]))
        assertTrue(events[0] is UnifiedSearchEvent.Started)

        val done = events.last() as UnifiedSearchEvent.Done
        // Addons rank above YouTube in the unified order, so the addon row leads
        // even though YouTube answered first.
        assertEquals(listOf("a1", "y1"), done.hits.map { it.song.videoId })
        assertEquals(2, done.hits.map { it.sourceLabel }.distinct().size)
    }

    @Test
    fun `a failing source costs only its own rows`() = runBlocking {
        val engine = UnifiedSearchEngine(
            providers = {
                listOf(
                    Fake("good", "Good", SearchSourceKind.YOUTUBE) { listOf(song("y1")) },
                    Fake("bad", "Bad", SearchSourceKind.JIOSAAVN) { error("catalogue down") },
                )
            },
        )

        val events = engine.search("query").toList()
        val failure = events.filterIsInstance<UnifiedSearchEvent.Failed>().single()
        assertEquals("bad", failure.failure.sourceId)
        assertEquals("catalogue down", failure.failure.reason)

        val done = events.last() as UnifiedSearchEvent.Done
        assertEquals(listOf("y1"), done.hits.map { it.song.videoId })
        assertEquals(1, done.failures.size)
    }

    @Test
    fun `a hung source times out without holding the page`() = runBlocking {
        val engine = UnifiedSearchEngine(
            providers = {
                listOf(
                    Fake("quick", "Quick", SearchSourceKind.YOUTUBE) { listOf(song("y1")) },
                    Fake("hung", "Hung", SearchSourceKind.ADDON, delayMs = 10_000) { emptyList() },
                )
            },
            timeoutMs = 200,
        )

        val events = engine.search("query").toList()
        val done = events.last() as UnifiedSearchEvent.Done
        assertEquals(listOf("y1"), done.hits.map { it.song.videoId })
        assertTrue(done.failures.single().reason.startsWith("Timed out"))
    }

    @Test
    fun `blank query asks nobody`() = runBlocking {
        var asked = false
        val engine = UnifiedSearchEngine(
            providers = {
                listOf(
                    Fake("one", "One", SearchSourceKind.YOUTUBE) {
                        asked = true
                        emptyList()
                    },
                )
            },
        )

        val events = engine.search("   ").toList()
        assertTrue(!asked)
        assertEquals(2, events.size)
        assertEquals(emptyList<UnifiedSearchHit>(), (events.last() as UnifiedSearchEvent.Done).hits)
    }

    @Test
    fun `sources are listed in rank order`() {
        val engine = UnifiedSearchEngine(
            providers = {
                listOf(
                    Fake("yt", "YouTube Music", SearchSourceKind.YOUTUBE) { emptyList() },
                    Fake("jio", "JioSaavn", SearchSourceKind.JIOSAAVN) { emptyList() },
                    Fake("local", "On this device", SearchSourceKind.LOCAL) { emptyList() },
                )
            },
        )
        assertEquals(listOf("local", "jio", "yt"), engine.sources().map { it.id })
    }

    @Test
    fun `interleave puts the best of every source on the first screen`() {
        fun hit(id: String, source: String) = UnifiedSearchHit(
            song = song(id),
            sourceId = source,
            sourceLabel = source,
            sourceKind = SearchSourceKind.OTHER,
        )
        val merged = UnifiedSearchEngine.interleave(
            listOf(
                UnifiedSearchBatch("a", "A", SearchSourceKind.OTHER, listOf(hit("a1", "A"), hit("a2", "A"))),
                UnifiedSearchBatch("b", "B", SearchSourceKind.OTHER, listOf(hit("b1", "B"))),
            ),
        )
        assertEquals(listOf("a1", "b1", "a2"), merged.map { it.song.videoId })
    }

    @Test
    fun `per-source limit is applied`() = runBlocking {
        val engine = UnifiedSearchEngine(
            providers = {
                listOf(
                    Fake("many", "Many", SearchSourceKind.YOUTUBE) {
                        (1..50).map { song("s$it") }
                    },
                )
            },
        )
        val done = engine.search("query", perSourceLimit = 3).toList().last() as UnifiedSearchEvent.Done
        assertEquals(3, done.hits.size)
    }
}
