#!/usr/bin/env bash
# Installs a release APK over the launcher on a phone (data kept) and cold-starts it many times,
# in several languages, failing on any crash, ANR or start that leaves no process.
#
# For before an upload to Play. 1.9.0 and 1.9.1 crashed at each start, and the test before their
# release missed it: it ran a debug build, in a language Parakeet doesn't know, with the Voice
# settings at their defaults. This script fails on 1.9.0 on an emulator as on a phone, but only
# with a release build on an install that has Linux and a coding agent Voice can talk to. So run
# it on a lived-in install: Linux, an agent, and settings that aren't the defaults (a Voice
# speed, a picked theme, widgets). The APK must be signed with the key of the build that's
# there; nothing is uninstalled.
#
#   scripts/release-smoke.sh <adb serial> [apk, default the foss release build] [starts per language, default 10]
set -euo pipefail

serial=${1:?usage: release-smoke.sh <adb serial> [apk] [starts per language]}
apk=${2:-app/build/outputs/apk/foss/release/app-foss-release.apk}
starts=${3:-10}
pkg=dev.omalauncher
adb=(adb -s "$serial")

before=$("${adb[@]}" shell cmd locale get-app-locales $pkg | sed -n 's/.*\[\(.*\)\].*/\1/p')
restore() { "${adb[@]}" shell cmd locale set-app-locales $pkg --locales "'$before'" >/dev/null; }
trap restore EXIT

"${adb[@]}" install -r "$apk" | tail -1
"${adb[@]}" shell dumpsys package $pkg | grep -m1 versionName
"${adb[@]}" logcat -c
dead=0
# The phone's own language, then ones Parakeet knows (pt, de) and ones it doesn't (ja, tr).
for locale in "$before" pt-BR de-DE ja-JP tr-TR; do
  "${adb[@]}" shell cmd locale set-app-locales $pkg --locales "'$locale'" >/dev/null
  for _ in $(seq "$starts"); do
    "${adb[@]}" shell "am force-stop $pkg; am start -n $pkg/.MainActivity" >/dev/null 2>&1
    sleep 5
    [ -n "$("${adb[@]}" shell pidof $pkg | tr -d '\r')" ] || dead=$((dead + 1))
  done
  echo "${locale:-phone language}: $starts starts"
done

fatal=$("${adb[@]}" logcat -d -b crash | grep -c "FATAL EXCEPTION" || true)
anr=$("${adb[@]}" logcat -d | grep -c "ANR in $pkg" || true)
echo "crashes: $fatal, ANRs: $anr, starts without a process: $dead"
if [ "$fatal" != 0 ] || [ "$anr" != 0 ] || [ "$dead" != 0 ]; then
  "${adb[@]}" logcat -d -b crash | grep -A12 "FATAL EXCEPTION" | head -60
  exit 1
fi
