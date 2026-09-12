#!/bin/sh
# Stage NDK-built payloads into the app's assets.
# Requires Android NDK r26+ (same requirement as upstream).
#
# Usage:
#   cd exploit && make preload          # produces build/bin/preload.so + build/embed/su_daemon_aarch64_pie
#   ./stage-assets.sh                   # copies them into app/src/main/assets/
#
# After staging, build the app:
#   ./gradlew :app:assembleDebug
set -eu
ROOT="$(cd "$(dirname "$0")" && pwd)"
ASSETS="$ROOT/app/src/main/assets"

PRELOAD="$ROOT/exploit/build/bin/preload.so"
HELPER="$ROOT/exploit/build/embed/su_daemon_aarch64_pie"

missing=0
if [ ! -f "$PRELOAD" ]; then
  echo "missing: $PRELOAD (run: cd exploit && make preload)" >&2
  missing=1
fi
if [ ! -f "$HELPER" ]; then
  echo "missing: $HELPER (run: cd exploit && make preload)" >&2
  missing=1
fi
if [ "$missing" -ne 0 ]; then
  echo "ksud is already vendored at $ASSETS/ksud; only the two NDK outputs are missing." >&2
  exit 1
fi

mkdir -p "$ASSETS"
cp -f "$PRELOAD" "$ASSETS/preload.so"
cp -f "$HELPER" "$ASSETS/su_daemon"
chmod 644 "$ASSETS/preload.so"
echo "staged:"
ls -l "$ASSETS/preload.so" "$ASSETS/su_daemon" "$ASSETS/ksud"
