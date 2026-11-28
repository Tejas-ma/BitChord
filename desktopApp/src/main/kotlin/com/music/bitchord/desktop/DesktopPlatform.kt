package com.music.bitchord.desktop

/**
 * Which desktop this is running on, and on what kind of processor.
 *
 * The OS flags decide behaviour (window frames, media keys, secret stores);
 * the architecture decides which native artifacts a build asks for and which
 * installer an update should pick. Both are read once: `os.name`/`os.arch`
 * cannot change while the process lives, and reading them per call made the
 * answers hard to fake in a test.
 */
internal object DesktopPlatform {

    private val name: String = System.getProperty("os.name").orEmpty()

    val isWindows: Boolean = name.startsWith("Windows", ignoreCase = true)

    val isLinux: Boolean = name.contains("linux", ignoreCase = true)

    val isMac: Boolean = name.contains("mac", ignoreCase = true)

    /** Whether the application draws the window's own frame instead of the system drawing it. */
    val drawsOwnWindowFrame: Boolean = isWindows || isMac

    /** `x64` or `arm64`: the two architectures this app ships for on every desktop. */
    val arch: String = archOf(System.getProperty("os.arch").orEmpty())

    val isArm64: Boolean = arch == ARCH_ARM64

    /**
     * The architecture an `os.arch` value names.
     *
     * JDK and native toolchains spell the same CPU several ways — `aarch64` on
     * Linux, `arm64` on macOS, `amd64` on Windows — and every dependency this
     * build asks for uses one of the two names this returns. An unknown value
     * is treated as x64, which is what a build host broken enough to report
     * something else can still run.
     */
    fun archOf(osArch: String): String {
        val normalised = osArch.trim().lowercase()
        return when {
            normalised.contains("aarch64") ||
                normalised.contains("arm64") ||
                normalised == "arm" ||
                normalised.contains("armv8") -> ARCH_ARM64

            else -> ARCH_X64
        }
    }

    const val ARCH_X64 = "x64"
    const val ARCH_ARM64 = "arm64"
}
