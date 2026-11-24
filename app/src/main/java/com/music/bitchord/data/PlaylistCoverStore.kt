package com.music.bitchord.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.min

/** A custom playlist cover on disk, and the hash that invalidates image caches when it changes. */
data class PlaylistCover(
    val path: String,
    val hash: String,
)

/**
 * The account's local overlay of playlist covers.
 *
 * Covers are not a YouTube feature: there is nowhere in the playlist response
 * to put one, and a read-only or saved playlist must not be given one. So the
 * file and its metadata live here, keyed by the playlist's page or local id,
 * and the UI draws it over the normal thumbnail only for playlists whose edit
 * controls are already shown.
 *
 * The file is centre-cropped to a square, scaled to 512×512 and encoded as
 * WebP at 80% quality under `filesDir/playlist_covers`. Keeping the hash beside
 * it lets Coil bypass a cached decode of the old file at the same path.
 */
object PlaylistCoverStore {
    private const val PREF_NAME = "bitchord_playlist_covers"
    private const val KEY_COVERS = "covers_json"
    private const val TARGET_PX = 512
    private const val WEBP_QUALITY = 80

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences

    private val _covers = MutableStateFlow<Map<String, PlaylistCover>>(emptyMap())
    val covers = _covers.asStateFlow()

    fun init(context: Context) {
        if (this::appContext.isInitialized) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        load()
    }

    /** Local model ids and page ids name the same playlist; the cover key must too. */
    fun keyFor(playlistId: String): String =
        playlistId.trim().removePrefix("local:playlist:")

    fun coverFor(playlistId: String?): PlaylistCover? {
        val key = playlistId?.takeIf(String::isNotBlank)?.let(::keyFor) ?: return null
        return _covers.value[key]?.takeIf { File(it.path).isFile }
    }

    /**
     * Copies, crops and compresses [sourceUriOrPath] into this playlist's cover.
     *
     * Android hands the picker's result back as a `content://` URI, so the
     * stream is copied immediately rather than relying on a permission that
     * does not outlive the activity result.
     */
    suspend fun setCover(playlistId: String, sourceUriOrPath: String): Result<PlaylistCover> =
        runCatching {
            require(this::appContext.isInitialized) { "PlaylistCoverStore has not been initialised" }
            val key = keyFor(playlistId)
            require(key.isNotBlank()) { "A playlist id is required" }
            val cover = withContext(Dispatchers.IO) { writeCover(key, sourceUriOrPath) }
            _covers.value = _covers.value + (key to cover)
            persist()
            cover
        }

    suspend fun removeCover(playlistId: String): Result<Unit> = runCatching {
        val key = keyFor(playlistId)
        withContext(Dispatchers.IO) {
            val file = File(coverDirectory(), coverFileName(key))
            if (file.exists() && !file.delete()) {
                error("The playlist cover file could not be deleted")
            }
        }
        _covers.value = _covers.value - key
        persist()
    }

    private fun writeCover(key: String, sourceUriOrPath: String): PlaylistCover {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openSource(sourceUriOrPath).use { BitmapFactory.decodeStream(it, null, bounds) }
        val width = bounds.outWidth
        val height = bounds.outHeight
        require(width > 0 && height > 0) { "The selected file is not an image" }

        // Decode no larger than needed: a modern gallery photo is often tens of
        // megapixels, and decoding all of it just to crop a 512px square can
        // exhaust the heap before any downscale happens.
        var sample = 1
        while (min(width, height) / (sample * 2) >= TARGET_PX) sample *= 2
        val decoded = openSource(sourceUriOrPath).use { stream ->
            BitmapFactory.decodeStream(
                stream,
                null,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } ?: error("The selected file could not be decoded")

        val squareSize = min(decoded.width, decoded.height)
        val left = (decoded.width - squareSize) / 2
        val top = (decoded.height - squareSize) / 2
        val square = Bitmap.createBitmap(decoded, left, top, squareSize, squareSize)
        val scaled = if (squareSize == TARGET_PX) {
            square
        } else {
            Bitmap.createScaledBitmap(square, TARGET_PX, TARGET_PX, true)
        }

        val directory = coverDirectory()
        if (!directory.isDirectory && !directory.mkdirs()) {
            error("The playlist cover folder could not be created")
        }
        val target = File(directory, coverFileName(key))
        val temporary = File(directory, "${target.name}.tmp")
        try {
            FileOutputStream(temporary).use { out ->
                check(scaled.compress(webpFormat(), WEBP_QUALITY, out)) {
                    "The image could not be encoded as WebP"
                }
            }
            if (target.exists() && !target.delete()) {
                error("The old playlist cover could not be replaced")
            }
            check(temporary.renameTo(target)) { "The playlist cover could not be saved" }
        } finally {
            temporary.delete()
            if (scaled !== decoded) scaled.recycle()
            if (square !== decoded && square !== scaled) square.recycle()
            decoded.recycle()
        }
        return PlaylistCover(target.absolutePath, sha256(target))
    }

    @Suppress("DEPRECATION")
    private fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            // WEBP on API 26–29 is the lossy encoder; the explicit variant
            // arrived with Android 11.
            Bitmap.CompressFormat.WEBP
        }

    private fun openSource(source: String): InputStream = when {
        source.startsWith("content://") -> appContext.contentResolver.openInputStream(Uri.parse(source))
            ?: error("The selected image could not be opened")

        source.startsWith("file://") -> Uri.parse(source).path?.let(::File)?.inputStream()
            ?: error("The selected image could not be opened")

        else -> FileInputStream(File(source))
    }

    private fun coverDirectory(): File = File(appContext.filesDir, "playlist_covers")

    /** Safe on every platform Android targets; the id itself may contain `:`. */
    private fun coverFileName(key: String): String =
        key.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "playlist" } + ".webp"

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    private fun load() {
        val raw = prefs.getString(KEY_COVERS, null) ?: return
        runCatching {
            val root = JSONObject(raw)
            _covers.value = buildMap {
                root.keys().forEach { key ->
                    val entry = root.optJSONObject(key) ?: return@forEach
                    val path = entry.optString("path").takeIf(String::isNotBlank) ?: return@forEach
                    val hash = entry.optString("hash").takeIf(String::isNotBlank) ?: return@forEach
                    put(key, PlaylistCover(path, hash))
                }
            }
        }
    }

    private fun persist() {
        if (!this::prefs.isInitialized) return
        val root = JSONObject()
        _covers.value.forEach { (key, cover) ->
            root.put(
                key,
                JSONObject()
                    .put("path", cover.path)
                    .put("hash", cover.hash),
            )
        }
        prefs.edit().putString(KEY_COVERS, root.toString()).apply()
    }
}
