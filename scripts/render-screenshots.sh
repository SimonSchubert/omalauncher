#!/usr/bin/env bash
# Renders store/screenshots/frame.html (captions + raw/<language>/ captures) into the Play listing's phone
# screenshots, 1080x1920 each, for every listing language (or the Play locales given).
#
#   scripts/render-screenshots.sh [play locale...]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
COUNT=8
# Play listing locale → the language its captures and captions are in.
declare -A LANG_OF=([en-US]=en [id]=id [es-419]=es [es-ES]=es [pt-BR]=pt [de-DE]=de [zh-CN]=zh)
LOCALES=("$@")
[ ${#LOCALES[@]} -gt 0 ] || LOCALES=(en-US id es-419 es-ES pt-BR de-DE zh-CN)

for locale in "${LOCALES[@]}"; do
  l=${LANG_OF[$locale]}
  out="$ROOT/fastlane/metadata/android/$locale/images/phoneScreenshots"
  mkdir -p "$out"
  rm -f "$out"/*.png
  for i in $(seq 1 $COUNT); do
    file=$(printf '%s/%02d.png' "$out" "$i")
    "$CHROME" --headless=new --hide-scrollbars --force-device-scale-factor=1 \
      --window-size=1080,1920 --virtual-time-budget=8000 \
      --screenshot="$file" "file://$ROOT/store/screenshots/frame.html?s=$i&l=$l" 2>/dev/null
    echo "$file"
  done
done
