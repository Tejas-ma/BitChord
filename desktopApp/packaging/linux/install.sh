#!/bin/sh
#
# The header of a BitChord Linux .run installer.
#
# This file is a template: build-run-installer.sh substitutes the @NAME@ values
# below, appends a marker line and the compressed payload, and marks the result
# executable. It is never run from the repository.
#
# It deliberately uses only what a base system has — POSIX sh, tar, sha256sum —
# so the installer runs on a machine that has neither makeself nor a package
# manager. Run it with --help for the flags.
set -eu

APP_NAME="BitChord"
VERSION="@VERSION@"
ARCH="@ARCH@"
EXPECTED_SHA256="@SHA256@"
PAYLOAD_BYTES="@PAYLOAD_BYTES@"
BUILT_AT="@BUILT_AT@"

MARKER="__BITCHORD_PAYLOAD_BELOW__"

MODE=install
UNATTENDED=0
PREFIX=""
QUIET=0

say() {
    [ "$QUIET" = 1 ] || printf '%s\n' "$*"
}

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

usage() {
    cat <<USAGE
$APP_NAME $VERSION ($ARCH) — self-extracting installer, built $BUILT_AT

Usage: $0 [options]

Options:
  --unattended          Install without asking anything (also -y)
  --prefix <dir>        Install to <dir> instead of the default
  --uninstall           Remove an existing installation from <dir>
  --list                List what the payload contains and exit
  --skip-integration    Do not create the launcher symlink or desktop entry
  --quiet               Print only errors
  -h, --help            Show this help

Defaults:
  Without sudo the app goes to ~/.local/opt/bitchord, with a launcher at
  ~/.local/bin/bitchord and a menu entry in ~/.local/share/applications.
  As root it goes to /opt/bitchord, with a launcher in /usr/local/bin and a
  menu entry in /usr/share/applications.

The payload is checked (length and SHA-256) before anything is extracted.
USAGE
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --unattended|-y) UNATTENDED=1 ;;
        --prefix)
            [ "$#" -ge 2 ] || die "--prefix needs a directory"
            PREFIX=$2
            shift
            ;;
        --prefix=*) PREFIX=${1#*=} ;;
        --uninstall) MODE=uninstall ;;
        --list) MODE=list ;;
        --skip-integration) ALREADY_SKIPPED=1 ;;
        --quiet) QUIET=1 ;;
        -h|--help) usage; exit 0 ;;
        *) die "unknown option '$1' — run '$0 --help'" ;;
    esac
    shift
done

# Where the installing user may write. Root gets the system locations the
# specification names; everyone else stays inside their home directory.
if [ "$(id -u)" = 0 ]; then
    AS_ROOT=1
    [ -n "$PREFIX" ] || PREFIX=/opt/bitchord
    BIN_DIR=/usr/local/bin
    DESKTOP_DIR=/usr/share/applications
    ICON_DIR=/usr/share/icons/hicolor/256x256/apps
else
    AS_ROOT=0
    [ -n "$PREFIX" ] || PREFIX="$HOME/.local/opt/bitchord"
    BIN_DIR="$HOME/.local/bin"
    DATA_DIR=${XDG_DATA_HOME:-$HOME/.local/share}
    DESKTOP_DIR="$DATA_DIR/applications"
    ICON_DIR="$DATA_DIR/icons/hicolor/256x256/apps"
fi

# Absolute, so the desktop entry does not depend on how the installer was run.
case "$PREFIX" in
    /*) ;;
    *) PREFIX="$(pwd)/$PREFIX" ;;
esac

remove_installation() {
    if [ -x "$PREFIX/uninstall.sh" ]; then
        "$PREFIX/uninstall.sh"
        return
    fi
    # A directory with no uninstall.sh of its own: remove what an install of
    # this prefix would have created.
    [ -d "$PREFIX" ] && rm -rf "$PREFIX"
    [ -L "$BIN_DIR/bitchord" ] && rm -f "$BIN_DIR/bitchord"
    rm -f "$DESKTOP_DIR/bitchord.desktop" "$ICON_DIR/bitchord.png"
    say "BitChord removed from $PREFIX"
}

if [ "$MODE" = uninstall ]; then
    [ -d "$PREFIX" ] || die "nothing to uninstall: $PREFIX does not exist"
    remove_installation
    exit 0
fi

# --- The payload ----------------------------------------------------------
# Everything after the marker line is the archive. Reading it back out of this
# same file is what makes the installer a single download.
PAYLOAD_LINE=$(awk -v marker="$MARKER" '$0 == marker { print NR + 1; exit }' "$0")
[ -n "$PAYLOAD_LINE" ] || die "this installer is truncated: no payload marker"

WORK=$(mktemp -d 2>/dev/null || mktemp -d -t bitchord)
trap 'rm -rf "$WORK"' EXIT INT TERM
PAYLOAD="$WORK/payload.tar.gz"
tail -n +"$PAYLOAD_LINE" "$0" > "$PAYLOAD"

ACTUAL_BYTES=$(wc -c < "$PAYLOAD" | tr -d ' ')
[ "$ACTUAL_BYTES" = "$PAYLOAD_BYTES" ] || die \
    "the payload is $ACTUAL_BYTES bytes but should be $PAYLOAD_BYTES — the download is truncated"

command -v sha256sum >/dev/null 2>&1 || die "sha256sum is needed to verify the payload"
ACTUAL_SHA=$(sha256sum "$PAYLOAD" | cut -d' ' -f1)
[ "$ACTUAL_SHA" = "$EXPECTED_SHA256" ] || die \
    "checksum mismatch: expected $EXPECTED_SHA256, got $ACTUAL_SHA — the download is corrupt"

if [ "$MODE" = list ]; then
    tar -tzf "$PAYLOAD"
    exit 0
fi

# --- The wizard -----------------------------------------------------------
if [ "$UNATTENDED" = 0 ] && [ -t 0 ]; then
    printf '\n%s %s (%s)\n\n' "$APP_NAME" "$VERSION" "$ARCH"
    printf '  Install directory : %s\n' "$PREFIX"
    printf '  Launcher          : %s/bitchord\n' "$BIN_DIR"
    printf '  Menu entry        : %s/bitchord.desktop\n' "$DESKTOP_DIR"
    printf '\nThe payload checksum has been verified (%s).\n\n' "$ACTUAL_SHA"
    printf 'Install now? [Y/n] '
    read -r reply || reply=y
    case "$reply" in
        [nN]*) say "cancelled — nothing was installed"; exit 1 ;;
    esac
fi

# --- Extraction -----------------------------------------------------------
mkdir -p "$PREFIX"
say "extracting to $PREFIX"
tar -xzf "$PAYLOAD" -C "$PREFIX"
[ -x "$PREFIX/bin/BitChord" ] || die "the payload did not contain bin/BitChord"
chmod 755 "$PREFIX/bin/BitChord"

# --- Desktop integration --------------------------------------------------
if [ "${ALREADY_SKIPPED:-0}" != 1 ]; then
    mkdir -p "$BIN_DIR"
    ln -sfn "$PREFIX/bin/BitChord" "$BIN_DIR/bitchord"

    ICON_SRC=""
    for candidate in "$PREFIX/lib/BitChord.png" "$PREFIX/lib/bitchord.png"; do
        if [ -f "$candidate" ]; then ICON_SRC=$candidate; break; fi
    done
    if [ -z "$ICON_SRC" ]; then
        ICON_SRC=$(find "$PREFIX" -maxdepth 3 -name 'BitChord.png' -print -quit 2>/dev/null || true)
    fi
    if [ -n "$ICON_SRC" ]; then
        mkdir -p "$ICON_DIR"
        cp "$ICON_SRC" "$ICON_DIR/bitchord.png"
        ICON_LINE="$ICON_DIR/bitchord.png"
    else
        ICON_LINE="$APP_NAME"
    fi

    mkdir -p "$DESKTOP_DIR"
    cat > "$DESKTOP_DIR/bitchord.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Version=1.0
Name=$APP_NAME
GenericName=Music Player
Comment=Aesthetic YouTube Music client
Exec=$BIN_DIR/bitchord %U
TryExec=$BIN_DIR/bitchord
Icon=$ICON_LINE
Terminal=false
Categories=AudioVideo;Music;
Keywords=music;player;youtube;streaming;
StartupWMClass=com-music-bitchord-desktop-MainKt
DESKTOP
    chmod 644 "$DESKTOP_DIR/bitchord.desktop"

    # Both are optional; a headless or minimal install simply has neither.
    if command -v update-desktop-database >/dev/null 2>&1; then
        update-desktop-database "$DESKTOP_DIR" >/dev/null 2>&1 || true
    fi
    if [ -n "${ICON_SRC:-}" ] && command -v gtk-update-icon-cache >/dev/null 2>&1; then
        gtk-update-icon-cache -q -t -f "$(dirname "$(dirname "$ICON_DIR")")" >/dev/null 2>&1 || true
    fi
fi

# --- Uninstaller ----------------------------------------------------------
cat > "$PREFIX/uninstall.sh" <<UNINSTALL
#!/bin/sh
# Removes this BitChord installation. Written by the BitChord $VERSION installer.
set -eu
PREFIX='$PREFIX'
BIN_DIR='$BIN_DIR'
DESKTOP_DIR='$DESKTOP_DIR'
ICON_DIR='$ICON_DIR'
if [ -L "\$BIN_DIR/bitchord" ] || [ -f "\$BIN_DIR/bitchord" ]; then
    rm -f "\$BIN_DIR/bitchord"
fi
rm -f "\$DESKTOP_DIR/bitchord.desktop"
rm -f "\$ICON_DIR/bitchord.png"
[ -n "\$PREFIX" ] && [ -d "\$PREFIX" ] && rm -rf "\$PREFIX"
if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "\$DESKTOP_DIR" >/dev/null 2>&1 || true
fi
echo "BitChord removed from \$PREFIX"
UNINSTALL
chmod 755 "$PREFIX/uninstall.sh"

say ""
say "$APP_NAME $VERSION installed."
say "  application : $PREFIX"
if [ "${ALREADY_SKIPPED:-0}" != 1 ]; then
    say "  launcher    : $BIN_DIR/bitchord"
    say "  menu entry  : $DESKTOP_DIR/bitchord.desktop"
fi
say ""
say "Run it with: $PREFIX/bin/BitChord"
say "Remove it with: $PREFIX/uninstall.sh"

# The payload starts on the next line. Without this the shell would keep reading
# and try to execute the marker and the archive as commands, exiting 127 after a
# perfectly successful install.
exit 0

