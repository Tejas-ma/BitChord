package com.music.bitchord.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cover store's Windows-facing rules.
 *
 * A custom cover is a local file, and everything about it that reads a path has
 * to work on the one platform where paths and `file:` URLs do not look like
 * they do elsewhere: Windows. The name a cover is written under, the address
 * the artwork loader is handed, and the way that address is resolved back to a
 * file are pinned here.
 */
class DesktopCollectionCoverTest {

    @Test
    fun coverFileNameIsLegalOnWindows() {
        // Characters Windows refuses, whatever the extension.
        assertEquals("a_b_c.webp", DesktopPlaylistCovers.coverFileNameFor("a:b*c"))
        // Names Windows reserves for devices, with or without an extension.
        assertEquals("_con.webp", DesktopPlaylistCovers.coverFileNameFor("con"))
        assertEquals("_NUL.webp", DesktopPlaylistCovers.coverFileNameFor("NUL"))
        assertEquals("_com1.webp", DesktopPlaylistCovers.coverFileNameFor("com1"))
        assertEquals("collection.webp", DesktopPlaylistCovers.coverFileNameFor("   "))
        // A trailing dot or space silently loses the extension on Windows.
        assertEquals("playlist.webp", DesktopPlaylistCovers.coverFileNameFor("playlist..."))
        // Long ids are cut short rather than failing at the filesystem.
        val long = DesktopPlaylistCovers.coverFileNameFor("x".repeat(400))
        assertTrue(long.length <= 130)
        assertTrue(long.endsWith(".webp"))
    }

    @Test
    fun localAndPageIdsShareOneCoverKey() {
        // A shelf card and the page it opens name the same playlist differently.
        assertEquals("abc", DesktopPlaylistCovers.keyFor("local:playlist:abc"))
        assertEquals("abc", DesktopPlaylistCovers.keyFor("  abc  "))
        // A playlist's page carries the `VL` prefix its own model does not, and
        // both have to name the same cover file or a cover set from a card
        // never appears on the page it belongs to.
        assertEquals("PLabc", DesktopPlaylistCovers.keyFor("VLPLabc"))
        // Album ids are already bare, and stay so.
        assertEquals("MPREb_album", DesktopPlaylistCovers.keyFor("MPREb_album"))
    }

    @Test
    fun aCoverUrlIsAFileUrlWithoutAQuery() {
        val file = image()
        val url = DesktopPlaylistCovers.fileUrlFor(file)
        assertTrue(url, url.startsWith("file:"))
        // No `?v=` cache-buster: a query is not part of a file name, and on
        // Windows it made the address unreadable.
        assertFalse(url, url.contains('?'))
        assertEquals(file.toRealPath(), Path.of(java.net.URI(url)).toRealPath())
    }

    @Test
    fun coverAddressesResolveToTheirFile() {
        val file = image()
        val url = DesktopPlaylistCovers.fileUrlFor(file)
        val expected = file.toRealPath()
        // What the app writes today.
        assertEquals(expected, DesktopArtworkCache.localFileFor(url)?.toRealPath())
        // What earlier builds wrote, with the cache-buster still attached.
        assertEquals(expected, DesktopArtworkCache.localFileFor("$url?v=1699999999")?.toRealPath())
        // A bare path, which is how a chosen file arrives from the picker.
        assertEquals(expected, DesktopArtworkCache.localFileFor(file.toString())?.toRealPath())
        // Anything that is not a local file stays a network fetch.
        assertNull(DesktopArtworkCache.localFileFor("https://example.test/cover.webp"))
        assertNull(DesktopArtworkCache.localFileFor(""))
        // A path that no longer exists is no cover at all.
        assertNull(DesktopArtworkCache.localFileFor("$file.missing"))
    }

    @Test
    fun aDeletedCoverIsNoLongerACover() {
        val file = image()
        val url = DesktopPlaylistCovers.fileUrlFor(file)
        assertNotNull(DesktopArtworkCache.localFileFor(url))
        Files.delete(file)
        assertNull(DesktopArtworkCache.localFileFor(url))
    }

    /** A file on disk with an image extension; only its path is read here. */
    private fun image(): Path =
        Files.createTempFile("bitchord-cover", ".png").also { it.toFile().deleteOnExit() }
}
