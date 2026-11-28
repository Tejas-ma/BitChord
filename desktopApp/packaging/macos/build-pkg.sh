#!/usr/bin/env bash
#
# Builds BitChord's macOS installer package from the app image jpackage wrote.
#
# Usage: build-pkg.sh <app-image-dir> <out.pkg> [<version> [<arch>]]
#
# <app-image-dir> is the directory holding BitChord.app — what
# :desktopApp:createDistributable writes. <arch> is the target architecture as
# this build understands it (x64 or arm64); it is translated to the name Apple's
# tools and the distribution file use.
#
# The result is a product archive with BitChord's own wizard pages, a macOS 12
# minimum, and the bundle installed into /Applications. Signing is optional: with
# MACOS_PKG_SIGN_IDENTITY set the archive is signed with productsign, and
# without it the same package is produced unsigned, which is what a fork or an
# unsigned CI run should get.
set -euo pipefail

APP_DIR_SRC=${1:?usage: build-pkg.sh <app-image-dir> <out.pkg> [<version> [<arch>]]}
OUT=${2:?usage: build-pkg.sh <app-image-dir> <out.pkg> [<version> [<arch>]]}
VERSION=${3:-1.0}
ARCH_IN=${4:-$(uname -m)}

HERE=$(cd "$(dirname "$0")" && pwd)
APP="$APP_DIR_SRC/BitChord.app"

case "$ARCH_IN" in
    arm64|aarch64) ARCH=arm64 ;;
    x64|x86_64|amd64) ARCH=x86_64 ;;
    *) echo "build-pkg: unknown architecture '$ARCH_IN' (expected x64 or arm64)" >&2; exit 1 ;;
esac

[ -d "$APP" ] || {
    echo "build-pkg: no BitChord.app in $APP_DIR_SRC — run :desktopApp:createDistributable first" >&2
    exit 1
}
[ -x "$APP/Contents/MacOS/BitChord" ] || {
    echo "build-pkg: $APP has no Contents/MacOS/BitChord launcher" >&2
    exit 1
}
for tool in pkgbuild productbuild; do
    command -v "$tool" >/dev/null 2>&1 || {
        echo "build-pkg: $tool is missing; a .pkg can only be built on macOS" >&2
        exit 1
    }
done

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# The payload root. pkgbuild treats this as the filesystem root, so the bundle
# has to sit at Applications/ to land in /Applications.
ROOT="$WORK/root"
mkdir -p "$ROOT/Applications"
cp -a "$APP" "$ROOT/Applications/"

# Debug symbols are build output, not something to ship: a release .app can
# carry them for its natives, and they can outweigh the bundle's own code.
find "$ROOT/Applications/BitChord.app" -name '*.dSYM' -prune -exec rm -rf {} + 2>/dev/null || true

# pkgbuild reads preinstall/postinstall from a directory of its own.
SCRIPTS="$WORK/scripts"
mkdir -p "$SCRIPTS"
cp "$HERE/postinstall" "$SCRIPTS/postinstall"
chmod 755 "$SCRIPTS/postinstall"

COMPONENT="$WORK/BitChord-component.pkg"
pkgbuild \
    --root "$ROOT" \
    --identifier "com.bitchord.desktop" \
    --version "$VERSION" \
    --install-location "/" \
    --scripts "$SCRIPTS" \
    --ownership recommended \
    "$COMPONENT"

# installKBytes is what Installer uses to check there is room; it has to be a
# number in the distribution file.
KB=$(du -sk "$APP" | awk '{print $1}')

sed \
    -e "s|__VERSION__|$VERSION|g" \
    -e "s|__ARCH__|$ARCH|g" \
    -e "s|__KB__|$KB|g" \
    "$HERE/distribution.xml" > "$WORK/distribution.xml"

mkdir -p "$(dirname "$OUT")"
BUILT="$WORK/BitChord-unsigned.pkg"
productbuild \
    --distribution "$WORK/distribution.xml" \
    --package-path "$WORK" \
    --resources "$HERE" \
    "$BUILT"

if [ -n "${MACOS_PKG_SIGN_IDENTITY:-}" ]; then
    command -v productsign >/dev/null 2>&1 || {
        echo "build-pkg: productsign is missing but MACOS_PKG_SIGN_IDENTITY is set" >&2
        exit 1
    }
    productsign --sign "$MACOS_PKG_SIGN_IDENTITY" "$BUILT" "$OUT"
    echo "build-pkg: signed with $MACOS_PKG_SIGN_IDENTITY"
else
    cp "$BUILT" "$OUT"
    echo "build-pkg: unsigned (set MACOS_PKG_SIGN_IDENTITY to sign)"
fi

# A package that opens but installs nothing is worse than a failed build, so
# read the payload back and check the pieces the app cannot run without.
pkgutil --payload-files "$OUT" > "$WORK/payload.txt"
for entry in \
    "Applications/BitChord.app/Contents/MacOS/BitChord" \
    "Applications/BitChord.app/Contents/Info.plist"; do
    grep -q "$entry" "$WORK/payload.txt" || {
        echo "build-pkg: the payload is missing $entry" >&2
        sed -n '1,40p' "$WORK/payload.txt" >&2
        exit 1
    }
done

echo "build-pkg: $OUT"
echo "build-pkg:   version $VERSION, architecture $ARCH, $KB KB installed"
