pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor is published via JitPack.
        maven("https://jitpack.io")
    }
}

rootProject.name = "BitChord"
include(":shared")
include(":sharedUi")

/**
 * Targets this machine is not set up for can be left out.
 *
 * A CI job that builds the Windows installers has no Android SDK, and an
 * Android job has no desktop toolchain; including the other half fails at
 * configuration time, before the modules that machine can build are reached.
 */
fun switch(name: String): Boolean = providers.gradleProperty(name).orNull?.toBoolean() ?: false

val skipDesktop = switch("bitchord.skipDesktop")
val skipAndroid = switch("bitchord.skipAndroid")

if (!skipDesktop) {
    include(":desktopUi")
    include(":desktopApp")
}

// The Android modules only when there is an SDK to build them against.
// Read as a Properties file, not line by line: Android Studio writes the path
// escaped (`C\:\\Users\\...` on Windows), and only Properties undoes that.
// Every source is trimmed, because an empty value must never reach
// file("").isDirectory — which is true, for the current directory, and would
// include the Android modules on a machine that has no SDK at all.
fun sdkPath(value: String?): String? = value?.trim()?.takeIf { it.isNotBlank() }

val androidSdk: String? = file("local.properties")
    .takeIf { it.isFile }
    ?.let { f -> java.util.Properties().apply { f.inputStream().use(::load) } }
    ?.getProperty("sdk.dir")
    .let(::sdkPath)
    ?: sdkPath(System.getenv("ANDROID_HOME"))
    ?: sdkPath(System.getenv("ANDROID_SDK_ROOT"))

if (skipAndroid) {
    logger.lifecycle("Android targets skipped (-Pbitchord.skipAndroid=true) — building the desktop targets only.")
} else if (androidSdk != null && file(androidSdk).isDirectory) {
    include(":app")
    include(":tv")
    include(":wearos")
} else {
    logger.lifecycle("No Android SDK found — building the desktop targets only.")
}
