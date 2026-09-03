#!/usr/bin/env bash
# Builds a verified HiLight release with the permanent signing identity saved by Android Studio.
# Passwords are read from macOS Keychain and exist only in this short-lived, no-daemon build.
set -euo pipefail

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
OUTPUT_ROOT="${HILIGHT_RELEASE_OUTPUT_DIR:-$HOME/Library/Application Support/HiLight Studio/releases}"
DEVICE=""

usage() {
  echo "Usage: $(basename "$0") [--device SERIAL]"
  echo "Builds and verifies the signed APK. --device also installs it with Android CLI."
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
  ./gradlew --no-daemon :app:assembleGithubRelease

cleanup

APK="$ROOT/app/build/outputs/apk/github/release/app-github-release.apk"
[ -f "$APK" ] || {
  echo "signed APK was not produced; refusing any unsigned release artifact" >&2
  exit 1
}

APKSIGNER="$(find "$SDK/build-tools" -maxdepth 2 -type f -name apksigner -print \
  | sort -V | tail -1)"
AAPT2="$(find "$SDK/build-tools" -maxdepth 2 -type f -name aapt2 -print \
  | sort -V | tail -1)"
[ -x "$APKSIGNER" ] || { echo "missing apksigner" >&2; exit 1; }
[ -x "$AAPT2" ] || { echo "missing aapt2" >&2; exit 1; }

CERT="$($APKSIGNER verify --print-certs "$APK" \
  | awk -F': ' '/Signer #1 certificate SHA-256 digest:/{print tolower($2); exit}')"
[ "$CERT" = "$EXPECTED_CERT" ] || {
  echo "release certificate mismatch; refusing artifact" >&2
  exit 1
}

VERSION="$($AAPT2 dump badging "$APK" \
  | sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p" \
  | head -1)"
[ -n "$VERSION" ] || { echo "could not read APK version" >&2; exit 1; }

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DEST_DIR="$OUTPUT_ROOT/v$VERSION"
DEST="$DEST_DIR/HiLight-Studio-v${VERSION}-experimental-signed-${STAMP}.apk"
mkdir -p "$DEST_DIR"
chmod 700 "$OUTPUT_ROOT" "$DEST_DIR"
cp "$APK" "$DEST"
chmod 600 "$DEST"

echo "Signed APK: $DEST"
echo "Certificate SHA-256: $CERT"
shasum -a 256 "$DEST"

if [ -n "$DEVICE" ]; then
  command -v android >/dev/null || { echo "Android CLI is not installed" >&2; exit 1; }
  android install --use-delta-install --device="$DEVICE" --apks="$DEST" --install-options=-r
fi
