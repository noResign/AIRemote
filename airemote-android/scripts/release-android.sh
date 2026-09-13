#!/usr/bin/env bash
set -euo pipefail

# 用法:
#   scripts/release-android.sh alpha
#   scripts/release-android.sh prod
#
# 可选环境变量:
#   VERSION_CODE=123
#   VERSION_NAME=0.1.20260913
#   CHANGELOG="修复 xxx"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

CHANNEL="${1:-}"
if [[ "$CHANNEL" != "alpha" && "$CHANNEL" != "prod" ]]; then
  echo "usage: $0 <alpha|prod>" >&2
  exit 1
fi

OSS_BUCKET="oss://your-bucket/android"
OSS_PUBLIC_BASE="https://your-bucket.oss-cn-hangzhou.aliyuncs.com/android"

if [[ ! -f "$ROOT_DIR/keystore.properties" ]]; then
  echo "missing keystore.properties; copy keystore.properties.example and fill it" >&2
  exit 1
fi

if ! command -v ossutil >/dev/null 2>&1; then
  echo "ossutil not found in PATH" >&2
  exit 1
fi

VERSION_CODE="${VERSION_CODE:-$(date +%s)}"
VERSION_NAME="${VERSION_NAME:-0.1.$(date +%Y%m%d%H%M)}"
DISPLAY_VERSION_NAME="$VERSION_NAME"
if [[ "$CHANNEL" == "alpha" ]]; then
  DISPLAY_VERSION_NAME="${VERSION_NAME}-alpha"
fi
CHANGELOG="${CHANGELOG:-$(git log -1 --pretty=%s 2>/dev/null || true)}"

TASK="assembleAlphaRelease"
APK_PATH="$ROOT_DIR/app/build/outputs/apk/alpha/release/app-alpha-release.apk"
if [[ "$CHANNEL" == "prod" ]]; then
  TASK="assembleProdRelease"
  APK_PATH="$ROOT_DIR/app/build/outputs/apk/prod/release/app-prod-release.apk"
fi

echo "==> build $CHANNEL release"
echo "    versionCode=$VERSION_CODE"
echo "    versionName=$VERSION_NAME"
./gradlew ":app:$TASK" "-PversionCode=$VERSION_CODE" "-PversionName=$VERSION_NAME"

if [[ ! -f "$APK_PATH" ]]; then
  echo "apk not found: $APK_PATH" >&2
  exit 1
fi

APK_NAME="airemote-${VERSION_CODE}-${CHANNEL}.apk"
MANIFEST_PATH="$ROOT_DIR/build/releases/${CHANNEL}-manifest.json"
mkdir -p "$ROOT_DIR/build/releases"
cp "$APK_PATH" "$ROOT_DIR/build/releases/$APK_NAME"

APK_SIZE="$(stat -c %s "$ROOT_DIR/build/releases/$APK_NAME")"
SHA256="$(sha256sum "$ROOT_DIR/build/releases/$APK_NAME" | awk '{print $1}')"
PUBLISHED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
APK_URL="$OSS_PUBLIC_BASE/$CHANNEL/$APK_NAME"

jq -n \
  --arg channel "$CHANNEL" \
  --argjson versionCode "$VERSION_CODE" \
  --arg versionName "$DISPLAY_VERSION_NAME" \
  --arg apkUrl "$APK_URL" \
  --argjson apkSize "$APK_SIZE" \
  --arg sha256 "$SHA256" \
  --arg changelog "$CHANGELOG" \
  --arg publishedAt "$PUBLISHED_AT" \
  '{channel:$channel,versionCode:$versionCode,versionName:$versionName,apkUrl:$apkUrl,apkSize:$apkSize,sha256:$sha256,changelog:$changelog,publishedAt:$publishedAt}' \
  > "$MANIFEST_PATH"

echo "==> upload apk"
ossutil cp -f "$ROOT_DIR/build/releases/$APK_NAME" "$OSS_BUCKET/$CHANNEL/$APK_NAME"
echo "==> upload manifest"
ossutil cp -f "$MANIFEST_PATH" "$OSS_BUCKET/$CHANNEL/manifest.json"

echo
echo "done:"
echo "  apk:      $OSS_PUBLIC_BASE/$CHANNEL/$APK_NAME"
echo "  manifest: $OSS_PUBLIC_BASE/$CHANNEL/manifest.json"
