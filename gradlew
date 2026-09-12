#!/bin/sh
# Minimal Gradle launcher: uses the wrapper jar when present, else system gradle.
# Android Studio will generate the full wrapper on first open (it only needs
# gradle/wrapper/gradle-wrapper.properties, which is checked in).
APP_BASE="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_JAR="$APP_BASE/gradle/wrapper/gradle-wrapper.jar"
if [ -f "$WRAPPER_JAR" ]; then
  exec java -jar "$WRAPPER_JAR" "$@"
elif command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
else
  echo "No gradle-wrapper.jar and no system gradle. Install Gradle 8.9+ or open in Android Studio." >&2
  exit 1
fi
