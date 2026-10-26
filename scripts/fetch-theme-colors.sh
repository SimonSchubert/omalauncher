#!/usr/bin/env bash
# Bundles each Omarchy theme's colors.toml, so the first-run screen can offer themes
# before anything is downloaded, and each theme's first background's name, so first run
# can fetch just that one image and leave the full themes to the background.
# Uses the commit the app pins (ThemeStore.OMARCHY_REF): the names must match its files.
set -euo pipefail
cd "$(dirname "$0")/.."
ref=$(sed -n 's/.*const val OMARCHY_REF = "\([0-9a-f]*\)".*/\1/p' app/src/main/kotlin/dev/omadroid/theme/ThemeStore.kt)
[ -n "$ref" ] || { echo "OMARCHY_REF not found" >&2; exit 1; }
out=app/src/main/assets/themes
backgrounds=app/src/main/assets/theme-backgrounds.txt
rm -rf "$out" && mkdir -p "$out"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
curl -fsSL "https://codeload.github.com/omacom/omarchy/tar.gz/$ref" |
  tar xz -C "$tmp" --include='*/themes/*/colors.toml' --include='*/themes/*/backgrounds/*'
: > "$backgrounds"
for f in "$tmp"/*/themes/*/colors.toml; do
  dir=$(dirname "$f")
  name=$(basename "$dir")
  cp "$f" "$out/$name.toml"
  # The launcher shows a theme's backgrounds sorted by name and applies the first one.
  first=$(ls "$dir/backgrounds" 2>/dev/null | grep -iE '\.(jpe?g|png|webp)$' | LC_ALL=C sort | head -1 || true)
  [ -n "$first" ] && echo "$name $first" >> "$backgrounds"
done
ls "$out"
cat "$backgrounds"
