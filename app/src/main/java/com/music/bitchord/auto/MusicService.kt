package com.music.bitchord.auto

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.music.bitchord.playback.PlaybackService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class MusicService : MediaLibraryService() {

    private var librarySession: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        val player = PlaybackService.globalPlayer
        if (player != null) {
            librarySession = MediaLibrarySession.Builder(this, player, CustomMediaLibrarySessionCallback()).build()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return librarySession
    }

    override fun onDestroy() {
        librarySession?.release()
        librarySession = null
        super.onDestroy()
    }

    private inner class CustomMediaLibrarySessionCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootItem = MediaItem.Builder()
                .setMediaId("root")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>> {
            if (parentId == "root") {
                val recentItem = MediaItem.Builder()
                    .setMediaId("recent")
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle("Recent")
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .build()
                    )
                    .build()

                val libraryItem = MediaItem.Builder()
                    .setMediaId("library")
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle("Library")
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .build()
                    )
                    .build()

                return Futures.immediateFuture(
                    LibraryResult.ofItemList(
                        listOf(recentItem, libraryItem),
                        params
                    )
                )
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(emptyList(), params))
        }
    }
}
