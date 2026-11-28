#!/usr/bin/env bash
#
# Builds the single-file Linux installer: a POSIX sh header (install.sh) with a
# tar.gz payload of the app image appended to it.
#
# Usage: build-run-installer.sh <app-image-dir> <out.run> [<version> [<arch>]]
#
# The header records the payload's exact length and SHA-256, so the installer
# can refuse a truncated or corrupted download before it extracts anything.
set -euo pipefail

APP_DIR=${1:?usage: build-run-installer.sh <app-image-dir> <out.run> [<version> [<arch>]]}
OUT=${2:?usage: build-run-installer.sh <app-image-dir> <out.run> [<version> [<arch>]]}
VERSION=${3:-1.0}
ARCH=${4:-x64}

HERE=$(cd "$(dirname "$0")" && pwd)

[ -x "$APP_DIR/bin/BitChord" ] || {
    echo "build-run-installer: no launcher in $APP_DIR — run :desktopApp:createDistributable first" >&2
    exit 1
}
[ -f "$HERE/install.sh" ] || {
    echo "build-run-installer: $HERE/install.sh is missing" >&2
    exit 1
}

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# A sorted, zeroed archive so two builds of the same image produce the same
# bytes. Byte-identical output is what lets a release be re-checked against the
# digest in its notes; GNU tar is used on every Linux runner, and the fallback
# keeps the script working with a plainer tar.
if ! tar -C "$APP_DIR" --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner \
        -czf "$WORK/payload.tar.gz" . 2>/dev/null; then
    tar -C "$APP_DIR" -czf "$WORK/payload.tar.gz" .
fi

PAYLOAD_BYTES=$(wc -c < "$WORK/payload.tar.gz" | tr -d ' ')
SHA256=$(sha256sum "$WORK/payload.tar.gz" | cut -d' ' -f1)
BUILT_AT=$(date -u '+%Y-%m-%dT%H:%M:%SZ')

mkdir -p "$(dirname "$OUT")"

# One pass over the template. The values are ours (a version string, an
# architecture label, a hex digest and two integers), so there is nothing here
# that needs shell escaping.
sed \
    -e "s|@VERSION@|$VERSION|g" \
    -e "s|@ARCH@|$ARCH|g" \
    -e "s|@SHA256@|$SHA256|g" \
    -e "s|@PAYLOAD_BYTES@|$PAYLOAD_BYTES|g" \
    -e "s|@BUILT_AT@|$BUILT_AT|g" \
    "$HERE/install.sh" > "$WORK/header.sh"

: > "$OUT"
cat "$WORK/header.sh" >> "$OUT"
printf '%s\n' '__BITCHORD_PAYLOAD_BELOW__' >> "$OUT"
cat "$WORK/payload.tar.gz" >> "$OUT"
chmod 755 "$OUT"

# Prove the result reads back before anyone downloads it: the header's own
# --list mode re-reads the tail, verifies the length and SHA-256, and unpacks
# the archive listing.
"$OUT" --list > "$WORK/listing.txt" || {
    echo "build-run-installer: the installer header could not read its own payload" >&2
    exit 1
}
grep -q '^\./bin/BitChord$' "$WORK/listing.txt" || {
    echo "build-run-installer: the payload does not contain bin/BitChord" >&2
    exit 1
}

echo "run installer: $OUT"
echo "  version : $VERSION ($ARCH)"
echo "  payload : $PAYLOAD_BYTES bytes, sha256 $SHA256"
echo "  entries : $(wc -l < "$WORK/listing.txt" | tr -d ' ')"
