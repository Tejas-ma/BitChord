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
# manager. A graphical wizard is offered when a desktop session and one of zenity,
# yad or kdialog are present; otherwise the same steps run in the terminal, and
# --unattended skips both. Run it with --help for the flags.
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
FORCE_CLI=0
PREFIX=""
QUIET=0
SCOPE=""
ADMIN=0
WANT_MENU=1
WANT_SHORTCUT=0
DIALOG=""

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

Modes:
  (default)             Graphical wizard when a desktop session and zenity, yad
                        or kdialog are available; otherwise a terminal wizard.
  --cli                 Use the terminal wizard even when a GUI is available
  --unattended, -y      Install without asking anything (CI, scripts, servers)
  --uninstall           Remove an existing installation
  --list                List what the payload contains and exit

Install options:
  --prefix <dir>        Install to <dir> instead of the default
  --skip-integration    Do not create the launcher symlink or menu entry
  --desktop-shortcut    Also place a shortcut on the desktop
  --quiet               Print only errors
  -h, --help            Show this help

Defaults:
  Without sudo the app goes to ~/.local/opt/bitchord, with a launcher at
  ~/.local/bin/bitchord, a menu entry in ~/.local/share/applications, and its
  config and data under \$XDG_CONFIG_HOME/bitchord and \$XDG_DATA_HOME/bitchord.
  As root (or choosing the system-wide scope, which asks for admin rights) it
  goes to /opt/bitchord, with a launcher in /usr/local/bin and a menu entry in
  /usr/share/applications.

The payload's length and SHA-256 are checked before anything is extracted.
USAGE
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --unattended|-y) UNATTENDED=1 ;;
        --cli|--terminal) FORCE_CLI=1 ;;
        --prefix)
            [ "$#" -ge 2 ] || die "--prefix needs a directory"
            PREFIX=$2
            shift
            ;;
        --prefix=*) PREFIX=${1#*=} ;;
        --uninstall) MODE=uninstall ;;
        --list) MODE=list ;;
        --skip-integration) WANT_MENU=0 ;;
        --desktop-shortcut) WANT_SHORTCUT=1 ;;
        --quiet) QUIET=1 ;;
        -h|--help) usage; exit 0 ;;
        *) die "unknown option '$1' — run '$0 --help'" ;;
    esac
    shift
done

# Absolute, so an escalated child and the payload reader agree on which file
# this is even if the caller used a relative path.
SELF=$(cd "$(dirname "$0")" && pwd)/$(basename "$0")

if [ "$(id -u)" = 0 ]; then
    AS_ROOT=1
else
    AS_ROOT=0
fi

# --- Helpers --------------------------------------------------------------
# All of them are defined here, before the first call: a POSIX shell reads a
# script as it executes, so a function used above its definition simply fails.

# Single-quotes a value for a generated script, so a path with spaces or quotes
# survives being handed to sudo.
quote() {
    printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}

# Sets the destination and the integration directories for the chosen scope.
# Called again after the wizard picks a scope, because system-wide moves all of
# them: /opt and /usr instead of ~/.local.
resolve_dirs() {
    if [ "$AS_ROOT" = 1 ] || [ "$ADMIN" = 1 ]; then
        [ -n "$PREFIX" ] || PREFIX=/opt/bitchord
        BIN_DIR=/usr/local/bin
        DESKTOP_DIR=/usr/share/applications
        ICON_DIR=/usr/share/icons/hicolor/256x256/apps
        CONFIG_DIR=""
        DATA_APP_DIR=""
    else
        [ -n "$PREFIX" ] || PREFIX="$HOME/.local/opt/bitchord"
        BIN_DIR="$HOME/.local/bin"
        DESKTOP_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
        ICON_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/icons/hicolor/256x256/apps"
        CONFIG_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/bitchord"
        DATA_APP_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/bitchord"
    fi
    # Absolute, so the desktop entry does not depend on how the installer ran.
    case "$PREFIX" in
        /*) ;;
        *) PREFIX="$(pwd)/$PREFIX" ;;
    esac
}
resolve_dirs

shortcut_dir() {
    if command -v xdg-user-dir >/dev/null 2>&1; then
        xdg-user-dir DESKTOP 2>/dev/null || printf '%s\n' "$HOME/Desktop"
    else
        printf '%s\n' "$HOME/Desktop"
    fi
}

find_escalation() {
    if command -v pkexec >/dev/null 2>&1; then
        command -v pkexec
    elif command -v sudo >/dev/null 2>&1; then
        command -v sudo
    fi
}

write_desktop_entry() { # path, icon, mode
    cat > "$1" <<DESKTOP
[Desktop Entry]
Type=Application
Version=1.0
Name=$APP_NAME
GenericName=Music Player
Comment=Aesthetic YouTube Music client
Exec=$PREFIX/bin/BitChord %U
TryExec=$PREFIX/bin/BitChord
Icon=$2
Terminal=false
Categories=AudioVideo;Music;
Keywords=music;player;youtube;streaming;
StartupWMClass=com-music-bitchord-desktop-MainKt
DESKTOP
    chmod "$3" "$1"
}

launch_app() {
    if command -v setsid >/dev/null 2>&1; then
        setsid "$PREFIX/bin/BitChord" >/dev/null 2>&1 &
    else
        nohup "$PREFIX/bin/BitChord" >/dev/null 2>&1 &
    fi
}

remove_installation() {
    if [ -x "$PREFIX/uninstall.sh" ]; then
        "$PREFIX/uninstall.sh"
        return 0
    fi
    [ -d "$PREFIX" ] && rm -rf "$PREFIX"
    if [ -L "$BIN_DIR/bitchord" ] || [ -f "$BIN_DIR/bitchord" ]; then
        rm -f "$BIN_DIR/bitchord"
    fi
    rm -f "$DESKTOP_DIR/bitchord.desktop" "$ICON_DIR/bitchord.png"
    rm -f "$(shortcut_dir)/bitchord.desktop"
    say "BitChord removed from $PREFIX"
}

# --- The payload ----------------------------------------------------------
payload_line=$(awk -v marker="$MARKER" '$0 == marker { print NR + 1; exit }' "$SELF")
if [ -z "$payload_line" ]; then
    die "this installer is truncated: no payload marker"
fi

WORK=$(mktemp -d 2>/dev/null || mktemp -d -t bitchord)
trap 'rm -rf "$WORK"' EXIT INT TERM
PAYLOAD="$WORK/payload.tar.gz"
tail -n +"$payload_line" "$SELF" > "$PAYLOAD"

# --- Removal --------------------------------------------------------------
if [ "$MODE" = uninstall ]; then
    if [ ! -d "$PREFIX" ]; then
        die "nothing to uninstall: $PREFIX does not exist"
    fi
    # A system-wide installation is removed with the rights that made it.
    if [ ! -w "$PREFIX" ] && [ "$AS_ROOT" = 0 ]; then
        escalate=$(find_escalation) || true
        [ -n "${escalate:-}" ] || die "$PREFIX is not writable; run the installer with sudo"
        runner="$WORK/uninstall-as-root.sh"
        {
            printf '#!/bin/sh\n'
            printf 'exec %s --unattended --uninstall --prefix %s\n' \
                "$(quote "$SELF")" "$(quote "$PREFIX")"
        } > "$runner"
        chmod 755 "$runner"
        "$escalate" "$runner"
        exit $?
    fi
    remove_installation
    exit 0
fi

# --- Dialog abstraction ---------------------------------------------------
# zenity and yad share a command line; kdialog needs its own. Everything
# returns non-zero on Cancel, which is what each caller treats as "stop".
detect_dialog() {
    [ -n "${DISPLAY:-}" ] || [ -n "${WAYLAND_DISPLAY:-}" ] || return 1
    for candidate in zenity yad kdialog; do
        if command -v "$candidate" >/dev/null 2>&1; then
            DIALOG=$candidate
            return 0
        fi
    done
    return 1
}

dlg_info() {
    case "$DIALOG" in
        zenity|yad) "$DIALOG" --info --title "$1" --text "$2" --width 460 --no-wrap ;;
        kdialog) kdialog --title "$1" --msgbox "$2" ;;
        *) return 0 ;;
    esac
}

dlg_error() {
    case "$DIALOG" in
        zenity|yad) "$DIALOG" --error --title "$1" --text "$2" --width 460 ;;
        kdialog) kdialog --title "$1" --error "$2" ;;
        *) printf 'error: %s\n' "$2" >&2 ;;
    esac
}

dlg_confirm() { # title, text, [ok label]
    case "$DIALOG" in
        zenity|yad)
            "$DIALOG" --question --title "$1" --text "$2" --width 460 \
                --ok-label "${3:-Continue}" --cancel-label "Cancel"
            ;;
        kdialog) kdialog --title "$1" --yesno "$2" ;;
        *) return 0 ;;
    esac
}

dlg_entry() { # title, text, default
    case "$DIALOG" in
        zenity|yad) "$DIALOG" --entry --title "$1" --text "$2" --entry-text "$3" --width 460 ;;
        kdialog) kdialog --title "$1" --inputbox "$2" "$3" ;;
        *) printf '%s\n' "$3" ;;
    esac
}

dlg_scope() { # prints user, system or custom
    case "$DIALOG" in
        zenity|yad)
            "$DIALOG" --list --radiolist --title "$APP_NAME $VERSION" \
                --text "Where should $APP_NAME be installed?" \
                --width 540 --height 250 \
                --column "" --column "scope" --column "Location" \
                TRUE "user" "Only me — ~/.local/opt/bitchord" \
                FALSE "system" "All users — /opt/bitchord (asks for admin)" \
                FALSE "custom" "Choose a directory"
            ;;
        kdialog)
            kdialog --title "$APP_NAME $VERSION" --radiolist \
                "Where should $APP_NAME be installed?" \
                user "Only me — ~/.local/opt/bitchord" on \
                system "All users — /opt/bitchord (asks for admin)" off \
                custom "Choose a directory" off
            ;;
        *) printf 'user\n' ;;
    esac
}

dlg_options() { # prints the selected option tags
    case "$DIALOG" in
        zenity|yad)
            "$DIALOG" --list --checklist --title "$APP_NAME $VERSION" \
                --text "Shortcuts" --width 540 --height 220 --separator " " \
                --column "" --column "option" --column "What" \
                TRUE "menu" "Add an entry to the application menu" \
                FALSE "shortcut" "Add a shortcut on the desktop"
            ;;
        kdialog)
            kdialog --title "$APP_NAME $VERSION" --checklist "Shortcuts" \
                menu "Add an entry to the application menu" on \
                shortcut "Add a shortcut on the desktop" off
            ;;
        *) printf 'menu\n' ;;
    esac
}

# A progress window driven by lines on the dialog's stdin. zenity and yad read
# percentages and close themselves at 100; kdialog has no counterpart that can
# be driven without a D-Bus round trip, so that one falls back to plain terminal
# output for this step.
PROGRESS_FIFO=""
PROGRESS_PID=""

progress_begin() { # text
    case "$DIALOG" in
        zenity|yad) ;;
        *) return 0 ;;
    esac
    PROGRESS_FIFO="$WORK/progress.fifo"
    mkfifo "$PROGRESS_FIFO"
    "$DIALOG" --progress --title "$APP_NAME $VERSION" --text "$1" \
        --percentage=0 --auto-close --width 460 < "$PROGRESS_FIFO" &
    PROGRESS_PID=$!
    exec 3> "$PROGRESS_FIFO"
}

progress_set() {
    [ -n "$PROGRESS_FIFO" ] || return 0
    printf '%s\n' "$1" >&3 2>/dev/null || true
}

progress_end() {
    [ -n "$PROGRESS_FIFO" ] || return 0
    printf '100\n' >&3 2>/dev/null || true
    exec 3>&-
    wait "$PROGRESS_PID" 2>/dev/null || true
    PROGRESS_FIFO=""
}

# --- The wizard -----------------------------------------------------------
wizard_gui() {
    dlg_info "$APP_NAME $VERSION" \
"Welcome to $APP_NAME.

This installs $APP_NAME $VERSION for $ARCH in a few steps:
  1. where to install it
  2. shortcuts
  3. verification and extraction

Nothing outside the location you choose is written, and an uninstaller is
created there." || return 1

    scope=$(dlg_scope) || return 1
    case "$scope" in
        system)
            SCOPE=system
            [ "$AS_ROOT" = 1 ] || ADMIN=1
            PREFIX=/opt/bitchord
            ;;
        custom|"")
            SCOPE=custom
            PREFIX=$(dlg_entry "$APP_NAME $VERSION" \
                "Install directory" "$HOME/.local/opt/bitchord") || return 1
            ;;
        *)
            SCOPE=user
            PREFIX="$HOME/.local/opt/bitchord"
            ;;
    esac

    # The path stays editable for every scope, which is how a custom directory
    # is expressed without a second question.
    edited=$(dlg_entry "$APP_NAME $VERSION" "Install directory" "$PREFIX") || return 1
    [ -n "$edited" ] && PREFIX=$edited
    resolve_dirs

    options=$(dlg_options) || return 1
    WANT_MENU=0
    WANT_SHORTCUT=0
    case "$options" in *menu*) WANT_MENU=1 ;; esac
    case "$options" in *shortcut*) WANT_SHORTCUT=1 ;; esac
    return 0
}

wizard_terminal() {
    printf '\n%s %s (%s)\n\n' "$APP_NAME" "$VERSION" "$ARCH"
    say "This installs $APP_NAME and creates an uninstaller beside it."

    printf 'Install scope:\n'
    printf '  1) Only me      — %s\n' "$HOME/.local/opt/bitchord"
    printf '  2) All users    — /opt/bitchord (needs admin)\n'
    printf '  3) Custom directory\n'
    printf 'Choice [1]: '
    read -r answer || answer=1
    case "$answer" in
        2)
            SCOPE=system
            [ "$AS_ROOT" = 1 ] || ADMIN=1
            PREFIX=/opt/bitchord
            ;;
        3)
            SCOPE=custom
            printf 'Install directory: '
            read -r answer_path || answer_path=""
            [ -n "$answer_path" ] || die "no directory given"
            PREFIX=$answer_path
            ;;
        *) SCOPE=user ;;
    esac
    resolve_dirs

    if [ "$SCOPE" != custom ]; then
        printf 'Install directory [%s]: ' "$PREFIX"
        read -r answer_path || answer_path=""
        [ -n "$answer_path" ] && PREFIX=$answer_path
    fi
    resolve_dirs

    printf 'Add an application menu entry? [Y/n] '
    read -r answer || answer=y
    case "$answer" in [nN]*) WANT_MENU=0 ;; esac
    printf 'Add a desktop shortcut? [y/N] '
    read -r answer || answer=n
    case "$answer" in [yY]*) WANT_SHORTCUT=1 ;; esac

    printf '\n  Install directory : %s\n' "$PREFIX"
    printf '  Menu entry        : %s\n' "$( [ "$WANT_MENU" = 1 ] && printf 'yes' || printf 'no')"
    printf '  Desktop shortcut  : %s\n\n' "$( [ "$WANT_SHORTCUT" = 1 ] && printf 'yes' || printf 'no')"
    printf 'Continue? [Y/n] '
    read -r answer || answer=y
    case "$answer" in [nN]*) say "cancelled — nothing was installed"; return 1 ;; esac
    return 0
}

if [ "$UNATTENDED" = 0 ] && [ "$FORCE_CLI" = 0 ] && detect_dialog; then
    wizard_gui || { say "cancelled — nothing was installed"; exit 1; }
elif [ "$UNATTENDED" = 0 ]; then
    # --cli asks for the terminal wizard explicitly, which is also the only way
    # to answer it from a pipe — `read` works on a pipe even when stdin is not a
    # terminal, so a scripted run can still choose a scope instead of silently
    # installing to the default place.
    if [ -t 0 ] || [ "$FORCE_CLI" = 1 ]; then
        wizard_terminal || exit 1
    fi
fi

# --- Verification and installation ---------------------------------------
# A system-wide install from a normal user session is handed back to this same
# script with admin rights, which re-verifies the payload itself before writing
# anything into /opt.
if [ "$SCOPE" = system ] && [ "$AS_ROOT" = 0 ] && [ "$UNATTENDED" = 0 ]; then
    escalate=$(find_escalation) || true
    if [ -z "${escalate:-}" ]; then
        dlg_error "$APP_NAME" "A system-wide install needs pkexec or sudo, and neither was found." || true
        die "a system-wide install needs pkexec or sudo"
    fi
    runner="$WORK/install-as-root.sh"
    {
        printf '#!/bin/sh\n'
        printf 'exec %s --unattended --prefix %s' "$(quote "$SELF")" "$(quote "$PREFIX")"
        [ "$WANT_MENU" = 1 ] || printf ' --skip-integration'
        [ "$WANT_SHORTCUT" = 1 ] && printf ' --desktop-shortcut'
        printf '\n'
    } > "$runner"
    chmod 755 "$runner"
    say "asking for administrator rights…"
    if ! "$escalate" "$runner"; then
        dlg_error "$APP_NAME" "The system-wide install did not complete." || true
        exit 1
    fi
    if [ "$WANT_SHORTCUT" = 1 ] && [ -n "$DIALOG" ]; then
        desktop=$(shortcut_dir)
        mkdir -p "$desktop"
        write_desktop_entry "$desktop/bitchord.desktop" "$APP_NAME" 755
    fi
    if dlg_confirm "$APP_NAME $VERSION" \
        "$APP_NAME is installed in $PREFIX.

Launch it now?" "Launch"; then
        launch_app
    fi
    say "$APP_NAME $VERSION installed in $PREFIX."
    exit 0
fi

progress_begin "Verifying the download…"
progress_set 10

actual_bytes=$(wc -c < "$PAYLOAD" | tr -d ' ')
progress_set 25
if [ "$actual_bytes" != "$PAYLOAD_BYTES" ]; then
    progress_end
    dlg_error "$APP_NAME" \
        "The installer is incomplete: $actual_bytes of $PAYLOAD_BYTES bytes. Download it again." || true
    die "the payload is $actual_bytes bytes but should be $PAYLOAD_BYTES — the download is truncated"
fi

command -v sha256sum >/dev/null 2>&1 || { progress_end; die "sha256sum is needed to verify the payload"; }
actual_sha=$(sha256sum "$PAYLOAD" | cut -d' ' -f1)
progress_set 40
if [ "$actual_sha" != "$EXPECTED_SHA256" ]; then
    progress_end
    dlg_error "$APP_NAME" \
        "The download is corrupt: its checksum does not match.

Download the installer again." || true
    die "checksum mismatch: expected $EXPECTED_SHA256, got $actual_sha — the download is corrupt"
fi

if [ "$MODE" = list ]; then
    progress_end
    tar -tzf "$PAYLOAD"
    exit 0
fi

say "installing to $PREFIX"
mkdir -p "$PREFIX"
progress_set 55
tar -xzf "$PAYLOAD" -C "$PREFIX"
[ -x "$PREFIX/bin/BitChord" ] || die "the payload did not contain bin/BitChord"
chmod 755 "$PREFIX/bin/BitChord"
progress_set 75

# --- Desktop integration --------------------------------------------------
ICON_LINE="$APP_NAME"
if [ "$WANT_MENU" = 1 ]; then
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
    fi

    mkdir -p "$DESKTOP_DIR"
    write_desktop_entry "$DESKTOP_DIR/bitchord.desktop" "$ICON_LINE" 644
    # Both are optional; a minimal or headless install has neither.
    if command -v update-desktop-database >/dev/null 2>&1; then
        update-desktop-database "$DESKTOP_DIR" >/dev/null 2>&1 || true
    fi
    if [ -n "$ICON_SRC" ] && command -v gtk-update-icon-cache >/dev/null 2>&1; then
        gtk-update-icon-cache -q -t -f "$(dirname "$(dirname "$ICON_DIR")")" >/dev/null 2>&1 || true
    fi
fi

if [ "$WANT_SHORTCUT" = 1 ]; then
    desktop=$(shortcut_dir)
    mkdir -p "$desktop"
    write_desktop_entry "$desktop/bitchord.desktop" "$ICON_LINE" 755
    if command -v gio >/dev/null 2>&1; then
        gio set "$desktop/bitchord.desktop" metadata::trusted true >/dev/null 2>&1 || true
    fi
fi

# The app creates these on first run; making them now means the first launch has
# nothing to do and their permissions are the installer's, not the umask's.
if [ -n "$CONFIG_DIR" ]; then
    mkdir -p "$CONFIG_DIR" "$DATA_APP_DIR"
    chmod 700 "$CONFIG_DIR" 2>/dev/null || true
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
SHORTCUT_DIR='$(shortcut_dir)'
if [ -L "\$BIN_DIR/bitchord" ] || [ -f "\$BIN_DIR/bitchord" ]; then
    rm -f "\$BIN_DIR/bitchord"
fi
rm -f "\$DESKTOP_DIR/bitchord.desktop"
rm -f "\$ICON_DIR/bitchord.png"
rm -f "\$SHORTCUT_DIR/bitchord.desktop"
[ -n "\$PREFIX" ] && [ -d "\$PREFIX" ] && rm -rf "\$PREFIX"
if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "\$DESKTOP_DIR" >/dev/null 2>&1 || true
fi
echo "BitChord removed from \$PREFIX"
UNINSTALL
chmod 755 "$PREFIX/uninstall.sh"

progress_set 100
progress_end

# --- Completion -----------------------------------------------------------
if [ -n "$DIALOG" ]; then
    if dlg_confirm "$APP_NAME $VERSION" \
        "$APP_NAME $VERSION is installed in $PREFIX.

Launch it now?" "Launch"; then
        launch_app
    else
        dlg_info "$APP_NAME" "You can start $APP_NAME from your applications menu, or with:

$PREFIX/bin/BitChord"
    fi
fi

say ""
say "$APP_NAME $VERSION installed."
say "  application : $PREFIX"
if [ "$WANT_MENU" = 1 ]; then
    say "  launcher    : $BIN_DIR/bitchord"
    say "  menu entry  : $DESKTOP_DIR/bitchord.desktop"
fi
if [ "$WANT_SHORTCUT" = 1 ]; then
    say "  shortcut    : $(shortcut_dir)/bitchord.desktop"
fi
if [ -n "$CONFIG_DIR" ]; then
    say "  config      : $CONFIG_DIR"
    say "  data        : $DATA_APP_DIR"
fi
say ""
say "Run it with: $PREFIX/bin/BitChord"
say "Remove it with: $PREFIX/uninstall.sh"

# The payload starts on the next line. Without this the shell would keep reading
# and try to execute the marker and the archive as commands, exiting 127 after a
# perfectly successful install.
exit 0
