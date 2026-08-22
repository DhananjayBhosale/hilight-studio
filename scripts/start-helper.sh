#!/usr/bin/env bash
set -euo pipefail

PKG="com.hilight.studio"
ADB="${ADB:-adb}"

APK="$($ADB shell pm path $PKG | head -1 | tr -d '\r' | cut -d: -f2)"
[ -n "$APK" ] || { echo "$PKG is not installed — run ./gradlew :app:installDebug first"; exit 1; }

$ADB shell "pkill -f com.hilight.core.AdbHelper" >/dev/null 2>&1 || true
$ADB shell "nohup sh -c 'CLASSPATH=$APK exec app_process / com.hilight.core.AdbHelper' \
  >/data/local/tmp/hilight.log 2>&1 &"

sleep 2
$ADB shell "tail -3 /data/local/tmp/hilight.log" || true
$ADB shell "pgrep -f com.hilight.core.AdbHelper" >/dev/null \
  && echo "renderer running" \
  || { echo "failed to start; see /data/local/tmp/hilight.log"; exit 1; }
