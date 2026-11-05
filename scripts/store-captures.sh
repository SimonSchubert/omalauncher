#!/usr/bin/env bash
# Captures the Play listing's screens (store/screenshots/raw/<language>/, framed by store/screenshots/frame.html and
# scripts/render-screenshots.sh): home screens and a face in several themes with widgets from the widget store,
# and a few screens beyond home, in each of the launcher's languages (LANGS, default all six; the launcher's own
# language pick is set for the run).
#
# The display is shrunk to 1080x1670 for the run, the room a slide leaves below its caption, so the launcher lays
# itself out for that and nothing is cropped or squashed. System UI demo mode keeps the clock and icons tidy.
#
# Needs a debug build (run-as, the `theme` and `exec` extras) with Linux and the coding agents in RECENT installed,
# on an emulator with Google Play so the Play Store is there to show: the Omalauncher_Play AVD, which needs no root.
# The launcher's widgets, home layout, pinned apps, recent apps and theme are backed up first and put back
# afterwards, as are the display size and demo mode.
#
# Voice is shown with a conversation from store/screenshots/l10n/voice-<language>.json, resumed, and a stand-in
# `claude` that only waits, so no account is needed.
#
# Weather comes from store/screenshots/wttr-mock.py rather than wttr.in (WTTR=live for the real one), so every
# shot has the same sky. Themes from GitHub in REPO_THEMES are installed first, as the style screen's [ install ] would.
#
#   [LANGS="en de"] scripts/store-captures.sh [store repo, default ../omarchy-widgets] [shot...]
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
STORE="${1:-$HERE/../../omarchy-widgets}"
shift || true
export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5562}"
RAW="$HERE/../store/screenshots/raw"
L10N="$HERE/../store/screenshots/l10n"
RES="$HERE/../app/src/main/res"
LANGS=(${LANGS:-en id es pt de zh})
SHIMS=files/arch/root/.local/share/mise/shims
PKG=dev.omalauncher
DATA=/data/data/$PKG
BACKUP=cache/store-backup.tar
SIZE=1080x1670

# Shot, theme, then the widgets on its home screen in placement order (the launcher puts each in the first free
# spot) as `<name>` for simonschubert's or `<author>/<name>`, `face:<name>` for a face or `app:<name>` for an app. A shot with a `shot_<name>` function below runs that
# after they're placed, to open the screen it shows. Seven rows fit: two rows of 3-high tiles above the recent apps.
SCENES=(
  "home        to-beyond         fetch today koi-pond"
  "manga       flexoki-light     face:manga"
  "voice       kanagawa          today"
  "linux       matte-black       today"
  "live        jade-horse        ssalentin/red-panda hieitheevileye/fatcat dimasmds/hootling asphyksia/companion"
  "light       catppuccin-latte  boogercheeseonrye/dog-of-the-day travonted/apod weebowiz/trip-view rcrohmana/classic-clock"
  "store       gruvbox           today"
  "style       ristretto         today"
)

# Themes the scenes use that aren't built in, as GitHub owner/repo.
REPO_THEMES=(cbarland/omarchy-to-beyond-theme)
WTTR_PORT=8787

# The recent apps strip, first to last.
RECENT=(
  l:agent-claude l:foot l:agent-grok a:com.android.vending/.AssetBrowserActivity l:agent-codex l:agent-opencode
  l:agent-muse l:agent-kimi l:agent-omp
)
adb wait-for-device

# Runs a shell command as the launcher, in its data dir. Files from the Mac come in through /data/local/tmp.
app() { adb shell "run-as $PKG sh -c 'cd $DATA && $*'"; }

adb shell am force-stop $PKG
app "tar cf $BACKUP files/widgets files/voice shared_prefs"
restore() {
  [ -z "${mock:-}" ] || kill "$mock" 2>/dev/null || true
  adb shell am broadcast -a com.android.systemui.demo -e command exit >/dev/null
  adb shell wm size reset
  adb shell settings delete secure stylus_handwriting_enabled >/dev/null
  if [ "${clock:-null}" = null ]; then adb shell settings delete system time_12_24 >/dev/null; else adb shell settings put system time_12_24 "$clock"; fi
  adb shell am force-stop $PKG
  app "[ ! -e $SHIMS/.claude-store ] || mv $SHIMS/.claude-store $SHIMS/claude"
  app "rm -rf files/widgets/*.owl files/widgets/.store-ff.sh files/voice shared_prefs/app_usage.xml && tar xf $BACKUP && rm $BACKUP"
  adb shell am start -n $PKG/.MainActivity >/dev/null
  echo "restored the launcher's widgets and theme"
}
trap restore EXIT

adb shell wm size $SIZE
adb shell settings put global sysui_demo_allowed 1
# Otherwise Gboard can open its stylus tutorial over the drawer's keyboard.
adb shell settings put secure stylus_handwriting_enabled 0
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
demo enter
# The real time on a 24-hour clock, so the status bar agrees with clock widgets and faces.
clock=$(adb shell settings get system time_12_24 | tr -d '\r')
adb shell settings put system time_12_24 24
demo clock -e hhmm "$(date +%H%M)"
demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4 -e fully true
demo network -e mobile hide
demo notifications -e visible false
# The manga face reads the newest notification.
adb shell cmd notification allow_listener $PKG/$PKG.widgets.runtime.MediaListener
# Voice listens without asking first.
adb shell pm grant $PKG android.permission.RECORD_AUDIO

# Polls a condition on the device for up to $1 seconds.
wait_for() {
  local secs=$1
  shift
  for _ in $(seq "$secs"); do
    adb shell "$@" >/dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}

# Puts store file $1 (relative to the store repo) into the widgets dir as $2, its weather from the mock.
install() {
  local f
  f=$(mktemp)
  if [ "${WTTR:-}" = live ]; then cp "$STORE/$1" "$f"; else sed "s#https://wttr.in/#http://10.0.2.2:$WTTR_PORT/#g" "$STORE/$1" >"$f"; fi
  adb push "$f" /data/local/tmp/store.owl >/dev/null 2>&1
  rm "$f"
  app "cp /data/local/tmp/store.owl files/widgets/.$2 && mv files/widgets/.$2 files/widgets/$2"
}

# Puts file $1 from the Mac at $2 in the launcher's data dir.
put() {
  adb push "$1" /data/local/tmp/store-put >/dev/null 2>&1
  app "cp /data/local/tmp/store-put $2"
}

# String $1 of the app in the language being captured, as the screen shows it.
str() {
  local dir=values
  case $L in en) ;; id) dir=values-in ;; *) dir=values-$L ;; esac
  grep -h "name=\"$1\"" "$RES/$dir"/strings_*.xml | head -1 | sed -E 's/.*">(.*)<\/string>.*/\1/; s/\\(.)/\1/g'
}

# The launcher's language pick: $1.
language() {
  local f
  f=$(mktemp)
  printf '%s\n' 'f=shared_prefs/launcher.xml' "sed -i '/name=\"language\"/d' \$f" "sed -i 's#<map>#<map><string name=\"language\">$1</string>#' \$f" >"$f"
  put "$f" cache/store-lang.sh
  rm "$f"
  app "sh cache/store-lang.sh"
}

# Installs GitHub theme $1 (owner/repo) as the launcher does: its top-level files and backgrounds, and where it
# came from, in the user's themes under the repo's name without omarchy- and -theme.
repo_theme() {
  local name tmp
  name=${1#*/}
  name=${name#omarchy-}
  name=${name%-theme}
  tmp=$(mktemp -d)
  mkdir "$tmp/$name"
  curl -fsSL "https://codeload.github.com/$1/tar.gz/HEAD" | tar xz -C "$tmp/$name" --strip-components=1
  find "$tmp/$name" -mindepth 1 -maxdepth 1 -type d ! -name backgrounds -exec rm -rf {} +
  find "$tmp/$name/backgrounds" -mindepth 1 -type d -exec rm -rf {} + 2>/dev/null || true
  echo "https://github.com/$1" >"$tmp/$name/.omalauncher-source"
  adb shell rm -rf /data/local/tmp/store-theme
  adb push "$tmp/$name" /data/local/tmp/store-theme >/dev/null 2>&1
  rm -rf "$tmp"
  app "rm -rf files/themes/$name && mkdir -p files/themes && cp -r /data/local/tmp/store-theme files/themes/$name"
  adb shell rm -rf /data/local/tmp/store-theme
}

# App use as the keys given: each a second apart, the first the latest.
usage() {
  local xml now i keys=("$@")
  xml=$(mktemp)
  now=$(($(date +%s) * 1000))
  {
    echo "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
    echo "<map>"
    for i in "${!keys[@]}"; do echo "    <string name=\"${keys[$i]}\">1.0,$((now - i * 1000))</string>"; done
    echo "</map>"
  } >"$xml"
  adb push "$xml" /data/local/tmp/store-usage.xml >/dev/null
  rm "$xml"
  app "cp /data/local/tmp/store-usage.xml shared_prefs/app_usage.xml"
}

# Taps the centre of the first node whose text or description is $1, from a uiautomator dump.
tap_text() {
  local xml bounds
  xml=$(adb exec-out uiautomator dump /dev/tty 2>/dev/null)
  bounds=$(grep -o "<node[^>]*\(text\|content-desc\)=\"$1\"[^>]*>" <<<"$xml" | head -1 | grep -o 'bounds="[^"]*"' || true)
  [ -n "$bounds" ] || { echo "no \"$1\" on screen" >&2; return 1; }
  read -r x1 y1 x2 y2 <<<"$(tr -c '0-9' ' ' <<<"$bounds")"
  adb shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
}

w=${SIZE%x*}
h=${SIZE#*x}
swipe_right() { adb shell input swipe $((w / 5)) $((h / 2)) $((w * 4 / 5)) $((h / 2)) 300; }

long_press_home() { adb shell input swipe $((w * 3 / 4)) $((h / 2)) $((w * 3 / 4)) $((h / 2)) 900; sleep 2; }

# The manga face, left of home, with a notification in its caption.
shot_manga() {
  local note
  note=$(cat "$L10N/notification-$L.txt")
  adb shell cmd notification post -t "'${note%%|*}'" store-1 "'${note#*|}'" >/dev/null
  swipe_right
  sleep 6
}
# Voice: a conversation from earlier, resumed, the orb listening for the next thing to say.
shot_voice() {
  local f
  app "[ -e $SHIMS/.claude-store ] || mv $SHIMS/claude $SHIMS/.claude-store"
  f=$(mktemp)
  printf '#!/bin/sh\nexec cat >/dev/null\n' >"$f"
  put "$f" "$SHIMS/claude"
  app "chmod 755 $SHIMS/claude && rm -rf files/voice && mkdir files/voice"
  printf '{"agent":"claude","approveAll":false,"typing":false}' >"$f"
  put "$f" files/voice/settings.json
  printf '{"id":"store-shot","at":%s}' "$(($(date +%s) * 1000))" >"$f"
  put "$f" files/voice/previous-session-claude.json
  rm "$f"
  put "$L10N/voice-$L.json" files/voice/previous-transcript-claude.json
  # The prompt bar's voice button, at its right end.
  adb shell input tap $((w - 75)) $((h - 105))
  # A fresh Voice can take a while to know the agents, and offer to resume.
  for _ in $(seq 15); do tap_text "$(str voice_resume)" 2>/dev/null && break; sleep 1; done
  sleep 3
  # The orb: listening, lit.
  adb shell input tap $((w / 2)) $((h * 32 / 100))
  sleep 2
}
# The store's games: colourful previews (fun has sip, which Simon doesn't want shown).
shot_store() {
  long_press_home
  tap_text "$(str home_browse_store)"
  sleep 4
  # The category row scrolls sideways; games is near its end.
  for _ in 1 2 3; do
    adb shell input swipe $((w * 5 / 6)) $((h * 28 / 100)) $((w / 10)) $((h * 28 / 100)) 400
    sleep 2
    tap_text "games [0-9]*" 2>/dev/null && break
  done
  sleep 10
}
# A terminal with fastfetch and Omarchy's logo, without the host and hardware lines that give the emulator away.
shot_linux() {
  local ff
  ff=$(mktemp)
  printf '%s\n' clear 'fastfetch -c none --logo-type file --logo ~/.config/omarchy/branding/about.txt --logo-color-1 green --logo-position top -s os:uptime:packages:shell:terminal:memory:break:colors' 'exec bash' >"$ff"
  adb push "$ff" /data/local/tmp/store-ff.sh >/dev/null
  rm "$ff"
  app "cp /data/local/tmp/store-ff.sh files/widgets/.store-ff.sh"
  adb shell am start -n $PKG/.MainActivity --es exec "'foot bash /root/.config/omalauncher/widgets/.store-ff.sh'" >/dev/null
  sleep 10
}
shot_style() {
  long_press_home
  tap_text "$(str home_settings)"
  sleep 2
  tap_text "$(str settings_style)"
  sleep 4
}

if [ "${WTTR:-}" != live ]; then
  python3 -I "$HERE/../store/screenshots/wttr-mock.py" $WTTR_PORT &
  mock=$!
fi
for r in "${REPO_THEMES[@]}"; do repo_theme "$r"; done

for L in "${LANGS[@]}"; do
OUT="$RAW/$L"
mkdir -p "$OUT"
adb shell am force-stop $PKG
language "$L"
n=0
for scene in "${SCENES[@]}"; do
  n=$((n + 1))
  read -r shot theme widgets <<<"$scene"
  if [ $# -gt 0 ] && [[ " $* " != *" $shot "* ]]; then continue; fi
  adb shell am force-stop $PKG
  # A bare home: no widgets, no layout, no pinned apps, and RECENT for recent apps.
  app "rm -f files/widgets/*.owl shared_prefs/widget_grid.xml shared_prefs/shortcuts.xml"
  usage "${RECENT[@]}"
  adb shell am start -n $PKG/.MainActivity --es theme "$theme" >/dev/null
  sleep 3
  # One at a time, each once the one before has its spot: the launcher places tiles as they load, so adding them
  # all at once mixes up the order.
  for item in $widgets; do
    if [[ $item == app:* ]]; then
      a=${item#app:}
      install "apps/simonschubert/$a/app.owl" "market-app-simonschubert-$a.owl"
      continue
    elif [[ $item == face:* ]]; then
      # A face keeps no cell; it takes the empty page left of page 1.
      f=${item#face:}
      install "faces/simonschubert/$f/face.owl" "$f.owl"
      continue
    else
      [[ $item == */* ]] || item=simonschubert/$item
      install "widgets/$item/widget.owl" "${item#*/}.owl"
    fi
    wait_for 20 "run-as $PKG grep -q ${item#*/}.owl $DATA/shared_prefs/widget_grid.xml" || echo "$shot: $item got no spot" >&2
  done
  # Theme, wallpaper and the widgets' first data.
  sleep "${WAIT:-12}"
  # Away from home and back, so the recent apps strip starts at its first app: it keeps the first icon it showed
  # in view while the rest load in, and Android's apps load before Linux's.
  adb shell am start -a android.settings.SETTINGS >/dev/null
  sleep 2
  adb shell input keyevent KEYCODE_HOME
  sleep 2
  if declare -F "shot_$shot" >/dev/null; then "shot_$shot"; fi
  sleep 2
  adb exec-out screencap -p >"$OUT/$(printf %d $n)-$shot.png"
  echo "$OUT/$n-$shot.png"
done
done
