#!/bin/bash
# omalauncher: checks widgets and apps the way the launcher sees them (see
# WidgetEngine.kt). Waits until the launcher has loaded each file as it is on
# disk, then prints its errors and where the picture of its tile is.
set -u
dir="$HOME/.config/omalauncher/widgets"

if [ $# -eq 0 ] || [ "$1" = -h ] || [ "$1" = --help ]; then
  cat >&2 <<EOF
usage: owl-check <file.owl>...

Waits for the launcher to load each file as saved, then prints its errors
and the path of a PNG of it, drawn with live data: a widget's tile at its
size on the home screen (and its screen { }, if it has one), an app's view
at the phone's size. Exits 1 if a file doesn't compile or reports problems.
EOF
  exit 2
fi

# The rest of the first "<key> ..." line of check file $1.
field() { sed -n "s/^$2 //p" "$1" 2>/dev/null | head -1; }

# Runs $1 every tenth of a second until it succeeds, at most $2 times.
wait_for() {
  local i
  for ((i = 0; i < $2; i++)); do
    eval "$1" && return 0
    sleep 0.1
  done
  return 1
}

status=0
for arg in "$@"; do
  f=$(realpath -- "$arg" 2>/dev/null)
  if [ -z "$f" ] || [ ! -f "$f" ]; then
    echo "$arg: no such file"
    status=1
    continue
  fi
  name=$(basename -- "$f")
  if [ "$(dirname -- "$f")" != "$(realpath -- "$dir")" ] || [[ $name != *.owl ]] || [[ $name == .* ]]; then
    echo "$arg: the launcher only loads *.owl files directly in $dir"
    status=1
    continue
  fi
  hash=$(sha256sum <"$f" | cut -d' ' -f1)
  check="$dir/.check/$name.txt"

  # A file saved a moment ago takes the launcher about a second to pick up.
  if ! wait_for '[ "$(field "$check" sha256)" = "$hash" ]' 100; then
    echo "$name: the launcher hasn't loaded this version yet; run owl-check again"
    status=1
    continue
  fi
  if [ "$(field "$check" result)" != ok ]; then
    echo "$name: doesn't compile"
    sed 's/^/  /' "$dir/.errors/$name.txt" 2>/dev/null
    status=1
    continue
  fi
  echo "$name: compiles"

  # The launcher renders files that change while it runs; for anything else, ask.
  has_preview() { grep -qE "^preview(-skipped)? $hash( |\$)" "$check"; }
  has_preview || echo "$hash" >"$dir/.check/$name.want"
  if wait_for has_preview 250; then
    if line=$(grep -E "^preview $hash " "$check"); then
      read -r _ _ cells px <<<"$line"
      echo "  preview: $dir/.preview/$name.png ($cells cells, $px px, with live data); look at it to check the layout"
    else
      echo "  no preview: $(grep -E "^preview-skipped $hash " "$check" | cut -d' ' -f3-)"
    fi
    # A widget's screen { }, or an app's whole view.
    if line=$(grep -E "^screen $hash " "$check"); then
      read -r _ _ px <<<"$line"
      echo "  screen: $dir/.preview/$name.screen.png ($px px, opened full screen, with live data); look at it to check the layout"
    fi
  else
    echo "  no preview yet (the launcher may be busy); run owl-check again for it"
  fi

  # Problems while running: values that failed to evaluate, commands that failed.
  if [ -s "$dir/.errors/$name.txt" ]; then
    sed 's/^/  /' "$dir/.errors/$name.txt"
    grep -qv '^runtime: warning:' "$dir/.errors/$name.txt" && status=1
  fi
done
exit $status
