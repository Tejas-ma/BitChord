package com.music.bitchord.data.search

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Which catalogue a unified hit came from, for the badge beside its row. */
enum class SearchSourceKind(val label: String, val rank: Int) {
    LOCAL("Local", 0),
    ADDON("Addon", 1),
    MODULE("Module", 2),
    JIOSAAVN("JioSaavn", 3),
    YOUTUBE("YouTube Music", 4),
    OTHER("Source", 5),
}

/**
 * One catalogue a unified search can ask.
 *
 * Implemented once per platform over the sources that platform already
 * resolves streams from — the phone's [com.music.bitchord.data.sources.MusicSource]
 * instances, the desktop's source registry — so this file knows nothing about
 * either and both get the same fan-out, ordering and failure handling.
 *
 * [search] may throw; one provider throwing costs its own rows and nothing else.
 */
interface UnifiedSearchProvider {
    /** Stable within one search, and what the source chips are keyed by. */
    val id: String

    /** What the badge and the chip say. */
    val label: String

    val kind: SearchSourceKind

    suspend fun search(query: String, limit: Int): List<Song>
}

/** One row of the unified page: the track, and the catalogue that returned it. */
data class UnifiedSearchHit(
    val song: Song,
    val sourceId: String,
    val sourceLabel: String,
    val sourceKind: SearchSourceKind,
)

/** A catalogue's answer, landing as its own batch rather than waiting for the slowest. */
data class UnifiedSearchBatch(
    val sourceId: String,
    val sourceLabel: String,
    val sourceKind: SearchSourceKind,
    val hits: List<UnifiedSearchHit>,
)

/** A catalogue that did not answer, and why — shown as a chip rather than hidden. */
data class UnifiedSearchFailure(
    val sourceId: String,
    val sourceLabel: String,
    val reason: String,
)

sealed interface UnifiedSearchEvent {
    /** Every catalogue being asked, so the page can show its chips before the first row. */
    data class Started(val sources: List<UnifiedSearchSource>) : UnifiedSearchEvent

    /** One catalogue's rows. Batches arrive in whatever order the sources answer. */
    data class Batch(val batch: UnifiedSearchBatch) : UnifiedSearchEvent

    data class Failed(val failure: UnifiedSearchFailure) : UnifiedSearchEvent

    /** Everything that arrived, merged round-robin in the sources' rank order. */
    data class Done(
        val hits: List<UnifiedSearchHit>,
        val failures: List<UnifiedSearchFailure>,
    ) : UnifiedSearchEvent
}

/** What [UnifiedSearchEvent.Started] names: the id, label and kind of one catalogue. */
data class UnifiedSearchSource(
    val id: String,
    val label: String,
    val kind: SearchSourceKind,
) {
    companion object {
        fun of(provider: UnifiedSearchProvider) =
            UnifiedSearchSource(provider.id, provider.label, provider.kind)
    }
}

/**
 * Searches every configured catalogue at once, and reports each one as it
 * answers.
 *
 * The phone used to search one source at a time — YouTube, then the addons,
 * then JioSaavn — and the desktop searched only YouTube, so a track that a
 * configured FLAC addon had was invisible until the user switched source.
 * This asks them all in parallel and streams the answers in as batches: the
 * first catalogue to reply paints rows while the rest are still working, and
 * a catalogue that times out or fails costs only its own batch.
 *
 * Results keep the order the sources are configured in
 * ([SearchSourceKind.rank], then the providers' own order), and within one
 * source the order the source itself returned, which is its confidence order.
 * Rows are not de-duplicated across sources: the same recording from YouTube
 * and from an addon are two rows that play differently, and the chips are how
 * a listener picks between them.
 */
class UnifiedSearchEngine(
    /** Read per search, so turning a source on in Settings takes effect on the next one. */
    private val providers: () -> List<UnifiedSearchProvider>,
    /** How long a catalogue may take before its batch is given up on. */
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    /** The catalogues a search would ask, in rank order. */
    fun sources(): List<UnifiedSearchSource> =
        ordered(providers()).map(UnifiedSearchSource::of)

    /**
     * [query] across every enabled catalogue, as a stream of batches.
     *
     * Completes after [UnifiedSearchEvent.Done]; every batch is also repeated
     * in that final event, so a collector that only wants the finished list can
     * ignore everything else. Blank queries complete immediately with nothing.
     */
    fun search(query: String, perSourceLimit: Int = DEFAULT_PER_SOURCE_LIMIT): Flow<UnifiedSearchEvent> =
        channelFlow {
            val trimmed = query.trim()
            val active = ordered(providers())
            send(UnifiedSearchEvent.Started(active.map(UnifiedSearchSource::of)))
            if (trimmed.isEmpty() || active.isEmpty()) {
                send(UnifiedSearchEvent.Done(emptyList(), emptyList()))
                return@channelFlow
            }

            val mutex = Mutex()
            val batches = mutableListOf<UnifiedSearchBatch>()
            val failures = mutableListOf<UnifiedSearchFailure>()

            coroutineScope {
                active.forEach { provider ->
                    launch(Dispatchers.IO) {
                        val answer = withTimeoutOrNull(timeoutMs) {
                            runCatching { provider.search(trimmed, perSourceLimit) }
                        }
                        when {
                            answer == null -> mutex.withLock {
                                val failure = UnifiedSearchFailure(
                                    provider.id,
                                    provider.label,
                                    "Timed out after ${timeoutMs / 1000}s",
                                )
                                failures += failure
                                send(UnifiedSearchEvent.Failed(failure))
                            }

                            answer.isFailure -> mutex.withLock {
                                val failure = UnifiedSearchFailure(
                                    provider.id,
                                    provider.label,
                                    answer.exceptionOrNull()?.message?.takeIf(String::isNotBlank)
                                        ?: "Search failed",
                                )
                                failures += failure
                                send(UnifiedSearchEvent.Failed(failure))
                            }

                            else -> {
                                val hits = answer.getOrThrow()
                                    .take(perSourceLimit)
                                    .map { song ->
                                        UnifiedSearchHit(
                                            song = song,
                                            sourceId = provider.id,
                                            sourceLabel = provider.label,
                                            sourceKind = provider.kind,
                                        )
                                    }
                                val batch = UnifiedSearchBatch(
                                    provider.id,
                                    provider.label,
                                    provider.kind,
                                    hits,
                                )
                                mutex.withLock {
                                    batches += batch
                                    send(UnifiedSearchEvent.Batch(batch))
                                }
                            }
                        }
                    }
                }
            }

            val orderedBatches = batches.sortedWith(
                compareBy({ it.sourceKind.rank }, { it.sourceLabel.lowercase() }),
            )
            send(UnifiedSearchEvent.Done(interleave(orderedBatches), failures.toList()))
        }.flowOn(Dispatchers.IO)

    /** Enabled providers in the order the chips should list them. */
    private fun ordered(list: List<UnifiedSearchProvider>): List<UnifiedSearchProvider> =
        list.sortedWith(
            compareBy<UnifiedSearchProvider>({ it.kind.rank }, { it.label.lowercase() }, { it.id }),
        )

    companion object {
        /** Long enough for a cold addon index, short enough that the page settles. */
        const val DEFAULT_TIMEOUT_MS: Long = 20_000L

        const val DEFAULT_PER_SOURCE_LIMIT: Int = 12

        /**
         * One row of each source, then the next row of each, so the unified
         * list is not one catalogue followed by another: with a 12-row limit
         * per source, taking sources in turn puts the best answer from every
         * catalogue on the first screenful.
         */
        fun interleave(batches: List<UnifiedSearchBatch>): List<UnifiedSearchHit> = buildList {
            val longest = batches.maxOfOrNull { it.hits.size } ?: 0
            for (index in 0 until longest) {
                for (batch in batches) {
                    batch.hits.getOrNull(index)?.let(::add)
                }
            }
        }
    }
}
