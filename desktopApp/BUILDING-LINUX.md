# Building BitChord for Linux

Both x86_64 and aarch64 are supported. Like macOS, each is built on a machine
of its own architecture: the runtime, Skiko, FFmpeg and JavaFX WebKit inside the
package are the host's, and `jpackage` cannot assemble another architecture.

## What the build needs

| Package | Why |
| --- | --- |
| JDK 21 | Compiles and packages `desktopApp`. |
| `cmake`, a C++ compiler | Build `libbitchord_analysis.so` for Automix. Optional: the app runs without it, with Automix disabled. |
| `rpm`, `fakeroot` | `packageRpm` and `packageDeb`. |
| `binutils`, `file` | Inspecting the produced launcher. |
| `appimagetool` | Wraps the jpackage app image as an AppImage. |

```bash
sudo apt-get install -y cmake build-essential rpm fakeroot binutils file
```

Run it from source with:

```bash
./gradlew :desktopApp:run -Pbitchord.skipAndroid=true
```

## Packaging

```bash
./gradlew :desktopApp:packageDeb :desktopApp:packageRpm :desktopApp:packageAppImage \
  -Pbitchord.skipAndroid=true -Pbitchord.arch=x64
```

`packageAppImage` depends on `createDistributable` and wraps the app image with
`appimagetool`, writing `desktopApp/build/distributions/BitChord-<version>-linux-x64.AppImage`.
`appimagetool` must be on `PATH`, or named by `APPIMAGE_TOOL` / `-Pbitchord.appimageTool`.
Compose's own `TargetFormat.AppImage` only produces the app-image directory, which is why
the last step is a task of its own; `packaging/appimage.sh` remains usable directly for a
release build that wants a different output name.

On arm64 the same commands take `-Pbitchord.arch=arm64` and the arch-matched
`appimagetool-aarch64.AppImage`, and the AppImage is named `-linux-arm64`.
`-Pbitchord.arch` selects FFmpeg `linux-arm64`/`linux-x86_64`, JavaFX
`linux-aarch64`/`linux`, and Skiko `linux_arm64`/`linux_x64`.

The `.deb` and `.rpm` land in `desktopApp/build/compose/binaries/main/`, the app
image in `.../app/BitChord`, and the AppImage wherever the script was told to
write it.

## Where it keeps things

The XDG base directory specification, honouring the variables when set:

| Kind | Location |
| --- | --- |
| Custom covers | `$XDG_DATA_HOME/bitchord/playlist_covers` (default `~/.local/share/bitchord/...`) |
| Motion-artwork clips | `$XDG_CACHE_HOME/bitchord/media` (default `~/.cache/bitchord/media`) |
| Downloads | `~/Music/BitChord` |
| Preferences | `java.util.prefs`; credentials in the Secret Service (GNOME Keyring, KWallet) |

Cover files are named from a normalised key (`[A-Za-z0-9._-]`), so a
case-sensitive filesystem cannot hold two playlists' covers in one file; a key
that differs only by case still gets its own name.

## Audio and desktop integration

- Playback is FFmpeg → Java Sound. Java Sound reaches the system mixer through
  ALSA, which PipeWire and PulseAudio both provide a compatible layer for. A
  machine with no ALSA plugin for its server will list no output device; install
  `pipewire-alsa` or `pulseaudio-alsa`.
- Sound search records through `javax.sound.sampled`. PipeWire's portal asks for
  microphone permission the first time; a Flatpak or Snap build needs the
  `audio-record` permission as well.
- Media keys and the desktop's media widget use MPRIS over the D-Bus session
  bus (`org.mpris.MediaPlayer2.bitchord`).
- Discord Rich Presence finds its socket through `XDG_RUNTIME_DIR` and the
  Flatpak/Snap paths.

## CI

`.github/workflows/build-linux.yml` runs a matrix of `ubuntu-24.04` (x64) and
`ubuntu-24.04-arm` (arm64): native libraries, the full desktop test suite,
`deb`/`rpm`/AppImage, a check that the analyser and ONNX models are in the jar
and that the launcher's ELF architecture matches the runner, then uploads.

## Release publishing

`.github/workflows/release.yml` still packages Linux on `ubuntu-24.04` only, so
a tagged release currently attaches the x86_64 `.deb`, `.rpm` and AppImage.
The arm64 artifacts are built and verified by `build-linux.yml` on every push;
moving them into a release means giving that job the same two-entry matrix and
uploading `out/*` to the release, exactly as the macOS job does today.

## Known limits

- AppImage needs FUSE to run on the target machine; `--appimage-extract-and-run`
  is the fallback, and CI uses `APPIMAGE_EXTRACT_AND_RUN` because runners have
  no FUSE.
- No display or audio device exists on CI, so the window, the engine and the
  microphone are not exercised there.
- The arm64 packages are built on arm64 runners; there is no cross-packaged
  fallback.
