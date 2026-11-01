#!/usr/bin/env bash
# Verifies a recovered archive; no production APK build and no device testing.
set -euo pipefail
cd "$(dirname "$0")/.."
command -v unzip >/dev/null || { echo "Missing unzip" >&2; exit 2; }
command -v kotlinc >/dev/null || { echo "Missing kotlinc" >&2; exit 2; }
command -v java >/dev/null || { echo "Missing Java" >&2; exit 2; }
archive="imports/KineticTorch_v1_3_1_AndroidStudio_Project.zip"
test -f "$archive" || { echo "Missing $archive" >&2; exit 2; }
unzip -tqq "$archive"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
unzip -q "$archive" -d "$tmp"
src="$tmp/KineticTorch/app/src/main/java/com/npfs/stars/kinetictorch/ChopDetector.kt"
test -f "$src" || { echo "Missing ChopDetector source" >&2; exit 2; }
kotlinc "$src" tools/OfflineDetectorSmoke.kt -include-runtime -d "$tmp/smoke.jar"
java -jar "$tmp/smoke.jar"
echo "Offline gesture unit smoke complete; Android build and double-chop OFF remain unverified."
