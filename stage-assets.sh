#!/bin/sh
# Stage NDK-built payloads into the app's assets.
# Requires Android NDK r26+ (same requirement as upstream).
#
# Usage:
#   cd exploit && make preload          # produces build/bin/preload.so + build/embed/su_daemon_aarch64_pie
#   ./stage-assets.sh                   # copies them into app/src/main/assets/
#
# The ksud daemon is also staged here: if exploit/build/bin/ksud exists (i.e. the
# workflow freshly rebuilt it from polygraphene/KernelSU), that binary is used;
# otherwise the vendored prebuilt at app/src/main/assets/ksud is kept as-is.
#
# After staging, build the app:
#   ./gradlew :app:assembleDebug
set -eu
ROOT="$(cd "$(dirname "$0")" && pwd)"
ASSETS="$ROOT/app/src/main/assets"

PRELOAD="$ROOT/exploit/build/bin/preload.so"
HELPER="$ROOT/exploit/build/embed/su_daemon_aarch64_pie"
KSUD_NEW="$ROOT/exploit/build/bin/ksud"

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
  echo "the two NDK payloads are absent; ksud staging is skipped" >&2
  exit 1
fi

mkdir -p "$ASSETS"
cp -f "$PRELOAD" "$ASSETS/preload.so"
cp -f "$HELPER" "$ASSETS/su_daemon"
chmod 644 "$ASSETS/preload.so" "$ASSETS/su_daemon"

if [ -f "$KSUD_NEW" ]; then
  cp -f "$KSUD_NEW" "$ASSETS/ksud"
  chmod 644 "$ASSETS/ksud"
  echo "ksud: staged fresh build from $KSUD_NEW"
else
  echo "ksud: keeping vendored prebuilt at $ASSETS/ksud"
fi
echo "staged:"
ls -l "$ASSETS/preload.so" "$ASSETS/su_daemon" "$ASSETS/ksud"
