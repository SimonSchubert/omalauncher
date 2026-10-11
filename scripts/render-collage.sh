#!/usr/bin/env bash
# Renders store/collage/collage.html (the captures from scripts/collage-captures.sh) into the
# collage for the README and X.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"

"$CHROME" --headless=new --hide-scrollbars --force-device-scale-factor=1 \
  --window-size=1808,1350 --virtual-time-budget=4000 \
  --screenshot="$ROOT/art/collage.png" "file://$ROOT/store/collage/collage.html" 2>/dev/null
echo "$ROOT/art/collage.png"
