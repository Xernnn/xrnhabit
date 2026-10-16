#!/usr/bin/env bash
# Installs xrnhabit on this laptop (KDE Plasma 6 on Linux): the daemon, its KWin script, and a
# systemd user service that keeps it running. Run it again to update. See desktop/README.md.
#
#   desktop/install.sh            build (needs JDK 17 in JAVA_HOME) and install
#   desktop/install.sh --no-build install what desktop/build/install already has
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(dirname "$here")"
data="${XDG_DATA_HOME:-$HOME/.local/share}"
config="${XDG_CONFIG_HOME:-$HOME/.config}"

if [[ "${1:-}" != "--no-build" ]]; then
  echo "Building…"
  (cd "$root" && ./gradlew -q :desktop:installDist)
fi

echo "Installing the daemon to $data/xrnhabit-desktop"
rm -rf "$data/xrnhabit-desktop"
cp -r "$root/desktop/build/install/xrnhabit-desktop" "$data/xrnhabit-desktop"

echo "Installing the KWin script"
mkdir -p "$data/kwin/scripts"
rm -rf "$data/kwin/scripts/xrnhabit"
cp -r "$here/kwin/xrnhabit" "$data/kwin/scripts/xrnhabit"
kwriteconfig6 --file kwinrc --group Plugins --key xrnhabitEnabled true
qdbus6 org.kde.KWin /Scripting org.kde.kwin.Scripting.unloadScript xrnhabit >/dev/null 2>&1 || true
qdbus6 org.kde.KWin /KWin reconfigure >/dev/null 2>&1 || true

if [[ ! -f "$config/xrnhabit/desktop.properties" ]]; then
  mkdir -p "$config/xrnhabit"
  cat > "$config/xrnhabit/desktop.properties" <<'PROPS'
# xrnhabit on the laptop. Leave server empty to block on this laptop only.
# Both sync services hang off the server: <server>/xrnhabit and <server>/xrnloops.
server=
user=
# Either password=… or a file holding just the password:
passwordFile=
port=47321
PROPS
  echo "Wrote $config/xrnhabit/desktop.properties: fill in the server and login to sync with the phone."
fi

echo "Starting the service"
mkdir -p "$config/systemd/user"
cp "$here/xrnhabit-desktop.service" "$config/systemd/user/"
systemctl --user daemon-reload
systemctl --user enable xrnhabit-desktop.service >/dev/null
systemctl --user restart xrnhabit-desktop.service

cat <<DONE

Done. The local page is http://127.0.0.1:47321/ (or the port in desktop.properties).
For websites, load the browser extension once:
  open the browser's extensions page (helium://extensions or chrome://extensions),
  turn on Developer mode, then "Load unpacked" and pick
  $here/extension
  and allow it in incognito windows too (its Details page).
DONE
