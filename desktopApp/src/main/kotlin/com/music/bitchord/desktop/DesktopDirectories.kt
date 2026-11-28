package com.music.bitchord.desktop

import java.nio.file.Files
import java.nio.file.Path

/**
 * Where a desktop keeps its files, on each of the three systems this app ships
 * for.
 *
 * Before this, three places each decided for themselves: the cover store knew
 * about `%APPDATA%`, `Application Support` and `XDG_DATA_HOME`, the media cache
 * only knew the XDG cache, and the download folder assumed `~/Music` — which is
 * the one thing all three systems happen to agree on. A macOS build therefore
 * put motion-artwork clips in `~/.cache`, a directory macOS neither creates nor
 * cleans, and a Windows build would have done the same. One resolver, with the
 * environment kept as a parameter, means the rules can be tested for all three
 * systems on any one of them.
 *
 * Everything is `<base>/bitchord` plus a purpose, which is what the existing
 * installs already use on Linux and Windows — so no data moves on upgrade.
 */
internal object DesktopDirectories {

    /** Stable files: custom covers, anything that must survive a cache purge. */
    val data: Path get() = dataDirectory(osName(), userHome(), environment())

    /** Disposable media: artwork clips, decoded caches. */
    val cache: Path get() = cacheDirectory(osName(), userHome(), environment())

    /** The listener's music, and this app's download folder inside it. */
    val music: Path get() = musicDirectory(userHome())

    val downloads: Path get() = music.resolve(DOWNLOAD_FOLDER)

    fun dataDirectory(os: String, home: String, env: Map<String, String>): Path =
        baseDirectory(os, home, env, APP_DIR_NAME)

    fun cacheDirectory(os: String, home: String, env: Map<String, String>): Path {
        val homePath = Path.of(home)
        val base = when {
            // LOCALAPPDATA is the per-user, non-roaming location Windows
            // intends for caches; APPDATA is for settings that follow a user.
            os.startsWith("Windows", ignoreCase = true) ->
                env["LOCALAPPDATA"]?.takeIf(String::isNotBlank)?.let(Path::of)
                    ?: homePath.resolve("AppData").resolve("Local")

            os.startsWith("Mac", ignoreCase = true) -> homePath.resolve("Library").resolve("Caches")

            else ->
                env["XDG_CACHE_HOME"]?.takeIf(String::isNotBlank)?.let(Path::of)
                    ?: homePath.resolve(".cache")
        }
        return base.resolve(APP_DIR_NAME)
    }

    /**
     * Settings that are not already in `java.util.prefs`.
     *
     * The same directory as [data] on Windows and macOS, where no separate
     * config convention exists; `$XDG_CONFIG_HOME` on Linux.
     */
    fun configDirectory(os: String, home: String, env: Map<String, String>): Path {
        if (!os.startsWith("Windows", ignoreCase = true) && !os.startsWith("Mac", ignoreCase = true)) {
            val base = env["XDG_CONFIG_HOME"]?.takeIf(String::isNotBlank)?.let(Path::of)
                ?: Path.of(home).resolve(".config")
            return base.resolve(APP_DIR_NAME)
        }
        return dataDirectory(os, home, env)
    }

    fun musicDirectory(home: String): Path = Path.of(home).resolve("Music")

    /** Creates [path] if it is not there, and returns it; a failure leaves the path to fail later. */
    fun ensure(path: Path): Path {
        runCatching { Files.createDirectories(path) }
        return path
    }

    private fun baseDirectory(os: String, home: String, env: Map<String, String>, name: String): Path {
        val homePath = Path.of(home)
        val base = when {
            os.startsWith("Windows", ignoreCase = true) ->
                env["APPDATA"]?.takeIf(String::isNotBlank)?.let(Path::of)
                    ?: homePath.resolve("AppData").resolve("Roaming")

            os.startsWith("Mac", ignoreCase = true) ->
                homePath.resolve("Library").resolve("Application Support")

            else ->
                env["XDG_DATA_HOME"]?.takeIf(String::isNotBlank)?.let(Path::of)
                    ?: homePath.resolve(".local").resolve("share")
        }
        return base.resolve(name)
    }

    private fun osName(): String = System.getProperty("os.name").orEmpty()

    private fun userHome(): String = System.getProperty("user.home").orEmpty()

    private fun environment(): Map<String, String> = System.getenv()

    /** Lower-case, as every existing install already spells it. */
    private const val APP_DIR_NAME = "bitchord"

    private const val DOWNLOAD_FOLDER = "BitChord"
}
