package com.music.bitchord.desktop

import com.music.bitchord.data.model.artworkAt

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.decodeToImageBitmap
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * Decoded artwork, kept to a budget.
 *
 * It used to be an unbounded map. Every distinct URL a session ever loaded stayed decoded for the
 * life of the process, so browsing Explore and Search for a while was hundreds of megabytes of
 * bitmaps nothing would ever ask for again — which is where the gigabyte people reported was going.
 */
internal object DesktopArtworkCache {

    /**
     * A sixth of the heap, within reason.
     *
     * Derived rather than fixed so the budget still makes sense against whatever `-Xmx` the
     * launcher was given, and clamped at both ends so a small heap still caches something and a
     * large one does not decide it may hold half a gigabyte of thumbnails.
     */
    internal val budgetBytes: Long =
        (Runtime.getRuntime().maxMemory() / 6).coerceIn(32L * 1024 * 1024, 128L * 1024 * 1024)

    /** Four bytes a pixel, which is what every format here decodes to. */
    private val images = DesktopByteBudgetLru<ImageBitmap>(budgetBytes) {
        it.width.toLong() * it.height.toLong() * 4L
    }

    /** Everything held, dropped — what the Storage settings' "Clear image cache" does. */
    fun clear() = images.clear()

    suspend fun load(url: String?, cacheKey: String? = null): ImageBitmap? {
        if (url.isNullOrBlank()) return null
        // A local file's cache entry follows its contents: replacing the file
        // at the same path (a new custom cover) must not draw yesterday's
        // cover. Network URLs are already versioned by their own address.
        val key = cacheKey?.takeIf(String::isNotBlank)
            ?: localFileFor(url)?.let { file ->
                "$url@${runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0L)}"
            }
            ?: url
        images.get(key)?.let { return it }
        return runCatching {
            val file = localFileFor(url)
            if (file != null) {
                // Read straight off the disk rather than through a `file:`
                // URLConnection: on Windows a path from `Path.toUri()` reads
                // `file:///C:/…`, and a connection would resolve that to a
                // path Java cannot open, so every custom cover silently
                // failed to decode there.
                Files.readAllBytes(file).decodeToImageBitmap()
            } else {
                URI(url).toURL().openConnection().apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }.getInputStream().use { stream -> stream.readBytes().decodeToImageBitmap() }
            }
        }.getOrNull()?.also { images.put(key, it) }
    }

    /**
     * The file [url] names, when it names one: a `file:` URL or a bare path.
     * Any `?v=`-style query is dropped, since a cache-buster is not part of a
     * file name.
     */
    internal fun localFileFor(url: String): Path? {
        val withoutQuery = url.substringBefore('#').substringBefore('?')
        return runCatching {
            when {
                withoutQuery.startsWith("file:", ignoreCase = true) ->
                    Path.of(URI(withoutQuery)).takeIf(Files::isRegularFile)

                // A bare path has no scheme and no "//"; a URL always has one.
                "://" !in withoutQuery &&
                    !withoutQuery.startsWith("//") &&
                    !withoutQuery.startsWith("data:", ignoreCase = true) ->
                    Path.of(withoutQuery).takeIf(Files::isRegularFile)

                else -> null
            }
        }.getOrNull()
    }
}

/** Artwork, fetched at the size the surface drawing it actually needs. */
@Composable
fun DesktopArtwork(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null,
    px: Int? = null,
    /** Overrides the cache key, e.g. a cover hash so replacing a local file redraws it. */
    cacheKey: String? = null,
) {
    val sized = if (px != null) url.artworkAt(px) else url
    val imageKey = cacheKey?.takeIf(String::isNotBlank) ?: sized.orEmpty()
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = sized, key2 = imageKey) {
        value = withContext(Dispatchers.IO) {
            DesktopArtworkCache.load(sized, imageKey)
        }
    }
    if (bitmap == null) {
        Box(modifier.background(Color(0xFF2B2B35)))
    } else {
        Image(
            painter = BitmapPainter(bitmap!!),
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier,
        )
    }
}
