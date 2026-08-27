#!/usr/bin/env bash
# Starts the ADB-hosted renderer on the connected device.
#
# Runs the classes out of the installed APK, so there is nothing to push. Must be re-run after every
# reboot: the shell UID is only reachable while adb has a session.
set -euo pipefail

PKG="com.hilight.studio"
ADB="${ADB:-adb}"

APK="$($ADB shell pm path $PKG | head -1 | tr -d '\r' | cut -d: -f2)"
[ -n "$APK" ] || { echo "$PKG is not installed — run ./gradlew :app:installDebug first"; exit 1; }

INSTANCE="adb-$("$ADB" shell cat /proc/sys/kernel/random/uuid | tr -d '\r\n')"
[ "$INSTANCE" != "adb-" ] || { echo "could not create a renderer instance ID"; exit 1; }

# Stop only the exact HiLight helper entry point or Shizuku service process, then wait for every
# matched PID to exit. An empty/unreadable cmdline belonging to app_process is unresolved and fails
# closed. Reset and launch share one phone-shell invocation, so nothing can enter between the final
# survivor check and the new helper. The new helper's explicit instance ID is mandatory.
PHONE_RESET='live=1; i=0; while [ "$i" -lt 65 ] && [ -n "$live" ]; do live=""; for d in /proc/[0-9]*; do p=${d#/proc/}; c=$(tr "\000" " " < "$d/cmdline" 2>/dev/null); if [ -z "$c" ]; then e=$(readlink "$d/exe" 2>/dev/null); x=${e##*/}; if [ "$x" = app_process ] || [ "$x" = app_process32 ] || [ "$x" = app_process64 ]; then exit 1; fi; continue; fi; set -- $c; x=${1##*/}; if { { [ "$x" = app_process ] || [ "$x" = app_process32 ] || [ "$x" = app_process64 ]; } && [ "${2:-x}" = / ] && [ "${3:-x}" = com.hilight.core.AdbHelper ]; } || [ "${1:-x}" = com.hilight.studio:hilight ]; then kill -TERM "$p" 2>/dev/null || exit 1; live=1; fi; done; [ -n "$live" ] && sleep 0.1; i=$((i + 1)); done; [ -z "$live" ] || exit 1'
"$ADB" shell "$PHONE_RESET; CLASSPATH='$APK' nohup app_process / com.hilight.core.AdbHelper --owner adb --instance '$INSTANCE' --exclusive >/data/local/tmp/hilight.log 2>&1 &"

echo "renderer launched; synchronous startup cleanup can take about 2-3 seconds"
sleep 4
$ADB shell "tail -3 /data/local/tmp/hilight.log" || true
"$ADB" shell "pgrep -f 'com.hilight.core.AdbHelper.*--instance $INSTANCE'" >/dev/null \
  && echo "renderer running" \
  || { echo "failed to start; see /data/local/tmp/hilight.log"; exit 1; }
