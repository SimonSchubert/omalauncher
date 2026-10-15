#!/usr/bin/env bash
# Renders art/icons/*.svg to the 256px PNGs the launcher installs into the guest
# (the drawer only loads PNG icons), and the agent logos and Files' and the editor's icons again as
# resources: their activities' icons, which Recents shows. Last, the Play Store icon from
# art/app-icon.svg. Needs rsvg-convert and ImageMagick (brew install librsvg imagemagick).
set -euo pipefail
cd "$(dirname "$0")/.."
out=app/src/main/assets/icons
mkdir -p "$out"
for svg in art/icons/*.svg; do
    rsvg-convert -w 256 -h 256 "$svg" -o "$out/$(basename "$svg" .svg).png"
done
res=app/src/main/res/drawable-nodpi
mkdir -p "$res"
for svg in art/icons/agent-*.svg art/icons/files.svg art/icons/editor.svg; do
    name=$(basename "$svg" .svg)
    rsvg-convert -w 192 -h 192 "$svg" -o "$res/${name//-/_}.png"
done
rsvg-convert -w 512 -h 512 art/app-icon.svg | magick - -background '#16161e' -flatten -alpha off \
    fastlane/metadata/android/en-US/images/icon.png
