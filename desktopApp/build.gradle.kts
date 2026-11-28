import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import org.gradle.api.logging.Logger
import org.gradle.process.ExecSpec
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/** What the packages, the launcher and the settings footer all say. */
val appVersion: String = providers.gradleProperty("bitchord.version").orNull
    ?.removePrefix("v")
    ?.takeIf { it.isNotBlank() }
    // The version the phone and the release tags carry, so an unversioned desktop build does not
    // call itself a beta of a release that has already shipped.
    ?: (rootProject.extra["bitchordVersionName"] as? String)?.takeIf { it.isNotBlank() }
    ?: "1.9.1"

/** Which platform this build is *for*, which is the host unless told otherwise. */
val hostIsWindows = System.getProperty("os.name").contains("Windows", ignoreCase = true)
val hostIsLinux = System.getProperty("os.name").contains("Linux", ignoreCase = true)
val hostIsMac = System.getProperty("os.name").contains("Mac", ignoreCase = true)
val targetOs: String = (providers.gradleProperty("bitchord.target").orNull ?: when {
    hostIsWindows -> "windows"
    hostIsLinux -> "linux"
    hostIsMac -> "macos"
    else -> error("BitChord desktop supports Linux, Windows, and macOS only")
}).lowercase()

val hostArch = System.getProperty("os.arch").lowercase()

/**
 * Which processor this build is for: the host's, unless `-Pbitchord.arch` names another.
 *
 * Every per-architecture dependency — FFmpeg's javacpp natives, JavaFX's WebKit, Skiko —
 * is chosen from this one flag, so a Linux arm64 runner, an Apple Silicon Mac and a
 * cross-check on CI all agree. Packaging itself still runs on a matching host: jpackage
 * cannot assemble an architecture it is not running on.
 */
val isArm64: Boolean = when (val requested = providers.gradleProperty("bitchord.arch").orNull?.trim()?.lowercase()) {
    null, "" -> hostArch == "aarch64" || hostArch == "arm64"
    "arm64", "aarch64", "armv8" -> true
    "x64", "x86_64", "amd64", "x86-64" -> false
    else -> error("Unknown -Pbitchord.arch=$requested; use x64 or arm64")
}

// Windows installer metadata requires MAJOR.MINOR.BUILD, each field inside its own range, even
// though the app's public version is intentionally displayed without a patch number (1.7 rather
// than 1.7.0). The build number is the desktop version code; it also keeps "-beta1" out of the
// numeric installer version. A one-part, two-part or suffixed version ("1.9", "v1.9.1-beta1")
// normalises to the same MAJOR.MINOR.CODE shape instead of producing a malformed one.
val desktopVersionCode = 29
val nativePackageVersion: String = run {
    val fields = appVersion.substringBefore('-').split('.')
    val major = fields.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 255) ?: 1
    val minor = fields.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 255) ?: 0
    "$major.$minor.${desktopVersionCode.coerceIn(0, 65_535)}"
}

// FFmpeg decodes audio; see DesktopAudioDecoder.
val javacppVersion = "1.5.12"
val ffmpegVersion = "7.1.1-$javacppVersion"
val nativeClassifier = when (targetOs) {
    "windows" -> "windows-x86_64"
    "linux" -> if (isArm64) "linux-arm64" else "linux-x86_64"
    "macos" -> if (isArm64) "macosx-arm64" else "macosx-x86_64"
    else -> error("BitChord desktop supports Linux, Windows, and macOS only")
}
private fun localProperty(name: String): String = rootProject.file("local.properties")
    .takeIf { it.isFile }
    ?.readLines()
    ?.firstOrNull { it.startsWith("$name=") }
    ?.substringAfter('=')
    ?.trim()
    ?.takeIf { it.isNotBlank() }
    ?: System.getenv(name).orEmpty().trim()

val desktopModuleIndexUrl = localProperty("MODULE_INDEX_URL")

// Last.fm signs every request with these, so signing in from inside the app
// needs them at hand. Supplied locally and never committed, as on Android.
val listenTogetherServer = localProperty("LISTEN_TOGETHER_SERVER")
val lastfmApiKey = localProperty("LASTFM_API_KEY")
val lastfmSecret = localProperty("LASTFM_SECRET")

// macOS code signing and notarization, supplied locally or by CI.
//
// Absent, the build still makes an unsigned DMG, which is what a fork, a pull
// request and any machine without an Apple developer identity should get: a
// missing certificate must not turn a packaging run into a failure. See
// desktopApp/BUILDING-MACOS.md for what each value is.
val macSigningIdentity = localProperty("MACOS_SIGN_IDENTITY")
val macNotaryAppleId = localProperty("APPLE_ID")
val macNotaryPassword = localProperty("APPLE_APP_SPECIFIC_PASSWORD")
val macNotaryTeamId = localProperty("APPLE_TEAM_ID")

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    // Already on the classpath through the NewPipe extractor.
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("com.google.zxing:core:3.5.3")

    implementation(project(":shared"))
    // Its own copy of the player and pages; the phone and TV keep :sharedUi.
    implementation(project(":desktopUi"))
    // BotGuard, for the PoTokens YouTube's web clients need: the phone runs it in an Android
    // WebView, the desktop in JavaFX's (WebKit). Per-platform jars carry the natives.
    val javafxClassifier = when (targetOs) {
        "windows" -> "win"
        "macos" -> if (isArm64) "mac-aarch64" else "mac"
        else -> if (isArm64) "linux-aarch64" else "linux"
    }
    listOf("base", "graphics", "controls", "media", "web").forEach { module ->
        implementation("org.openjfx:javafx-$module:21.0.10:$javafxClassifier")
    }

    // The shared player loads artwork through Coil, as the phone does.
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")

    // Automix runs the same two models the Android build does.
    implementation("com.microsoft.onnxruntime:onnxruntime:1.29.0")

    // Decoding, resampling and demuxing for the audio engine.
    implementation("org.bytedeco:javacpp:$javacppVersion")
    implementation("org.bytedeco:javacpp:$javacppVersion:$nativeClassifier")
    implementation("org.bytedeco:ffmpeg:$ffmpegVersion")
    implementation("org.bytedeco:ffmpeg:$ffmpegVersion:$nativeClassifier")

    // The same backdrop blur the Android build uses for its floating bars.
    // On Windows, 1.7.2 keeps this Compose Desktop 1.10.3 app on the matching UI/text/animation
    // runtime. Preserve Linux's existing dependency graph exactly as it was.
    val desktopHazeVersion = if (targetOs == "windows") "1.7.2" else "1.7.3"
    implementation("dev.chrisbanes.haze:haze:$desktopHazeVersion")
    implementation("dev.chrisbanes.haze:haze-materials:$desktopHazeVersion")

    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.components.resources)
    implementation(compose.materialIconsExtended)
    // The Skiko runtime rides in on this, and Skiko is per-platform.
    implementation(
        when (targetOs) {
            "windows" -> compose.desktop.windows_x64
            "macos" -> if (isArm64) compose.desktop.macos_arm64 else compose.desktop.macos_x64
            else -> if (isArm64) compose.desktop.linux_arm64 else compose.desktop.linux_x64
        }
    )

    implementation("io.ktor:ktor-client-core:3.0.3")
    implementation("io.ktor:ktor-client-cio:3.0.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.3")
    // Listen Together speaks over a WebSocket; the Discord gateway already uses the same engine.
    implementation("io.ktor:ktor-client-websockets:3.0.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.slf4j:slf4j-nop:2.0.16")

    // Browsers keep their cookies in SQLite, and importing a session from one is how this app signs
    // in — see DesktopBrowserCookies.
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    // Chromium protects its Windows master key with DPAPI. JNA's platform helpers let the app
    // unwrap that key in the same Windows user session without invoking a shell or exposing it.
    implementation("net.java.dev.jna:jna-platform:5.19.1")

    // Linux desktop media controls use the standard session-bus MPRIS interfaces.
    implementation("com.github.hypfvieh:dbus-java-core:5.2.0")
    implementation("com.github.hypfvieh:dbus-java-transport-junixsocket:5.2.0")

    // Same URL deobfuscation helper used by Android's StreamResolver.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")

    // JVM equivalent of Android's QuickJS module host.
    implementation("org.graalvm.polyglot:polyglot:24.1.2")
    implementation("org.graalvm.polyglot:js:24.1.2")
    implementation("org.graalvm.js:js-language:24.1.2")

    testImplementation(kotlin("test"))
}

tasks.withType<Test> {
    systemProperty("java.awt.headless", "true")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

/** Builds the Automix analyser from the same C++ the Android app compiles. */
// Per target, because both write the library under the same name and a Windows cross-build
// otherwise left a `.dll` where the next Linux run looked for its `.so`.
val analysisNativeDir = layout.buildDirectory.dir("native/$targetOs")

/** The tiny Windows frame bridge is independent of the optional analyser and SMTC libraries. */
val windowNativeDir = layout.buildDirectory.dir("native-window/$targetOs")

/**
 * Whether this build is producing the analyser for a platform that is not the one running the
 * build.
 */
val crossBuildingForWindows = targetOs == "windows" && !hostIsWindows

/**
 * An executable's absolute path, from the PATH this build was invoked with.
 *
 * Resolved here rather than left to the child process. Gradle runs external
 * commands from the daemon, whose own PATH is whatever it was started with, so
 * a daemon reused from a shell that had no cmake cannot find the one the
 * current shell does — it fails with a bare "No such file or directory" while
 * `cmake --version` works fine in the terminal beside it.
 */
/**
 * Whether a tool on the PATH can actually be started.
 *
 * A PATH entry can be a wrapper script whose own interpreter is gone; it is a file, it is
 * executable, and it fails only at exec time — which surfaces as a build failure rather than as the
 * missing-tool path this build already handles.
 */
fun runs(tool: File): Boolean = runCatching {
    val process = ProcessBuilder(tool.absolutePath, "--version")
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        false
    } else {
        process.exitValue() == 0
    }
}.getOrDefault(false)

fun findOnPath(tool: String): File? {
    // Windows keeps the extension on the file and off the command line, so a bare name matches
    // nothing on disk: PATHEXT is the list a shell would have tried.
    val names = if (!hostIsWindows) {
        listOf(tool)
    } else {
        val extensions = System.getenv("PATHEXT").orEmpty()
            .ifBlank { ".COM;.EXE;.BAT;.CMD" }
            .split(';')
            .filter(String::isNotBlank)
        listOf(tool) + extensions.map { tool + it.lowercase() }
    }
    return System.getenv("PATH").orEmpty()
        .split(File.pathSeparator)
        .filter(String::isNotBlank)
        .flatMap { directory -> names.map { File(directory, it) } }
        // A PATHEXT match is executable by definition, and Windows' own canExecute is unreliable.
        .firstOrNull { it.isFile && (hostIsWindows || it.canExecute()) && runs(it) }
}

fun cmakeBinary(): String = findOnPath("cmake")?.absolutePath ?: "cmake"

/**
 * Runs one external build command and prints what it said when it fails.
 *
 * `providers.exec { ... }.result.get().assertNormalExitValue()` captures the
 * child's stdout and stderr into providers that nobody reads, so a compiler or
 * linker error surfaces only as "process ... finished with non-zero exit value"
 * — the diagnostics that say which header, symbol or library was missing are
 * gone. Both streams are drained on every run (an undrained pipe would block
 * the child) and printed at error level before the build is stopped, with the
 * command named so the log says which of the two CMake calls failed.
 */
fun runNativeCommand(
    what: String,
    command: List<String>,
    log: Logger,
    configure: ExecSpec.() -> Unit = {},
) {
    val exec = providers.exec {
        isIgnoreExitValue = true
        commandLine(command)
        configure()
    }
    val result = exec.result.get()
    val stdout = exec.standardOutput.asText.get()
    val stderr = exec.standardError.asText.get()
    if (result.exitValue != 0) {
        log.error("$what — stdout:\n$stdout")
        log.error("$what — stderr:\n$stderr")
        throw GradleException(
            "$what failed with exit code ${result.exitValue}.\n" +
                "Command: ${command.joinToString(" ")}\n" +
                "Its output is above; desktopApp/BUILDING-WINDOWS.md lists what each native library needs.",
        )
    }
}

/**
 * The Windows C++ toolchain a native build will use, if one is reachable.
 *
 * Two are supported and they are not interchangeable. MinGW-w64 builds both
 * libraries, but it has no C++/WinRT, so it compiles `smtc/smtc_abi.cpp` — the
 * media controls written against WinRT's raw ABI instead. Visual Studio's
 * compiler is the only one that builds `smtc/smtc_jni.cpp` against C++/WinRT,
 * and it is what a Windows machine most likely has, so when MinGW is absent the
 * configure is left to CMake's own default generator rather than forced. Forcing
 * MinGW (as this build used to) skipped every native — including the media keys
 * — on any machine that had Visual Studio and not MinGW.
 */
data class WindowsCompiler(val compiler: File?, val ninja: File?) {
    /** Whether the MinGW path can run: it needs both a compiler and Ninja. */
    val mingw: Boolean get() = compiler != null && ninja != null
}

fun windowsCompiler(crossing: Boolean): WindowsCompiler {
    if (targetOs != "windows") return WindowsCompiler(null, null)
    // A native Windows host goes to Visual Studio unless MinGW is asked for explicitly. A g++
    // that happens to be on PATH must not be taken for one: MinGW cannot build C++/WinRT, so
    // choosing it silently drops SMTC and the frame bridge from an otherwise fine build.
    val requested = crossing || (providers.gradleProperty("bitchord.useMingw").orNull?.toBoolean() ?: false)
    if (!requested) return WindowsCompiler(null, null)
    val gxx = findOnPath(if (crossing) "x86_64-w64-mingw32-g++" else "g++")
    val ninja = findOnPath("ninja")
    return if (gxx != null && ninja != null) WindowsCompiler(gxx, ninja) else WindowsCompiler(null, null)
}

val buildAnalysisNative by tasks.registering {
    val cmakeSource = project.file("native")
    val outputDir = analysisNativeDir.get().asFile
    val crossing = crossBuildingForWindows
    val toolchain = project.file("native/mingw-w64.cmake")
    inputs.dir(rootProject.file("native/analyzer"))
    inputs.dir(rootProject.file("app/src/main/cpp/jni"))
    inputs.dir(project.file("native"))
    inputs.property("target", targetOs)
    outputs.dir(outputDir)
    onlyIf {
        val cmake = findOnPath("cmake")
        if (cmake == null) {
            logger.lifecycle(
                "no usable cmake — building without the Automix analyser. If cmake is installed, " +
                    "a Gradle daemon started before it was on PATH may be in use: ./gradlew --stop",
            )
        }
        val win = windowsCompiler(crossing)
        if (cmake != null && targetOs == "windows" && !win.mingw && !hostIsWindows) {
            logger.lifecycle(
                "No MinGW-w64 C++ compiler with Ninja on PATH, and this is not a Windows host — " +
                    "the build will have no Automix analyser and no Windows media controls. " +
                    "See desktopApp/BUILDING-WINDOWS.md.",
            )
        }
        // On Windows without MinGW this still runs: CMake's default generator finds Visual
        // Studio, the only toolchain that can build C++/WinRT SMTC.
        cmake != null && (targetOs != "windows" || win.mingw || hostIsWindows)
    }
    doLast {
        val javaHome = System.getProperty("java.home")
        val win = windowsCompiler(crossing)
        check(targetOs != "windows" || win.mingw || hostIsWindows) {
            "no Windows C++ toolchain for the analyser"
        }
        val configureCommand = buildList {
            addAll(listOf(cmakeBinary(), "-S", cmakeSource.absolutePath, "-B", outputDir.absolutePath))
            if (targetOs == "windows") {
                // Discard any generator cached by an earlier configure.
                add("--fresh")
                if (win.mingw) {
                    addAll(
                        listOf(
                            "-G", "Ninja",
                            "-DCMAKE_MAKE_PROGRAM=${win.ninja!!.absolutePath}",
                            "-DCMAKE_CXX_COMPILER=${win.compiler!!.absolutePath}",
                        ),
                    )
                }
                // Otherwise no -G and no compiler: CMake's default generator on Windows is
                // Visual Studio, which builds C++/WinRT and finds the Windows SDK without
                // a developer prompt.
            }
            add("-DCMAKE_BUILD_TYPE=Release")
            // CMake 4 aborts a project whose minimum is older than 3.5; this lets an
            // older CMakeLists configure without editing it.
            add("-DCMAKE_POLICY_VERSION_MINIMUM=3.5")
            if (crossing) add("-DCMAKE_TOOLCHAIN_FILE=${toolchain.absolutePath}")
        }
        runNativeCommand(
            "CMake configure for the Automix analyser ($targetOs)",
            configureCommand,
            logger,
        ) {
            environment("JAVA_HOME", javaHome)
        }
        runNativeCommand(
            "CMake build for the Automix analyser ($targetOs)",
            listOf(cmakeBinary(), "--build", outputDir.absolutePath, "--config", "Release", "-j", "4"),
            logger,
        ) {
            environment("JAVA_HOME", javaHome)
        }
    }
}

/**
 * Builds the Win32/DWM frame bridge even when the much larger analyser build is skipped during a
 * desktop UI run. It is intentionally absent from Linux builds, whose window manager remains the
 * sole owner of decoration and behaviour.
 */
val buildWindowNative by tasks.registering {
    val cmakeSource = project.file("native/window")
    val outputDir = windowNativeDir.get().asFile
    val crossing = crossBuildingForWindows
    val toolchain = project.file("native/mingw-w64.cmake")
    inputs.dir(cmakeSource)
    inputs.property("target", targetOs)
    outputs.dir(outputDir)
    onlyIf {
        if (targetOs != "windows") return@onlyIf false
        val cmake = findOnPath("cmake")
        val win = windowsCompiler(crossing)
        if (cmake == null || (!win.mingw && !hostIsWindows)) {
            logger.lifecycle(
                "cmake and a Windows C++ toolchain (MinGW-w64 + Ninja, or Visual Studio) are " +
                    "unavailable — the build falls back to Compose's frameless window and Java " +
                    "Sound's DirectSound output. See desktopApp/BUILDING-WINDOWS.md.",
            )
        }
        cmake != null && (win.mingw || hostIsWindows)
    }
    doLast {
        val javaHome = System.getProperty("java.home")
        val win = windowsCompiler(crossing)
        val configureCommand = buildList {
            addAll(
                listOf(
                    cmakeBinary(),
                    "-S", cmakeSource.absolutePath,
                    "-B", outputDir.absolutePath,
                    "-DCMAKE_BUILD_TYPE=Release",
                ),
            )
            if (targetOs == "windows") add("--fresh")
            if (win.mingw) {
                addAll(
                    listOf(
                        "-G", "Ninja",
                        "-DCMAKE_MAKE_PROGRAM=${win.ninja!!.absolutePath}",
                        "-DCMAKE_CXX_COMPILER=${win.compiler!!.absolutePath}",
                    ),
                )
            }
            // Same floor as the analyser: CMake 4.x refuses an older minimum outright.
            add("-DCMAKE_POLICY_VERSION_MINIMUM=3.5")
            if (crossing) add("-DCMAKE_TOOLCHAIN_FILE=${toolchain.absolutePath}")
        }
        runNativeCommand(
            "CMake configure for the window bridge ($targetOs)",
            configureCommand,
            logger,
        ) {
            environment("JAVA_HOME", javaHome)
        }
        runNativeCommand(
            "CMake build for the window bridge ($targetOs)",
            listOf(cmakeBinary(), "--build", outputDir.absolutePath, "--config", "Release", "-j", "4"),
            logger,
        ) {
            environment("JAVA_HOME", javaHome)
        }
    }
}

/**
 * What the native builds produced, and therefore what the packaged app will have.
 *
 * Every one of these is optional at runtime — the app degrades to Compose's own
 * frame, Java Sound, and no media keys — which is exactly why it has to be said
 * out loud instead of failing silently at package time.
 */
val windowsNatives by tasks.registering {
    group = "distribution"
    description = "Reports which native libraries this build produced."
    dependsOn(buildAnalysisNative, buildWindowNative)
    val analysis = analysisNativeDir
    val window = windowNativeDir
    val target = targetOs
    doLast {
        // CMake keeps each platform's own naming: `lib` on Linux and macOS, and on Windows the
        // bare name its CMakeLists pins with PREFIX "".
        val prefix = if (target == "windows") "" else "lib"
        val ext = when (target) {
            "windows" -> "dll"
            "macos" -> "dylib"
            else -> "so"
        }
        val analysisDir = analysis.get().asFile
        val windowDir = window.get().asFile
        val entries = buildList {
            if (target == "windows") {
                add(Triple("bitchord_window.$ext", "Win32 frame + WASAPI output", windowDir))
                add(Triple("bitchord_smtc.$ext", "media keys and the volume flyout", analysisDir))
            }
            add(Triple("${prefix}bitchord_analysis.$ext", "Automix analysis", analysisDir))
        }
        logger.lifecycle(
            buildString {
                appendLine("native libraries for $target:")
                // Visual Studio puts its output under Release\, and a target declared in a
                // subdirectory adds another level; MinGW and CMake on Unix put it at the top.
                // Any depth is this build's own output.
                fun holds(name: String, dir: File): Boolean =
                    dir.walkTopDown().any { it.isFile && it.name == name }
                for ((name, what, dir) in entries) {
                    appendLine("  $name — $what — ${if (holds(name, dir)) "built" else "MISSING"}")
                }
                if (target == "windows" && entries.any { (name, _, dir) -> !holds(name, dir) }) {
                    appendLine("  A missing library is not a broken build — the app falls back to Compose's")
                    appendLine("  frame, Java Sound, and generic media handling. See desktopApp/BUILDING-WINDOWS.md.")
                }
            },
        )
    }
}

tasks.matching { it.name == "packageExe" || it.name == "packageMsi" || it.name == "createDistributable" }
    .configureEach { dependsOn(windowsNatives) }

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildAnalysisNative, buildWindowNative)
    // Visual Studio is a multi-config generator: a target writes its library under Release\ at
    // the configure root, and a target declared in a subdirectory writes it under
    // <subdir>\Release\. Match at any depth and flatten every library into native/ — the loader
    // asks for it by bare name and does not care which generator produced it.
    from(analysisNativeDir) {
        include("**/*.so", "**/*.dll", "**/*.dylib")
        includeEmptyDirs = false
        eachFile { path = "native/$name" }
        into("native")
    }
    from(windowNativeDir) {
        include("**/*.dll")
        includeEmptyDirs = false
        eachFile { path = "native/$name" }
        into("native")
    }
    // The Automix models, taken from the Android module rather than copied into this one.
    from(rootProject.file("app/src/main/assets")) {
        include("*.onnx")
        into("models")
    }
}

// Kotlin 2.4 no longer contributes this JVM target's compiler output to Gradle's plain `jar`
// task in this project. Compose's dev runner and the portable launcher both use that jar, so add
// the output explicitly instead of producing a resources-only application archive.
tasks.named<Jar>("jar") {
    val kotlinClasses = tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin")
    dependsOn(kotlinClasses)
    from(kotlinClasses.flatMap { it.destinationDirectory })
    // Kotlin 2.3.20 also contributes the same class files through the default source set, and two
    // copies of every entry make the jar task fail rather than warn.
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

/** The entry point the launcher, the packages and the dev runner all start. */
val composeMainClass = "com.music.bitchord.desktop.MainKt"

/** A Windows build that can be assembled here, since an installer cannot be. */
val windowsPortableDir = layout.buildDirectory.dir("windows-portable")

val windowsPortableAssemble by tasks.registering {
    group = "distribution"
    description = "Lays out a Windows-runnable folder: application jars, Windows natives, launcher."
    dependsOn(tasks.named("jar"))
    val stage = windowsPortableDir.map { it.dir("BitChord") }
    val appJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    val runtime = configurations.named("runtimeClasspath")
    val version = appVersion
    val moduleIndex = desktopModuleIndexUrl
    val lastfmKey = lastfmApiKey
    val lastfmSharedSecret = lastfmSecret
    val mainClass = composeMainClass
    // Declared, not implied.
    inputs.file(appJar)
    inputs.files(runtime)
    inputs.property("version", version)
    inputs.property("moduleIndex", moduleIndex)
    inputs.property("lastfm", lastfmKey.isNotBlank())
    inputs.property("mainClass", mainClass)
    outputs.dir(stage)
    doLast {
        val target = stage.get().asFile
        val lib = File(target, "lib")
        lib.deleteRecursively()
        lib.mkdirs()

        // Named by coordinate rather than by file name.
        runtime.get().incoming.artifacts.artifacts.forEach { artifact ->
            val id = artifact.id.componentIdentifier
            val group = (id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.group
            val name = if (group.isNullOrBlank()) artifact.file.name else "$group.${artifact.file.name}"
            artifact.file.copyTo(File(lib, name), overwrite = true)
        }
        appJar.get().asFile.copyTo(File(lib, "bitchord-app.jar"), overwrite = true)

        // `lib\*` rather than a spelled-out classpath: the wildcard is what keeps this working when
        // a dependency is added, and the JVM is what expands it.
        File(target, "BitChord.bat").writeText(
            buildString {
                appendLine("@echo off")
                appendLine("setlocal")
                appendLine("rem BitChord, portable. Needs a Java 21+ runtime on PATH or in JAVA_HOME.")
                appendLine("set \"HERE=%~dp0\"")
                appendLine("set \"RUNNER=java\"")
                appendLine("if defined JAVA_HOME set \"RUNNER=%JAVA_HOME%\\bin\\java\"")
                append("\"%RUNNER%\" -Dbitchord.version=$version")
                if (moduleIndex.isNotBlank()) append(" -Dbitchord.module.index=$moduleIndex")
                if (lastfmKey.isNotBlank() && lastfmSharedSecret.isNotBlank()) {
                    append(" -Dbitchord.lastfm.key=$lastfmKey -Dbitchord.lastfm.secret=$lastfmSharedSecret")
                }
                appendLine(" -cp \"%HERE%lib\\*\" $mainClass %*")
                appendLine("endlocal")
            },
        )

        // What "it builds" is not the same as "it runs", and the difference is exactly what shipped
        // a broken archive.
        val jars = lib.listFiles { file: File -> file.name.endsWith(".jar") }.orEmpty()
        fun holds(entry: String) = jars.any { jar ->
            ZipFile(jar).use { zip -> zip.getEntry(entry) != null }
        }
        val problems = buildList {
            if (!holds("com/music/bitchord/desktop/MainKt.class")) {
                add("the BitChord desktop entry point is missing")
            }
            if (!holds("androidx/compose/runtime/Composer.class")) {
                add("the Compose runtime is missing")
            }
            if (jars.none { it.name.contains("skiko-awt-runtime-windows") }) {
                add("the Windows Skiko renderer is missing")
            }
            if (jars.any { it.name.contains("skiko-awt-runtime-linux") }) {
                add("a Linux Skiko renderer is in a Windows archive")
            }
            if (!holds("org/bytedeco/ffmpeg/windows-x86_64/avformat.dll") &&
                jars.none { it.name.contains("ffmpeg") && it.name.contains("windows-x86_64") }
            ) {
                add("the Windows FFmpeg natives are missing")
            }
        }
        check(problems.isEmpty()) { "windowsPortable: " + problems.joinToString("; ") }

        val analysisOutput = analysisNativeDir.get().asFile
        val analyser = File(analysisOutput, "bitchord_analysis.dll").isFile ||
            File(analysisOutput, "Release/bitchord_analysis.dll").isFile
        logger.lifecycle(
            if (analyser) {
                "windowsPortable: verified — Compose runtime, Windows renderer, Windows FFmpeg, Automix analyser."
            } else {
                "windowsPortable: verified, but no bitchord_analysis.dll — Automix will not run in this archive. " +
                    "Rebuild with: nix-shell -p cmake pkgsCross.mingwW64.buildPackages.gcc"
            },
        )
    }
}

val windowsPortable by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Zips the portable Windows folder for copying to a Windows machine."
    dependsOn(windowsPortableAssemble)
    from(windowsPortableDir)
    archiveFileName.set("BitChord-$appVersion-windows-portable.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    doLast { logger.lifecycle("windowsPortable: ${archiveFile.get().asFile.absolutePath}") }
}

/**
 * A real single-file AppImage for this architecture.
 *
 * Compose's `TargetFormat.AppImage` produces an app-image *directory* — what
 * `createDistributable` already writes — not the squashfs bundle the format
 * means, so the last step is appimagetool over that directory. Named so it sits
 * beside `packageDeb` and `packageRpm`.
 */
val packageAppImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Bundles the Linux app image into BitChord-<version>-linux-<arch>.AppImage."
    dependsOn("createDistributable")

    val archLabel = if (isArm64) "arm64" else "x64"
    val appImageArch = if (isArm64) "aarch64" else "x86_64"
    val appDir = layout.buildDirectory.dir("compose/binaries/main/app/BitChord")
    val output = layout.buildDirectory.file("distributions/BitChord-$appVersion-linux-$archLabel.AppImage")
    val tool = providers.gradleProperty("bitchord.appimageTool")
        .orElse(providers.environmentVariable("APPIMAGE_TOOL"))
        .getOrElse("appimagetool")

    doFirst {
        check(targetOs == "linux") {
            "packageAppImage needs a Linux host: appimagetool bundles a Linux app image " +
                "and cannot wrap another OS's from here."
        }
    }
    commandLine(
        "bash",
        project.file("packaging/appimage.sh").absolutePath,
        appDir.get().asFile.absolutePath,
        output.get().asFile.absolutePath,
        tool,
    )
    // Read by appimage.sh: appimagetool refuses to label an arm64 tree x86_64.
    environment("APPIMAGE_ARCH", appImageArch)
}

compose.desktop {
    application {
        javaHome = javaToolchains.launcherFor(java.toolchain).map {
            it.metadata.installationPath.asFile.absolutePath
        }.get()
        mainClass = composeMainClass
        jvmArgs("-Xmx512m")
        jvmArgs("-XX:+UseG1GC", "-XX:G1PeriodicGCInterval=20000", "-XX:G1PeriodicGCSystemLoadThreshold=0")
        jvmArgs("-Dbitchord.version=$appVersion")
        if (desktopModuleIndexUrl.isNotBlank()) {
            jvmArgs("-Dbitchord.module.index=$desktopModuleIndexUrl")
        }
        if (lastfmApiKey.isNotBlank() && lastfmSecret.isNotBlank()) {
            jvmArgs("-Dbitchord.lastfm.key=$lastfmApiKey", "-Dbitchord.lastfm.secret=$lastfmSecret")
        }
        if (listenTogetherServer.isNotBlank()) {
            jvmArgs("-Dbitchord.listentogether.server=$listenTogetherServer")
        }

        buildTypes.release.proguard {
            isEnabled.set(false)
            obfuscate.set(false)
            optimize.set(false)
            configurationFiles.from(project.file("packaging/proguard-rules.pro"))
        }

        nativeDistributions {
            targetFormats(
                TargetFormat.Dmg,
                TargetFormat.Pkg,
                TargetFormat.Exe,
                TargetFormat.Msi,
                TargetFormat.Deb,
                TargetFormat.Rpm,
            )
            packageName = "BitChord"
            packageVersion = nativePackageVersion
            description = if (targetOs == "windows") "BitChord" else "Aesthetic YouTube Music client"
            vendor = "BitChord contributors"
            copyright = "Copyright © 2026 BitChord contributors"

            modules(
                "java.base",
                "java.desktop",
                "java.instrument",
                "java.logging",
                "java.management",
                "java.naming",
                "java.net.http",
                "java.prefs",
                "java.scripting",
                "java.sql",
                "java.xml",
                "jdk.crypto.ec",
                "jdk.dynalink",
                // JavaFX WebEngine returns JavaScript objects through netscape.javascript.JSObject.
                // Without this module the slim production runtime crashes in twkExecuteScript,
                // while development runs work because the full JDK already contains it.
                "jdk.jsobject",
                "jdk.localedata",
                "jdk.security.auth",
                "jdk.unsupported",
                "jdk.zipfs",
            )

            macOS {
                iconFile.set(project.file("packaging/icons/AppIcon.icns"))
                bundleID = "com.bitchord.desktop"
                appCategory = "public.app-category.music"
                dockName = "BitChord"
                // The hardened runtime the signature implies needs these for
                // Skiko/JavaFX's JIT and for the microphone the sound search
                // records from. The file also covers a notarized build.
                entitlementsFile.set(project.file("packaging/macos/entitlements.plist"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSRequiresAquaSystemAppearance</key>
                        <false/>
                        <key>NSMicrophoneUsageDescription</key>
                        <string>BitChord listens through your microphone to recognise the music playing around you.</string>
                    """.trimIndent()
                }
                if (macSigningIdentity.isNotBlank()) {
                    signing {
                        sign.set(true)
                        identity.set(macSigningIdentity)
                    }
                }
                if (
                    macSigningIdentity.isNotBlank() &&
                    macNotaryAppleId.isNotBlank() &&
                    macNotaryPassword.isNotBlank() &&
                    macNotaryTeamId.isNotBlank()
                ) {
                    notarization {
                        appleID.set(macNotaryAppleId)
                        password.set(macNotaryPassword)
                        teamID.set(macNotaryTeamId)
                    }
                }
            }

            linux {
                iconFile.set(project.file("packaging/icons/AppIcon.png"))
                // The package id, separate from the display name: Debian and RPM
                // both require it to be lowercase.
                packageName = "bitchord"
                menuGroup = "AudioVideo"
                appCategory = "AudioVideo;Music;Player"
                debMaintainer = "85984486+AbhiTheModder@users.noreply.github.com"
                rpmLicenseType = "GPL-3.0"
            }

            windows {
                iconFile.set(project.file("packaging/icons/AppIcon.ico"))
                upgradeUuid = "8f3c1d24-6a2b-4f5e-9c17-2b8d54e0a913"
                menuGroup = "BitChord"
                shortcut = true
                dirChooser = false
                perUserInstall = true
            }
        }
    }

}
