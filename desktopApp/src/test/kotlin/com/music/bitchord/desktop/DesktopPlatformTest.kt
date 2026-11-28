package com.music.bitchord.desktop

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The three things a port has to get right before any window opens: which CPU
 * this is, where its files go, and which installer an update should fetch.
 *
 * All three are pure functions of inputs a test supplies, so the macOS and
 * Windows answers can be checked on the Linux machine that runs the suite.
 */
class DesktopPlatformTest {

    @Test
    fun `every spelling a JDK uses for the same CPU lands on two names`() {
        listOf("aarch64", "arm64", "ARM64", "armv8", "arm").forEach { raw ->
            assertEquals(DesktopPlatform.ARCH_ARM64, DesktopPlatform.archOf(raw), raw)
        }
        listOf("amd64", "x86_64", "x86-64", "AMD64", "i386", "unknown").forEach { raw ->
            assertEquals(DesktopPlatform.ARCH_X64, DesktopPlatform.archOf(raw), raw)
        }
    }

    @Test
    fun `macOS data and cache follow Apple's conventions, not XDG`() {
        val home = "/Users/listener"
        val env = mapOf("XDG_DATA_HOME" to "/tmp/xdg-data", "XDG_CACHE_HOME" to "/tmp/xdg-cache")

        assertEquals(
            Path.of("$home/Library/Application Support/bitchord"),
            DesktopDirectories.dataDirectory("Mac OS X", home, env),
        )
        assertEquals(
            Path.of("$home/Library/Caches/bitchord"),
            DesktopDirectories.cacheDirectory("Mac OS X", home, env),
        )
        assertEquals(
            Path.of("$home/Library/Application Support/bitchord"),
            DesktopDirectories.configDirectory("Mac OS X", home, env),
        )
    }

    @Test
    fun `Linux honours XDG variables and falls back to the home defaults`() {
        val home = "/home/listener"
        val env = mapOf(
            "XDG_DATA_HOME" to "/data",
            "XDG_CACHE_HOME" to "/cache",
            "XDG_CONFIG_HOME" to "/config",
        )
        assertEquals(Path.of("/data/bitchord"), DesktopDirectories.dataDirectory("Linux", home, env))
        assertEquals(Path.of("/cache/bitchord"), DesktopDirectories.cacheDirectory("Linux", home, env))
        assertEquals(Path.of("/config/bitchord"), DesktopDirectories.configDirectory("Linux", home, env))

        assertEquals(
            Path.of("$home/.local/share/bitchord"),
            DesktopDirectories.dataDirectory("Linux", home, emptyMap()),
        )
        assertEquals(
            Path.of("$home/.cache/bitchord"),
            DesktopDirectories.cacheDirectory("Linux", home, emptyMap()),
        )
        assertEquals(
            Path.of("$home/.config/bitchord"),
            DesktopDirectories.configDirectory("Linux", home, emptyMap()),
        )
    }

    @Test
    fun `Windows keeps data in APPDATA and puts the cache in LOCALAPPDATA`() {
        val home = "C:\\Users\\listener"
        val roaming = "C:\\Users\\listener\\AppData\\Roaming"
        val local = "C:\\Local"
        val env = mapOf("APPDATA" to roaming, "LOCALAPPDATA" to local)

        // Compared through Path rather than as strings: this test runs on
        // whichever host the suite is on, and only the resolver's own joins
        // should decide the separators.
        assertEquals(
            Path.of(roaming).resolve("bitchord"),
            DesktopDirectories.dataDirectory("Windows 11", home, env),
        )
        assertEquals(
            Path.of(local).resolve("bitchord"),
            DesktopDirectories.cacheDirectory("Windows 11", home, env),
        )
        // LOCALAPPDATA is the Windows answer even when an XDG variable is
        // exported beside it: Windows is decided first, on purpose.
        assertEquals(
            Path.of(local).resolve("bitchord"),
            DesktopDirectories.cacheDirectory(
                "Windows 11",
                home,
                mapOf("LOCALAPPDATA" to local, "XDG_CACHE_HOME" to "C:\\Portable"),
            ),
        )
    }

    @Test
    fun `downloads live under the listener's music folder on every desktop`() {
        assertEquals(Path.of("/home/listener/Music"), DesktopDirectories.musicDirectory("/home/listener"))
    }

    @Test
    fun `the update for an Intel Mac is not the Apple Silicon disk image`() {
        assertEquals(listOf("-macos-arm64.dmg", "-macos-aarch64.dmg"), DesktopUpdateChecker.installerSuffixes("macos", "arm64"))
        assertEquals(
            listOf("-macos-x64.dmg", "-macos-x86_64.dmg", "-macos-intel.dmg"),
            DesktopUpdateChecker.installerSuffixes("macos", "x64"),
        )
        assertFalse(DesktopUpdateChecker.installerSuffixes("macos", "x64").any { it.contains("arm64") })
    }

    @Test
    fun `Linux arm64 and x64 look for their own assets`() {
        assertTrue(DesktopUpdateChecker.installerSuffixes("linux", "arm64").first().contains("arm64"))
        assertTrue(DesktopUpdateChecker.installerSuffixes("linux", "x64").first().contains("x86_64"))
        // The generic AppImage is last, for a release that predates per-arch names.
        assertEquals(".AppImage", DesktopUpdateChecker.installerSuffixes("linux", "arm64").last())
        assertEquals(".AppImage", DesktopUpdateChecker.installerSuffixes("linux", "x64").last())
    }

    @Test
    fun `Windows and unknown platforms`() {
        assertEquals(listOf("-windows-x64-setup.exe"), DesktopUpdateChecker.installerSuffixes("windows", "x64"))
        assertTrue(DesktopUpdateChecker.installerSuffixes("plan9", "x64").isEmpty())
    }
}
