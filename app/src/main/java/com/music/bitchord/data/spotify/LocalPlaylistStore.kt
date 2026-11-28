package com.music.bitchord.data.spotify

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.PlaylistCover
import com.music.bitchord.data.PlaylistCoverStore
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class LocalPlaylist(
    val id: String,
    val title: String,
    val songs: List<Song>,
    /** The 512px WebP written by [PlaylistCoverStore], or null for the normal artwork. */
    val customCoverPath: String? = null,
    /** SHA-256 of [customCoverPath], used to bypass a stale image-cache entry. */
    val coverHash: String? = null,
) {
    val browseId: String get() = "local:playlist:$id"
}

object LocalPlaylistStore {
    private const val PREF_NAME = "bitchord_local_playlists"
    private const val KEY_PLAYLISTS = "playlists_json"

    private lateinit var prefs: SharedPreferences

    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlists = _playlists.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        load()
    }

    private fun load() {
        if (!this::prefs.isInitialized) return
        val raw = prefs.getString(KEY_PLAYLISTS, null) ?: return
        runCatching {
            val list = mutableListOf<LocalPlaylist>()
            val jsonArray = JSONArray(raw)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.getString("id")
                val title = obj.getString("title")
                val customCoverPath = obj.optString("customCoverPath", "")
                    .takeIf { it.isNotBlank() && it != "null" }
                val coverHash = obj.optString("coverHash", "")
                    .takeIf { it.isNotBlank() && it != "null" }
                val songsArray = obj.getJSONArray("songs")
                val songs = mutableListOf<Song>()
                for (j in 0 until songsArray.length()) {
                    val sObj = songsArray.getJSONObject(j)
                    val song = Song(
                        videoId = sObj.optString("videoId", ""),
                        title = sObj.optString("title", ""),
                        artist = sObj.optString("artist", ""),
                        thumbnailUrl = sObj.optString("thumbnailUrl", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        durationText = sObj.optString("durationText", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        artistId = sObj.optString("artistId", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        albumId = sObj.optString("albumId", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        albumName = sObj.optString("albumName", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        isVideo = sObj.optBoolean("isVideo", false),
                    )
                    songs.add(song)
                }
                list.add(LocalPlaylist(id, title, songs, customCoverPath, coverHash))
            }
            _playlists.value = list
        }
    }

    /**
     * Creates a playlist holding [songs], newest first.
     *
     * The id is a timestamp, plus a suffix when that millisecond already names
     * a playlist. Two playlists created in the same frame used to share an id,
     * which made the second replace the first instead of appearing beside it —
     * and, because the cover file is named from the id, could overwrite the
     * first one's artwork too.
     */
    fun savePlaylist(title: String, songs: List<Song>): LocalPlaylist {
        val stamp = System.currentTimeMillis()
        var id = "sp_local_$stamp"
        var suffix = 0
        while (_playlists.value.any { it.id == id }) {
            suffix += 1
            id = "sp_local_${stamp}_$suffix"
        }
        val playlist = LocalPlaylist(id = id, title = title, songs = songs)
        val updated = listOf(playlist) + _playlists.value.filterNot { it.id == id }
        _playlists.value = updated
        persist(updated)
        return playlist
    }

    fun deletePlaylist(id: String) {
        val updated = _playlists.value.filterNot { it.id == id || it.browseId == id || it.id == id.removePrefix("local:playlist:") }
        _playlists.value = updated
        persist(updated)
    }

    fun renamePlaylist(id: String, newTitle: String) {
        val cleanId = id.removePrefix("local:playlist:").removePrefix("VL")
        val updated = _playlists.value.map {
            if (it.id == cleanId || it.id == id || it.browseId == id) {
                it.copy(title = newTitle)
            } else it
        }
        _playlists.value = updated
        persist(updated)
    }

    /**
     * Takes one row of [song] out of the playlist at [browseId]. The first
     * copy, when the playlist holds it twice: rows here carry no per-row id
     * to say which copy was picked, and they play the same either way.
     */
    fun removeSong(browseId: String, song: Song) {
        val updated = _playlists.value.map { playlist ->
            if (playlist.browseId != browseId) return@map playlist
            val index = playlist.songs.indexOfFirst { it.videoId == song.videoId }
            if (index < 0) playlist else playlist.copy(songs = playlist.songs.filterIndexed { i, _ -> i != index })
        }
        _playlists.value = updated
        persist(updated)
    }

    fun getPlaylist(id: String): LocalPlaylist? {
        val cleanId = id.removePrefix("local:playlist:").removePrefix("VL")
        return _playlists.value.firstOrNull { it.id == cleanId || it.id == id || it.browseId == id }
    }

    /**
     * Saves a custom cover for this local playlist.
     *
     * The image is normalized and written by [PlaylistCoverStore]; the model's
     * fields are the local entity's copy of that cover, so a later load can
     * draw it without first reading the cover registry.
     */
    suspend fun setCustomPlaylistCover(
        playlistId: String,
        sourceUriOrPath: String,
    ): Result<LocalPlaylist> = runCatching {
        val playlist = getPlaylist(playlistId) ?: error("Playlist not found")
        val cover = PlaylistCoverStore.setCover(playlist.browseId, sourceUriOrPath).getOrThrow()
        val updated = playlist.copy(customCoverPath = cover.path, coverHash = cover.hash)
        replacePlaylist(updated)
        updated
    }

    /** Deletes the WebP and clears both model fields. */
    suspend fun removeCustomPlaylistCover(playlistId: String): Result<Unit> = runCatching {
        val playlist = getPlaylist(playlistId) ?: error("Playlist not found")
        PlaylistCoverStore.removeCover(playlist.browseId).getOrThrow()
        replacePlaylist(playlist.copy(customCoverPath = null, coverHash = null))
    }

    /**
     * Points every playlist whose cover key is [key] at a cover that has just
     * been restored.
     *
     * A restored cover is written to this device's own directory, so the path
     * the backup came from no longer exists; without this the playlist model
     * would keep naming the old device's file and the list would draw the
     * normal artwork until the playlist was next touched.
     */
    fun applyRestoredCover(key: String, cover: PlaylistCover) {
        val target = PlaylistCoverStore.keyFor(key)
        val updated = _playlists.value.map { playlist ->
            if (PlaylistCoverStore.keyFor(playlist.browseId) == target) {
                playlist.copy(customCoverPath = cover.path, coverHash = cover.hash)
            } else {
                playlist
            }
        }
        if (updated == _playlists.value) return
        _playlists.value = updated
        persist(updated)
    }

    private fun replacePlaylist(updated: LocalPlaylist) {
        val next = _playlists.value.map { if (it.id == updated.id) updated else it }
        _playlists.value = next
        persist(next)
    }

    private fun persist(list: List<LocalPlaylist>) {
        if (!this::prefs.isInitialized) return
        runCatching {
            val jsonArray = JSONArray()
            for (playlist in list) {
                val pObj = JSONObject()
                pObj.put("id", playlist.id)
                pObj.put("title", playlist.title)
                pObj.put("customCoverPath", playlist.customCoverPath)
                pObj.put("coverHash", playlist.coverHash)
                val songsArray = JSONArray()
                for (song in playlist.songs) {
                    val sObj = JSONObject()
                    sObj.put("videoId", song.videoId)
                    sObj.put("title", song.title)
                    sObj.put("artist", song.artist)
                    sObj.put("thumbnailUrl", song.thumbnailUrl)
                    sObj.put("durationText", song.durationText)
                    sObj.put("artistId", song.artistId)
                    sObj.put("albumId", song.albumId)
                    sObj.put("albumName", song.albumName)
                    sObj.put("isVideo", song.isVideo)
                    songsArray.put(sObj)
                }
                pObj.put("songs", songsArray)
                jsonArray.put(pObj)
            }
            prefs.edit().putString(KEY_PLAYLISTS, jsonArray.toString()).apply()
        }
    }
}
