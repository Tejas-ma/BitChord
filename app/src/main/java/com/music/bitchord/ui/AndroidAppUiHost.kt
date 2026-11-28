package com.music.bitchord.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.AppleCatalogue
import com.music.bitchord.data.PlaylistCoverStore
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.canvas.AppleArtistArtRepository
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasRepository
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.Downloads
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The shared pages' settings and downloads, answered from the phone's own stores. */
object AndroidAppUiHost : AppUiHost {
    override val swipeToPlayNext = AppSettings.swipeToPlayNext
    override val pinnedPlaylists = AppSettings.pinnedPlaylists
    override val librarySort = AppSettings.librarySort

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The cover registry, keyed by the browse id a shared card already carries.
     *
     * An album is registered under `album:<browseId>` and a playlist under its
     * page id, so both are unwrapped back to the bare id the card knows. The URL
     * carries the cover's hash: it changes when the file does, which is what
     * makes Coil decode the replacement instead of a cached decode of the old
     * file at the same path.
     */
    override val collectionCoverUrls: StateFlow<Map<String, String>> = PlaylistCoverStore.covers
        .map { covers ->
            covers.mapNotNull { (key, cover) ->
                val browseId = if (PlaylistCoverStore.isAlbumKey(key)) {
                    PlaylistCoverStore.albumIdOf(key)
                } else {
                    key
                }
                if (browseId.isBlank() || !File(cover.path).isFile) return@mapNotNull null
                browseId to Uri.fromFile(File(cover.path)).buildUpon()
                    .appendQueryParameter("v", cover.hash)
                    .build()
                    .toString()
            }.toMap()
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    @Composable
    override fun downloadedIds(): Set<String> {
        val saved by Downloads.saved.collectAsStateWithLifecycle()
        return saved.keys
    }

    override suspend fun releaseMotion(album: String, artist: String): CanvasArtwork? {
        if (!AppSettings.animatedCanvas.value || album.isBlank() || artist.isBlank()) return null
        return CanvasRepository.canvasForAlbum(album, artist)
    }

    /**
     * The clip the artist page plays, off the same proven Apple lookup — so
     * opening the artist from the card costs nothing more, and a card never
     * moves with somebody else's face. The YouTube page it is proved against is
     * fetched only when that lookup hasn't settled already.
     */
    override suspend fun artistMotion(browseId: String, artistName: String): CanvasArtwork? {
        if (!AppSettings.animatedCanvas.value) return null
        val art = AppleArtistArtRepository.artFor(browseId, artistName) {
            YtMusicRepository.artistLanding(browseId).getOrNull()?.let(AppleCatalogue::titlesOf).orEmpty()
        }
        return art?.videoUrl?.let { CanvasArtwork(url = it) }
    }
}
