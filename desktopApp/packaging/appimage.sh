#!/usr/bin/env bash
#
# Wraps the app image jpackage produced into an AppImage, the one Linux format
# Gradle cannot make itself.
#
# Usage: desktopApp/packaging/appimage.sh <app-image-dir> <out.AppImage> [appimagetool]
#
# APPIMAGE_ARCH selects the AppImage architecture (x86_64 or aarch64); appimagetool
# refuses to wrap an arm64 image under the x86_64 name and vice versa.
#
# The tool is named by the third argument, then $APPIMAGE_TOOL, then the bare name
# on PATH. A relative path is resolved against the directory this script was
# started from *and* the repository root, because Gradle runs its Exec tasks from
# the desktopApp directory while CI downloads the tool into the checkout root —
# treating "./appimagetool" as unsplittable is what turned a downloaded tool into
# "No such file or directory" at exit 127.
set -euo pipefail

APP_DIR_SRC=${1:?usage: appimage.sh <app-image-dir> <out.AppImage> [appimagetool]}
OUT=${2:?usage: appimage.sh <app-image-dir> <out.AppImage> [appimagetool]}
TOOL=${3:-${APPIMAGE_TOOL:-appimagetool}}
ARCH=${APPIMAGE_ARCH:-x86_64}

[ -x "$APP_DIR_SRC/bin/BitChord" ] || { echo "no launcher in $APP_DIR_SRC" >&2; exit 1; }

ROOT=$(cd "$(dirname "$0")/../.." && pwd)

# Prints the path of a usable appimagetool and returns 0, or returns 1.
resolve_tool() {
    local wanted=$1 base candidate
    case "$wanted" in
        /*)
            # Absolute: the caller said exactly where it is.
            [ -x "$wanted" ] && { printf '%s\n' "$wanted"; return 0; }
            ;;
        */*)
            # Relative path: the caller's directory first, then the repository.
            for base in "$PWD" "$ROOT"; do
                candidate="$base/$wanted"
                [ -x "$candidate" ] && { printf '%s\n' "$candidate"; return 0; }
            done
            ;;
        *)
            # Bare name: whatever PATH resolves, so a distro package works too.
            if command -v "$wanted" >/dev/null 2>&1; then
                command -v "$wanted"
                return 0
            fi
            ;;
    esac
    return 1
}

if ! TOOL_PATH=$(resolve_tool "$TOOL"); then
    echo "appimage: appimagetool not found: '$TOOL'" >&2
    echo "appimage: tried it as given, in $PWD, in $ROOT, and on PATH." >&2
    echo "appimage: put appimagetool at the repository root, add it to PATH, or set APPIMAGE_TOOL to its absolute path." >&2
    exit 127
fi

STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
APPDIR="$STAGE/BitChord.AppDir"

mkdir -p "$APPDIR/usr"
cp -a "$APP_DIR_SRC/." "$APPDIR/usr/"

# The mount point differs every run, so nothing may be hard-coded.
cat > "$APPDIR/AppRun" <<'APPRUN'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
exec "$HERE/usr/bin/BitChord" "$@"
APPRUN
chmod +x "$APPDIR/AppRun"

cat > "$APPDIR/bitchord.desktop" <<'DESKTOP'
[Desktop Entry]
Type=Application
Name=BitChord
GenericName=Music Player
Comment=Aesthetic YouTube Music client
Exec=BitChord
Icon=bitchord
Categories=AudioVideo;Audio;Player;
Terminal=false
StartupWMClass=com-music-bitchord-desktop-MainKt
DESKTOP

# appimagetool reads the icon by the desktop entry; the desktop reads .DirIcon.
cp "$ROOT/desktopApp/packaging/icons/AppIcon.png" "$APPDIR/bitchord.png"
cp "$ROOT/desktopApp/packaging/icons/AppIcon.png" "$APPDIR/.DirIcon"

mkdir -p "$(dirname "$OUT")"
# APPIMAGE_EXTRACT_AND_RUN rather than the flag of the same name: it is read by the AppImage
# runtime, so it covers a CI runner with no FUSE, and a natively built appimagetool — which has
# no such flag and would refuse to start — simply ignores it.
ARCH="$ARCH" APPIMAGE_EXTRACT_AND_RUN=1 "$TOOL_PATH" "$APPDIR" "$OUT"
echo "appimage: $OUT"
