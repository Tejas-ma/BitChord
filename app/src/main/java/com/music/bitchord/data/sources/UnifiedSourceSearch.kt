package com.music.bitchord.data.sources

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.search.SearchSourceKind
import com.music.bitchord.data.search.UnifiedSearchEngine
import com.music.bitchord.data.search.UnifiedSearchProvider

/**
 * The phone's unified search: every configured [MusicSource], asked at once.
 *
 * A [MusicSource] already returns [Song]s tagged with its own id, so playing a
 * row from here resolves back through the source it came from without any
 * extra bookkeeping. [SourceRegistry.searchable] supplies the sources rather
 * than [SourceRegistry.active]: a source skipped for *playback* because of the
 * connection's quality ceiling still has rows worth showing, and the search
 * page says why a row cannot be played instead of hiding it.
 */
object UnifiedSourceSearch {

    val engine = UnifiedSearchEngine(
        providers = { SourceRegistry.searchable().map { source -> Adapter(source) } },
    )

    private class Adapter(private val source: MusicSource) : UnifiedSearchProvider {
        override val id: String get() = source.configId
        override val label: String get() = source.displayName
        override val kind: SearchSourceKind
            get() = when (source.kind) {
                SourceKind.YOUTUBE -> SearchSourceKind.YOUTUBE
                SourceKind.JIOSAAVN -> SearchSourceKind.JIOSAAVN
                SourceKind.ADDON -> SearchSourceKind.ADDON
                SourceKind.MODULE, SourceKind.CUSTOM_MODULE -> SearchSourceKind.MODULE
            }

        override suspend fun search(query: String, limit: Int): List<Song> =
            source.search(query, limit)
    }
}
