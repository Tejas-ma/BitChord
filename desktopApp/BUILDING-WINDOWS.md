# Building BitChord for Windows

Everything here is about the Windows build specifically: the native libraries the app
carries, the tools that produce them, and the jpackage step that turns the result into an
installer. `DESKTOP.md` covers running the app and the packages in general.

jpackage drives the target platform's own tooling, so **a Windows installer has to be built
on Windows**. Nothing on Linux or macOS can produce a `.exe` or `.msi`.

## What a Windows build produces

| Artifact | Produced by | Notes |
|---|---|---|
| `BitChord-<version>-windows-x64-setup.exe` | `:desktopApp:packageExe` | Per-user install, never asks for an administrator. |
| `BitChord-<version>-windows-x64.msi` | `:desktopApp:packageMsi` | Same payload, for MSI-based deployment. |
| `BitChord-<version>-windows-portable.zip` | `:desktopApp:windowsPortable` | Unzip and run `BitChord.bat`; needs a Java 21 runtime on `PATH` or `JAVA_HOME`. |
| `build/compose/binaries/main/app/BitChord/` | `:desktopApp:createDistributable` | The app image jpackage builds before wrapping it. |

Each installer carries its own Java runtime, Skiko (the Skia binding Compose draws with), the
FFmpeg natives used for decoding, and the Automix models. The JDK and a codec pack are the
two things a user must never have to install themselves.

## Required tools

### Always

- **JDK 21** — exactly 21. `compose.desktop.application.javaHome` resolves it from the
  `java.toolchain` block, and `jpackage` (part of every JDK since 14) is what builds the
  installers. Temurin 21 and Microsoft OpenJDK 21 are both fine. Set `JAVA_HOME`.
- **Gradle** — use the wrapper (`gradlew.bat`), which pins 8.11.1. No separate install.
- **CMake ≥ 3.24** on `PATH` (`cmake --version`) — `native/CMakeLists.txt` itself only asks for
  3.16, but the Gradle build passes `--fresh` when targeting Windows, which 3.24 introduced.
  3.31 is what this was last built with.

A PATH entry is not enough on its own: Gradle runs external commands from its **daemon**,
which keeps whatever `PATH` it was started with. After installing a tool, run
`gradlew.bat --stop` so the next build sees it.

### One of these two C++ toolchains

They are not interchangeable, because only one of them has C++/WinRT.

**A. Visual Studio 2022 Build Tools (recommended)** — install the *Desktop development with
C++* workload and a **Windows 10/11 SDK** (10.0.17763 or newer; the SDK is where the WinRT
headers under `winrt/` come from). With this, CMake's default generator finds MSVC on its
own, and `smtc/smtc_jni.cpp` is compiled against C++/WinRT. Ninja is optional here.

**B. MinGW-w64 + Ninja** — MinGW has no C++/WinRT, so the build falls back to
`smtc/smtc_abi.cpp`, the same exports written against WinRT's raw ABI. It needs the SDK's
`systemmediatransportcontrolsinterop.h` and `windows.media.h` to be reachable; the CMake
guard (`check_include_file_cxx`) skips the media-controls library when they are not. If you
build this way, install Ninja — the Gradle build uses the Ninja generator for MinGW.

The build detects which one it has. If MinGW's compiler and Ninja are both absent it leaves
the generator and compiler to CMake, which is what selects Visual Studio. It does **not**
silently produce an installer with no natives — `:desktopApp:windowsNatives` prints what was
built and what that costs.

### For the installers

- **WiX Toolset 3.x** on `PATH`, providing `candle.exe` and `light.exe`. This is what
  jpackage shells out to for both `.exe` and `.msi`.
- **WiX 4 and WiX 5 do not work.** jpackage 21 calls the WiX 3 command line
  (`candle`/`light`); WiX 4 renamed the tools to `wix build` and cannot be substituted.
  Install WiX 3.14 from the WiX Toolset GitHub releases and add its `bin` directory to
  `PATH`.

## The native libraries

Three small JNI libraries sit beside the application. Each one exists because something the
app needs is unreachable from the JVM.

| Library | Sources | Built by | Needs | Without it |
|---|---|---|---|---|
| `bitchord_window.dll` | `native/window/window_jni.cpp` | `buildWindowNative` | Win32, DWM, WASAPI (all in the SDK) | Compose's own frameless window; audio falls back to Java Sound's DirectSound bridge |
| `bitchord_smtc.dll` | `native/smtc/smtc_jni.cpp` (MSVC) or `smtc_abi.cpp` (MinGW) | `buildAnalysisNative` | C++/WinRT **or** MinGW with the WinRT ABI headers | No media keys, no volume-flyout card, no lock-screen transport |
| `bitchord_analysis.dll` | `../../../native/analyzer/*.cpp` + `app/src/main/cpp/jni/*.cpp` | `buildAnalysisNative` | the same C++ toolchain | Automix never analyses anything; beat-matched transitions are unavailable |

`bitchord_window.dll` is one library with two jobs: the Win32/DWM frame the window is drawn
in, and WASAPI shared-mode output. WASAPI matters because Java Sound's DirectSound bridge
will accept a format a USB endpoint does not actually take — 384 kHz, 32-bit float, for
instance — and then render silence. Shared mode asks the Windows audio engine to convert to
the endpoint's own mix format instead.

`bitchord_smtc.dll` runs a hidden window with a message pump on a thread of its own, because
SMTC is acquired from a window handle and a window only delivers anything with a loop. It
exposes exactly three JNI entry points — `nativeStart`, `nativeUpdate`, `nativeStop` — on
`com.music.bitchord.desktop.DesktopWindowsMedia`. Updates are posted to the pump thread with
`WM_APP + 1`; every WinRT object belongs to the apartment that created it.

Both libraries are linked with a static C/C++ runtime (`-static -static-libgcc
-static-libstdc++` under MinGW, `CMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded` under MSVC). That
is deliberate: they are unpacked into the app's private runtime directory, where MinGW's own
`libstdc++-6.dll`, `libgcc_s_*.dll` and `libwinpthread-1.dll` are not, so a dynamically
linked library would fail to load on any machine without the toolchain installed.

### Where they end up

`processResources` copies `*.dll` from `build/native/windows/` and
`build/native-window/windows/` into the jar under `native/`. At startup
`DesktopAnalysisRuntime` unpacks each one to the app's private directory and `System.load`s
it from there — a DLL cannot be loaded straight out of a jar.

## Building

```bat
REM 1. Everything at once: app image + the natives it carries.
gradlew.bat :desktopApp:createDistributable

REM 2. The installers (each needs WiX 3 on PATH).
gradlew.bat :desktopApp:packageExe
gradlew.bat :desktopApp:packageMsi

REM 3. The portable archive.
gradlew.bat :desktopApp:windowsPortable
```

`packageMsi` and `packageExe` only do anything on Windows. Run from Linux or macOS the tasks
exist and report success without producing a file, because the Compose plugin skips target
formats the host cannot build. That silence is why the platform check below matters.

Stamping a version other than the one in `desktopApp/build.gradle.kts`:

```bat
gradlew.bat :desktopApp:packageMsi -Pbitchord.version=1.9.1
```

The numeric installer version is derived from it (`major.minor.<desktopVersionCode>`, because
Windows requires three parts and the app's public version deliberately has two). The
`-beta1`-style suffix is stripped from the MSI version but kept in the app's own.

A release build runs `-Xmx512m` with G1 and a periodic-GC hint, so an idle window gives its
heap back; those are in `compose.desktop.application.jvmArgs`.

### Checking what you got

```bat
gradlew.bat :desktopApp:windowsNatives
```

prints each library and whether it was built:

```
native libraries for windows:
  bitchord_window.dll — Win32 frame + WASAPI output — built
  bitchord_smtc.dll — media keys and the volume flyout — built
  bitchord_analysis.dll — Automix analysis — built
```

A `MISSING` line is not a broken build — every one of these has a fallback — but it does tell
you which feature the installer you just made will not have. `packageExe`, `packageMsi` and
`createDistributable` all run this first, so the answer is in the packaging log.

To confirm a library's exports by hand:

```bat
dumpbin /exports build\native\windows\bitchord_smtc.dll | findstr Java_
```

You should see `Java_com_music_bitchord_desktop_DesktopWindowsMedia_nativeStart`,
`..._nativeUpdate` and `..._nativeStop`.

## Troubleshooting

| Symptom | Cause |
|---|---|
| `bitchord_smtc.dll` missing, MinGW installed | MinGW's headers lack `systemmediatransportcontrolsinterop.h` / `windows.media.h`. Use Visual Studio Build Tools, or install a MinGW-w64 with the WinRT ABI headers. |
| No natives at all, but CMake is installed | The Gradle daemon started before CMake was on `PATH`. `gradlew.bat --stop`. |
| `packageMsi` fails in `candle.exe` | WiX 4/5 on `PATH` instead of WiX 3, or WiX 3 not on `PATH` at all. |
| App starts, Automix does nothing | `bitchord_analysis.dll` absent, or it failed to load. Install the [VC++ 2015–2022 Redistributable (x64)](https://aka.ms/vs/17/release/vc_redist.x64.exe) — a statically linked analyser still needs the system runtime for a few imports. |
| Media keys do nothing, DLL present | Another app owns the SMTC session, or `nativeStart` failed — the app logs `Windows media controls could not be created`. |
| Audio silent on a USB DAC | Expected if `bitchord_window.dll` is absent: Java Sound accepted a format the endpoint does not render. Build the natives. |

## What is not covered here

- **Cross-compiling.** `-Pbitchord.target=windows` from Linux exists for laying out a
  portable folder, and `native/mingw-w64.cmake` is the toolchain file for it. It cannot
  produce an installer, and MinGW cross-builds usually have no SMTC (see the CMake guard).
- **Code signing.** The installers are unsigned. Windows SmartScreen will warn on first run;
  signing needs a certificate this repository does not carry.
- **CI.** `.github/workflows/release.yml` is where the real installers are built, on a
  `windows-latest` runner with WiX installed.
