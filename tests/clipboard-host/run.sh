#!/bin/bash
# Compiles the real Clipboard.kt and Wire.kt, with host substitutes only for Android and the transport.
set -euo pipefail
repo=$(cd -- "$(dirname -- "$0")/../.." && pwd)
source_file=${1:-"$repo/app/src/main/kotlin/dev/omalauncher/wayland/Clipboard.kt"}
output_dir=${2:-"$repo/build/clipboard-host"}
java_bin=${CLIPBOARD_TEST_JAVA:-}
if [ -z "$java_bin" ]; then java_bin=$(command -v java || true); fi
if [ -z "$java_bin" ]; then
  for candidate in "$repo"/.test-tools/*-jre/bin/java; do
    if [ -x "$candidate" ]; then java_bin=$candidate; break; fi
  done
fi
if [ -z "$java_bin" ] || [ ! -x "$java_bin" ]; then
  echo 'Java 17+ is required; set CLIPBOARD_TEST_JAVA or put java on PATH.' >&2
  exit 2
fi
compiler_bin=${CLIPBOARD_TEST_KOTLINC:-}
if [ -z "$compiler_bin" ]; then compiler_bin=$(command -v kotlinc || true); fi
if [ -z "$compiler_bin" ] && [ -x "$repo/.test-tools/kotlinc/bin/kotlinc" ]; then
  compiler_bin="$repo/.test-tools/kotlinc/bin/kotlinc"
fi
if [ -z "$compiler_bin" ] || [ ! -x "$compiler_bin" ]; then
  echo 'Kotlin 2.2.21+ is required; set CLIPBOARD_TEST_KOTLINC or put kotlinc on PATH.' >&2
  exit 2
fi
mkdir -p "$output_dir"
if [[ "$source_file" != *.kt ]]; then
  cp -- "$source_file" "$output_dir/Clipboard.kt"
  source_file="$output_dir/Clipboard.kt"
fi
mapfile -t stubs < <(find "$repo/tests/clipboard-host/stubs" -name '*.kt' -print)
fake_clipboard="$repo/tests/clipboard-host/FakeClipboard.kt"
if ! grep -q 'data class Stamp' "$source_file"; then
  fake_clipboard="$repo/tests/clipboard-host/BaselineFakeClipboard.kt"
fi
JAVACMD="$java_bin" "$compiler_bin" -J-Xmx384m \
  -nowarn -jvm-target 17 -include-runtime -d "$output_dir/tests.jar" \
  "$source_file" "$repo/app/src/main/kotlin/dev/omalauncher/wayland/Wire.kt" \
  "$repo/tests/clipboard-host/ClipboardRegression.kt" "$fake_clipboard" "${stubs[@]}"
"$java_bin" -Xmx256m -jar "$output_dir/tests.jar"
