package com.music.bitchord.desktop

import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.search.SearchSourceKind
import com.music.bitchord.data.search.UnifiedSearchEngine
import com.music.bitchord.data.search.UnifiedSearchProvider

/**
 * The desktop's unified search: every configured catalogue, asked at once.
 *
 * The search page used to be YouTube Music's alone — the sidebar's field ran
 * `DesktopSearchClient.search` and nothing else — so a track held by a
 * configured addon, module index or JioSaavn was only reachable by opening the
 * Addons page. Each configured source is wrapped here as a
 * [UnifiedSearchProvider], and [engine] does the fan-out.
 */
internal object DesktopUnifiedSearch {

    val engine = UnifiedSearchEngine(::providers)

    /** The catalogues a search asks, in the order the chips list them. */
    private fun providers(): List<UnifiedSearchProvider> = buildList {
        add(YouTube)
        DesktopPersistence().sourceConfigs()
            .inSourceOrder()
            .filter { it.enabled && it.isComplete && it.kind != DesktopSourceKind.YOUTUBE }
            .forEach { config ->
                when (config.kind) {
                    DesktopSourceKind.ADDON -> add(Addons(config))
                    DesktopSourceKind.CUSTOM_MODULE, DesktopSourceKind.MODULE -> add(Modules(config))
                    DesktopSourceKind.JIOSAAVN -> add(Jio(config))
                    DesktopSourceKind.YOUTUBE -> Unit
                }
            }
    }

    private fun List<SearchResult>.songs(): List<Song> =
        mapNotNull { (it as? SearchResult.Track)?.song }

    private object YouTube : UnifiedSearchProvider {
        override val id = "youtube"
        override val label = "YouTube Music"
        override val kind = SearchSourceKind.YOUTUBE

        override suspend fun search(query: String, limit: Int): List<Song> =
            DesktopSearchClient.search(query, SearchFilter.SONGS)
                .getOrThrow()
                .songs()
    }

    private class Addons(private val config: DesktopSourceConfig) : UnifiedSearchProvider {
        override val id = config.id
        override val label = config.displayName
        override val kind = SearchSourceKind.ADDON

        override suspend fun search(query: String, limit: Int): List<Song> =
            DesktopAddonSource.search(config, query, limit).getOrThrow().songs()
    }

    private class Modules(private val config: DesktopSourceConfig) : UnifiedSearchProvider {
        override val id = config.id
        override val label = config.displayName
        override val kind = SearchSourceKind.MODULE

        override suspend fun search(query: String, limit: Int): List<Song> =
            DesktopModuleSource.search(config, query, limit).getOrThrow().songs()
    }

    private class Jio(private val config: DesktopSourceConfig) : UnifiedSearchProvider {
        override val id = config.id
        override val label = config.displayName
        override val kind = SearchSourceKind.JIOSAAVN

        override suspend fun search(query: String, limit: Int): List<Song> =
            DesktopJioSaavn.search(query, limit).getOrThrow().songs()
    }
}
