# Building BitChord for macOS

The desktop target is a JVM application packaged by Compose Desktop. Apple
Silicon and Intel are both supported, but each is built on a machine of its own
architecture: the bundled runtime, Skiko renderer, FFmpeg natives and JavaFX
WebKit are all the host's.

## What the build needs

| Tool | Why |
| --- | --- |
| JDK 21 | `desktopApp` compiles and packages with a Java 21 toolchain. |
| Xcode Command Line Tools | `clang`, `ld` and the SDK that CMake links the Automix analyser against (`xcode-select --install`). |
| CMake | Builds `libbitchord_analysis.dylib` from `native/analyzer`. Without it the app still builds and runs, with Automix unavailable. |
| `gradlew` | Everything else is resolved from the wrapper. |

Android modules are not configured for a desktop build:

```bash
./gradlew :desktopApp:run -Pbitchord.skipAndroid=true
```

## Packaging

```bash
# Apple Silicon (on an arm64 Mac)
./gradlew :desktopApp:packageDmg -Pbitchord.skipAndroid=true

# Intel (on an x64 Mac), or force the dependency classifiers
./gradlew :desktopApp:packageDmg -Pbitchord.skipAndroid=true -Pbitchord.arch=x64
```

`bitchord.arch` accepts `x64` or `arm64` and selects the per-architecture
artifacts: FFmpeg (`macosx-arm64` / `macosx-x86_64`), JavaFX (WebKit for
`mac-arm64` / `mac`) and Skiko (`macos_arm64` / `macos_x64`). It does not
cross-package; `jpackage` can only assemble the architecture it runs on.

The DMG is written to `desktopApp/build/compose/binaries/main/dmg/`.
`packagePkg` is also configured for a signed installer.

## Signing and notarization

Nothing is signed unless an identity is supplied, so a fork, a pull request and
any Mac without a developer certificate still produce a usable DMG. To sign,
put these in `local.properties` (or the environment, which CI uses):

```properties
MACOS_SIGN_IDENTITY=Developer ID Application: Your Name (TEAMID)
# Only needed for notarization, and only once signing works:
APPLE_ID=you@example.com
APPLE_APP_SPECIFIC_PASSWORD=app-specific-password
APPLE_TEAM_ID=TEAMID
```

`security find-identity -v -p codesigning` lists what the machine can use. The
password is an app-specific password from appleid.apple.com, not the Apple ID
password, and notarization additionally needs the identity above.

`packaging/macos/entitlements.plist` is applied to the bundle. It grants the
hardened runtime JIT and unsigned executable memory (Skiko and JavaFX render
with both), the microphone for sound search, and networking for NewPipeExtractor, Drive
backup, Listen Together and the local callback the browser sign-in returns to.

An unsigned build is quarantined by Gatekeeper on first launch:

```bash
xattr -dr com.apple.quarantine /Applications/BitChord.app
```

## Where it keeps things

| Kind | Location |
| --- | --- |
| Custom playlist/album covers | `~/Library/Application Support/bitchord/playlist_covers` |
| Motion-artwork clips | `~/Library/Caches/bitchord/media` |
| Downloads | `~/Music/BitChord` |
| Preferences | `java.util.prefs` (the app's bundle id) and the Keychain for browser-imported credentials |

Browser session import reads Chromium's `v10` cookies with the password from
the login Keychain (`Chrome Safe Storage` and its siblings) and 1003 rounds of
PBKDF2, which is macOS's own derivation; Firefox profiles are read in place.

## CI

`.github/workflows/build-macos.yml` builds both architectures on `macos-15` and
`macos-15-intel`, runs the full desktop test suite, verifies the bundle (the
microphone description, the analyser dylib, the ONNX models and the launcher's
architecture) and uploads the DMGs. Signing secrets are optional there too.

## Known limits

- No display or audio device exists on CI: the tests cover pure logic, so the
  window, the audio engine and the microphone prompt are not exercised there.
- Sound search needs Microphone permission; the first attempt raises the system
  prompt, and a refusal has to be undone in System Settings → Privacy & Security
  → Microphone.
- An arm64 DMG will not launch on Intel, and vice versa; there is no universal
  binary because the natives are per-architecture.
