package com.music.bitchord.desktop

import androidx.compose.runtime.mutableStateMapOf
import java.awt.FileDialog
import java.awt.Frame
import java.io.FilenameFilter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64
import javax.swing.JOptionPane
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

/** A custom playlist or album cover on disk, plus the hash that invalidates its artwork cache entry. */
data class DesktopPlaylistCover(
    val path: String,
    val hash: String,
)

/**
 * Desktop's local playlist and album cover overlay.
 *
 * YouTube Music has no place for a custom playlist or album image, so this is
 * kept beside the collection rather than in its response: a WebP at
 * `{appDataDir}/bitchord/playlist_covers/{id}.webp`, with a small preference
 * record mapping the collection id to that file and its SHA-256. The file is
 * centre-cropped, drawn to 512×512 and encoded at 80% quality.
 *
 * Albums are keyed by their own browse id in the same map and directory: the
 * two id spaces do not overlap (`MPREb…` albums, `VL…`/local ids playlists),
 * and the directory keeps its original name so covers written by earlier
 * builds are still found.
 */
object DesktopPlaylistCovers {
    private const val TARGET_PX = 512
    private const val WEBP_QUALITY = 80
    private const val KEY_COVERS = "playlist_covers"
    private const val DELIMITER = "#"

    /** Leaves room for `.webp` and a `.tmp` suffix within the usual 255-byte limit. */
    private const val MAX_FILE_NAME_LENGTH = 120

    /** Names Windows reserves for devices, with or without an extension. */
    private val RESERVED_NAMES = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )

    /** The extensions the chooser accepts and Skia decodes on every desktop target. */
    private val SUPPORTED_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

    private val persistence = DesktopPersistence()
    private val _covers = mutableStateMapOf<String, DesktopPlaylistCover>()
    @Volatile
    private var loaded = false

    init {
        // Load before the first composition reads a page, rather than mutating
        // the snapshot map from inside that composition.
        ensureLoaded()
    }

    /** Read by Compose, so replacing or removing a cover redraws every page and card. */
    val covers: Map<String, DesktopPlaylistCover>
        get() {
            ensureLoaded()
            return _covers
        }

    /**
     * Local model ids and page ids name the same playlist; the cover key must
     * too. Album ids are returned unchanged.
     */
    fun keyFor(playlistId: String): String =
        playlistId.trim().removePrefix("local:playlist:")

    /** The file a cover lives in, as a `file:` URL the artwork loader can read. */
    fun fileUrlFor(path: Path): String = path.toAbsolutePath().toUri().toString()

    /** The cover of [browseId] as a `file:` URL, or null when it has none. */
    fun coverUrlFor(browseId: String?): String? =
        coverFor(browseId)?.let { cover ->
            runCatching { fileUrlFor(Path.of(cover.path)) }.getOrNull()
        }

    /**
     * An album's custom cover, if it has one. Albums are not local entities,
     * so the overlay is the only record of it.
     */
    fun albumCoverFor(albumId: String?): DesktopPlaylistCover? = coverFor(albumId)

    suspend fun setCustomAlbumCover(albumId: String, source: Path): Result<DesktopPlaylistCover> =
        setCover(albumId, source)

    suspend fun removeCustomAlbumCover(albumId: String): Result<Unit> = removeCover(albumId)

    fun coverFor(playlistId: String?): DesktopPlaylistCover? {
        val key = playlistId?.takeIf(String::isNotBlank)?.let(::keyFor) ?: return null
        ensureLoaded()
        return _covers[key]?.takeIf { Files.isRegularFile(Path.of(it.path)) }
    }

    /**
     * Normalizes [source] into this playlist's cover and records it.
     *
     * Skia is used rather than ImageIO because Java's built-in readers and
     * writers do not consistently support WebP, while Skia does on every
     * desktop target Compose already ships for.
     */
    suspend fun setCover(playlistId: String, source: Path): Result<DesktopPlaylistCover> {
        ensureLoaded()
        val key = keyFor(playlistId)
        val result = withContext(Dispatchers.IO) {
            runCatching {
                require(key.isNotBlank()) { "A playlist id is required" }
                require(Files.isRegularFile(source)) { "The selected image no longer exists" }
                val decoded = Image.makeFromEncoded(Files.readAllBytes(source))
                val side = minOf(decoded.width, decoded.height)
                require(side > 0) { "The selected file is not an image" }
                val left = (decoded.width - side) / 2
                val top = (decoded.height - side) / 2
                val surface = Surface.makeRasterN32Premul(TARGET_PX, TARGET_PX)
                surface.canvas.drawImageRect(
                    decoded,
                    Rect(left.toFloat(), top.toFloat(), (left + side).toFloat(), (top + side).toFloat()),
                    Rect(0f, 0f, TARGET_PX.toFloat(), TARGET_PX.toFloat()),
                    SamplingMode.MITCHELL,
                    Paint(),
                    true,
                )
                val data = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.WEBP, WEBP_QUALITY)
                    ?: error("The image could not be encoded as WebP")
                val directory = coverDirectory()
                Files.createDirectories(directory)
                val target = directory.resolve(coverFileName(key))
                val temporary = directory.resolve("${target.fileName}.tmp")
                try {
                    Files.write(
                        temporary,
                        data.bytes,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE,
                    )
                    runCatching {
                        Files.move(
                            temporary,
                            target,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE,
                        )
                    }.getOrElse {
                        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally {
                    Files.deleteIfExists(temporary)
                }
                val hash = sha256(target)
                DesktopPlaylistCover(target.toAbsolutePath().toString(), hash)
            }
        }
        result.onSuccess { cover ->
            _covers[key] = cover
            persist()
        }
        return result
    }

    /** Named like the phone's repository operation, for the same edit. */
    suspend fun setCustomPlaylistCover(playlistId: String, source: Path): Result<DesktopPlaylistCover> =
        setCover(playlistId, source)

    suspend fun removeCustomPlaylistCover(playlistId: String): Result<Unit> = removeCover(playlistId)

    suspend fun removeCover(playlistId: String): Result<Unit> {
        ensureLoaded()
        val key = keyFor(playlistId)
        val result = withContext(Dispatchers.IO) {
            runCatching {
                Files.deleteIfExists(coverDirectory().resolve(coverFileName(key)))
                Unit
            }
        }
        result.onSuccess {
            _covers.remove(key)
            persist()
        }
        return result
    }

    /** A native file chooser filtered to the formats Skia can decode here. */
    fun chooseCoverFile(): Path? {
        val dialog = FileDialog(null as Frame?, "Choose cover", FileDialog.LOAD)
        dialog.filenameFilter = FilenameFilter { _, name ->
            name.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS
        }
        dialog.isMultipleMode = false
        dialog.isVisible = true
        val file = dialog.file ?: return null
        val directory = dialog.directory ?: return null
        val chosen = Path.of(directory, file)
        // The native filter is advisory on Windows — a typed name, or the dialog
        // offering an "all files" entry, walks straight past it. Checking here
        // names the formats that work, instead of failing later with a decode
        // error that never says which file or which field was wrong.
        val extension = chosen.fileName?.toString()
            ?.substringAfterLast('.', "")
            ?.lowercase()
            .orEmpty()
        if (extension !in SUPPORTED_EXTENSIONS) {
            JOptionPane.showMessageDialog(
                null,
                "BitChord can use PNG, JPEG or WebP images for a cover.",
                "Unsupported image",
                JOptionPane.WARNING_MESSAGE,
            )
            return null
        }
        return chosen
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            persistence.string(KEY_COVERS, "").lineSequence().forEach { line ->
                val fields = line.split(DELIMITER)
                if (fields.size != 3) return@forEach
                val key = decode(fields[0]).ifBlank { return@forEach }
                val path = decode(fields[1]).ifBlank { return@forEach }
                val hash = fields[2].ifBlank { return@forEach }
                _covers[key] = DesktopPlaylistCover(path, hash)
            }
        }
    }

    private fun persist() {
        val value = _covers.entries.joinToString("\n") { (key, cover) ->
            listOf(encode(key), encode(cover.path), cover.hash).joinToString(DELIMITER)
        }
        persistence.saveString(KEY_COVERS, value)
    }

    private fun coverDirectory(): Path = appDataDirectory().resolve("playlist_covers")

    /** The file name a key is stored under; exposed so the Windows rules can be tested. */
    internal fun coverFileNameFor(key: String): String = coverFileName(key)

    /**
     * A file name that is legal on every target — Windows included.
     *
     * Besides characters Windows refuses, it also has to avoid the reserved
     * device names (`CON`, `NUL`, `COM1`, …), which stay reserved whatever
     * extension is appended, and a component too long for the filesystem.
     * Writing either of those fails at the filesystem rather than in this
     * code, so they are normalised here instead.
     */
    private fun coverFileName(key: String): String {
        val cleaned = key.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trimEnd('.', ' ')
            .ifBlank { "collection" }
            .take(MAX_FILE_NAME_LENGTH)
        val safe = if (cleaned.substringBefore('.').uppercase() in RESERVED_NAMES) "_$cleaned" else cleaned
        return "$safe.webp"
    }

    private fun appDataDirectory(): Path {
        val home = Path.of(System.getProperty("user.home"))
        val os = System.getProperty("os.name").orEmpty()
        val base = when {
            os.startsWith("Windows", ignoreCase = true) ->
                System.getenv("APPDATA")?.takeIf(String::isNotBlank)?.let(Path::of)
                    ?: home.resolve("AppData").resolve("Roaming")

            os.startsWith("Mac", ignoreCase = true) -> home.resolve("Library").resolve("Application Support")
            else ->
                System.getenv("XDG_DATA_HOME")?.takeIf(String::isNotBlank)?.let(Path::of)
                    ?: home.resolve(".local").resolve("share")
        }
        return base.resolve("bitchord")
    }

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String =
        runCatching {
            String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
        }.getOrDefault("")
}
