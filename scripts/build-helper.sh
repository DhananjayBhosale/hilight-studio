#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
API_JAR="$SDK/platforms/android-37.1/android.jar"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
JAVAC="${JAVAC:-$(command -v javac || echo "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/javac")}"

[ -f "$API_JAR" ] || { echo "missing $API_JAR (need the Android 17 / API 37 platform)"; exit 1; }

OUT="$ROOT/helper/build"
rm -rf "$OUT" && mkdir -p "$OUT/classes"

"$JAVAC" --release 17 -nowarn -classpath "$API_JAR" -d "$OUT/classes" \
  "$ROOT"/core/src/com/hilight/core/*.java

"$BT/d8" --lib "$API_JAR" --output "$OUT" "$OUT"/classes/com/hilight/core/*.class 2>&1 \
  | grep -v "API level" || true

mv "$OUT/classes.dex" "$OUT/hilight-helper.dex"
echo "built $OUT/hilight-helper.dex"
