#!/usr/bin/env bash
# Builds and verifies the signed Play APK and uploadable Android App Bundle.
set -euo pipefail
umask 077

SCRIPT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -n "${HILIGHT_PROJECT_DIR:-}" ]; then
  ROOT="$(cd "$HILIGHT_PROJECT_DIR" && pwd)"
elif [ -x "$PWD/gradlew" ] && [ -f "$PWD/app/build.gradle.kts" ]; then
  ROOT="$PWD"
else
  ROOT="$SCRIPT_ROOT"
fi

KEYSTORE="${HILIGHT_STORE_FILE:-$HOME/Library/Application Support/HiLight Studio/signing/hilight-studio-release.jks}"
KEY_ALIAS="${HILIGHT_KEY_ALIAS:-hilight-studio}"
EXPECTED_CERT="15c1a4b5af54c3833e8d94582bddd985631cd007ca3f86d314d4be0bd5d9d9de"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
OUTPUT_ROOT="${HILIGHT_PLAY_OUTPUT_DIR:-$HOME/Library/Application Support/HiLight Studio/releases/play}"
DEVICE=""

usage() {
  echo "Usage: $(basename "$0") [--device SERIAL]"
  echo "Builds and verifies a signed Play APK and AAB. --device also installs the APK."
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --device)
      [ "$#" -ge 2 ] || { usage >&2; exit 2; }
      DEVICE="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      exit 2
      ;;
  esac
done

[ -f "$KEYSTORE" ] || { echo "missing release keystore: $KEYSTORE" >&2; exit 1; }
[ -x "$ROOT/gradlew" ] || { echo "missing Gradle wrapper" >&2; exit 1; }
[ -d "$SDK" ] || { echo "missing Android SDK: $SDK" >&2; exit 1; }

STORE_ACCOUNT="KEY_STORE_PASSWORD__${KEYSTORE}"
KEY_ACCOUNT="KEY_PASSWORD__${KEYSTORE}__${KEY_ALIAS}"

cleanup() {
  unset STORE_PASSWORD KEY_PASSWORD
}
trap cleanup EXIT HUP INT TERM

STORE_PASSWORD="${HILIGHT_STORE_PASSWORD:-}"
KEY_PASSWORD="${HILIGHT_KEY_PASSWORD:-}"
if [ -z "$STORE_PASSWORD" ] || [ -z "$KEY_PASSWORD" ]; then
  [ "$(uname -s)" = "Darwin" ] \
    || { echo "signing passwords are missing and macOS Keychain is unavailable" >&2; exit 1; }
  command -v security >/dev/null || { echo "missing macOS security tool" >&2; exit 1; }
  STORE_PASSWORD="$(security find-generic-password -a "$STORE_ACCOUNT" -w)" \
    || { echo "keystore password is not available in macOS Keychain" >&2; exit 1; }
  KEY_PASSWORD="$(security find-generic-password -a "$KEY_ACCOUNT" -w)" \
    || { echo "key password is not available in macOS Keychain" >&2; exit 1; }
fi

cd "$ROOT"
ANDROID_HOME="$SDK" \
HILIGHT_STORE_FILE="$KEYSTORE" \
HILIGHT_STORE_PASSWORD="$STORE_PASSWORD" \
HILIGHT_KEY_ALIAS="$KEY_ALIAS" \
HILIGHT_KEY_PASSWORD="$KEY_PASSWORD" \
  ./gradlew --no-daemon :app:assemblePlayRelease :app:bundlePlayRelease

cleanup

APK="$ROOT/app/build/outputs/apk/play/release/app-play-release.apk"
BUNDLE="$ROOT/app/build/outputs/bundle/playRelease/app-play-release.aab"
[ -f "$APK" ] || { echo "signed Play APK was not produced" >&2; exit 1; }
[ -f "$BUNDLE" ] || { echo "signed Play App Bundle was not produced" >&2; exit 1; }

APKSIGNER="$(find "$SDK/build-tools" -maxdepth 2 -type f -name apksigner -print | sort -V | tail -1)"
AAPT2="$(find "$SDK/build-tools" -maxdepth 2 -type f -name aapt2 -print | sort -V | tail -1)"
APKANALYZER="$SDK/cmdline-tools/latest/bin/apkanalyzer"
[ -x "$APKSIGNER" ] || { echo "missing apksigner" >&2; exit 1; }
[ -x "$AAPT2" ] || { echo "missing aapt2" >&2; exit 1; }
[ -x "$APKANALYZER" ] || { echo "missing apkanalyzer" >&2; exit 1; }
command -v keytool >/dev/null || { echo "missing keytool" >&2; exit 1; }
command -v jarsigner >/dev/null || { echo "missing jarsigner" >&2; exit 1; }

APK_CERT="$($APKSIGNER verify --print-certs "$APK" \
  | awk -F': ' '/Signer #1 certificate SHA-256 digest:/{print tolower($2); exit}')"
[ "$APK_CERT" = "$EXPECTED_CERT" ] || {
  echo "APK certificate mismatch; refusing artifacts" >&2
  exit 1
}

jarsigner -verify "$BUNDLE" >/dev/null 2>&1 \
  || { echo "App Bundle signature verification failed" >&2; exit 1; }
BUNDLE_CERT="$(keytool -printcert -jarfile "$BUNDLE" 2>/dev/null \
  | awk -F': ' '/SHA256:/{print $2; exit}' \
  | tr -d ':[:space:]' \
  | tr '[:upper:]' '[:lower:]')"
[ "$BUNDLE_CERT" = "$EXPECTED_CERT" ] || {
  echo "App Bundle certificate mismatch; refusing artifacts" >&2
  exit 1
}

VERSION="$($AAPT2 dump badging "$APK" \
  | sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p" \
  | head -1)"
VERSION_CODE="$($AAPT2 dump badging "$APK" \
  | sed -n "s/^package:.*versionCode='\([^']*\)'.*/\1/p" \
  | head -1)"
[ -n "$VERSION" ] && [ -n "$VERSION_CODE" ] \
  || { echo "could not read Play version" >&2; exit 1; }

PACKAGE_NAME="$($AAPT2 dump badging "$APK" \
  | sed -n "s/^package: name='\([^']*\)'.*/\1/p" \
  | head -1)"
[ "$PACKAGE_NAME" = "com.highlight.studio" ] \
  || { echo "unexpected Play package: $PACKAGE_NAME" >&2; exit 1; }

PERMISSIONS="$($APKANALYZER manifest permissions "$APK")"
if grep -Eq 'android.permission.(INTERNET|QUERY_ALL_PACKAGES)' <<<"$PERMISSIONS"; then
  echo "Play APK contains a forbidden distribution permission" >&2
  exit 1
fi

PACKAGES="$($APKANALYZER dex packages "$APK")"
grep -q 'com.hilight.core.AdbHelper' <<<"$PACKAGES" \
  || { echo "Play APK is missing AdbHelper" >&2; exit 1; }
grep -q 'com.hilight.studio.HiLightUserService' <<<"$PACKAGES" \
  || { echo "Play APK is missing HiLightUserService" >&2; exit 1; }
if grep -q 'GitHubUpdateChecker' <<<"$PACKAGES"; then
  echo "Play APK still contains GitHubUpdateChecker" >&2
  exit 1
fi
if unzip -p "$APK" classes.dex | strings \
    | grep -Eq 'api\.github\.com/.*/releases|github\.com/.*/releases/tag'; then
  echo "Play APK still contains a GitHub release URL" >&2
  exit 1
fi

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DEST_DIR="$OUTPUT_ROOT/v$VERSION-code$VERSION_CODE"
APK_DEST="$DEST_DIR/Highlight-Studio-v${VERSION}-play-signed-${STAMP}.apk"
BUNDLE_DEST="$DEST_DIR/Highlight-Studio-v${VERSION}-play-upload-${STAMP}.aab"
mkdir -p "$DEST_DIR"
chmod 700 "$OUTPUT_ROOT" "$DEST_DIR"
cp "$APK" "$APK_DEST"
cp "$BUNDLE" "$BUNDLE_DEST"
chmod 600 "$APK_DEST" "$BUNDLE_DEST"

echo "Signed Play APK: $APK_DEST"
echo "Signed Play App Bundle: $BUNDLE_DEST"
echo "Version: $VERSION (code $VERSION_CODE)"
echo "Certificate SHA-256: $APK_CERT"
shasum -a 256 "$APK_DEST" "$BUNDLE_DEST"

if [ -n "$DEVICE" ]; then
  command -v android >/dev/null || { echo "Android CLI is not installed" >&2; exit 1; }
  android install --use-delta-install --device="$DEVICE" --apks="$APK_DEST" --install-options=-r
fi
