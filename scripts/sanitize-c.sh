#!/bin/sh
# Runs the tests that build the app's C for this machine (HostNative) again, with AddressSanitizer and
# UndefinedBehaviorSanitizer: a read past an array, a signed overflow or a float that doesn't fit an int then fails
# the test, where the plain build may happen to give the right answer. Needs gcc with libasan; the terminal's tests
# need scripts/fetch-ghostty-vt.sh as the plain run does. CI runs it after the tests.
set -eu
cd "$(dirname "$0")/.."

asan=$(gcc -print-file-name=libasan.so)
[ -f "$asan" ] || { echo "no libasan for gcc" >&2; exit 1; }

# HostNative runs `cc`: this one builds with the sanitizers, and stops at the first undefined behaviour.
bin=$(mktemp -d)
trap 'rm -rf "$bin"' EXIT
cat > "$bin/cc" <<'EOF'
#!/bin/sh
exec gcc -g -fno-omit-frame-pointer -fsanitize=address,undefined,float-cast-overflow -fno-sanitize-recover=all "$@"
EOF
chmod +x "$bin/cc"

# Every test class that loads C through HostNative.
tests=$(grep -rl 'HostNative\.load' app/src/test --include='*.kt' | sed 's|.*/kotlin/||; s|\.kt$||; s|/|.|g' | sed 's/^/--tests /')

# The JVM loads the library, so the sanitizer's runtime goes in first. The JVM's own signals and memory aren't the C's:
# no leak report, and SIGSEGV stays the JVM's. A fresh daemon, so the test JVMs get this environment.
PATH="$bin:$PATH" LD_PRELOAD="$asan" ASAN_OPTIONS=detect_leaks=0:handle_segv=0:allow_user_segv_handler=1 \
    UBSAN_OPTIONS=print_stacktrace=1 \
    ./gradlew --no-daemon :app:testFossDebugUnitTest --rerun $tests
