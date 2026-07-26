#!/usr/bin/env bash
# Push a fresh JS/asset bundle to the self-hosted OTA server on 47.
#
# Pipeline:
#   1. npx expo export --platform <p>      → frontend/CretasFoodTrace/dist/
#   2. npx expo config --json              → frontend/CretasFoodTrace/dist/expoConfig.json
#   3. tar -czf … && scp                   → server 47 staging
#   4. gzip Hermes bundle + mirror assets  → OSS/CDN
#   5. atomic mv <ts>.tmp/ → <ts>/         → manifest becomes visible
#   6. curl POST /api/ota/admin/register   → server validates + acknowledges
#
# Usage:
#   export OTA_ADMIN_TOKEN=<hex64>          # source ~/.ota-env on operator box
#   ./scripts/ota/push-bundle.sh [production|staging] [android|ios]
#
# Defaults: channel=production, platform=android.

set -euo pipefail

CHANNEL="${1:-production}"
PLATFORM="${2:-android}"
SERVER="${OTA_SERVER:-root@47.100.235.168}"
SERVER_BUNDLE_ROOT="${OTA_SERVER_BUNDLE_ROOT:-/www/wwwroot/ota}"
SERVER_API="${OTA_SERVER_API:-http://47.100.235.168:8083}"
ADMIN_TOKEN="${OTA_ADMIN_TOKEN:?OTA_ADMIN_TOKEN env var required — source ~/.ota-env}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# shellcheck source=lib/ota-cdn.sh
source "$SCRIPT_DIR/lib/ota-cdn.sh"

# Same component regex as ota.services.storage._VALID_PATH_COMPONENT (spec §1).
# Kept in sync via tests/test_scripts.py::test_bash_regex_matches_storage_regex.
SAFE_COMPONENT='^[A-Za-z0-9][A-Za-z0-9._-]*$'

# --- arg validation -----------------------------------------------------------

if [[ ! "$CHANNEL" =~ ^(production|staging)$ ]]; then
    echo "ERROR: channel must be 'production' or 'staging' (got '$CHANNEL')" >&2
    exit 2
fi

if [[ "$PLATFORM" != "android" && "$PLATFORM" != "ios" ]]; then
    echo "ERROR: platform must be 'android' or 'ios' (got '$PLATFORM')" >&2
    exit 2
fi

# The bundle runtime must come from the same resolved Expo config as the
# exported JavaScript. A staging build deliberately uses the `-test` runtime.
if [[ "$CHANNEL" == "production" ]]; then
    export EXPO_PUBLIC_ENV="production"
else
    export EXPO_PUBLIC_ENV="test"
fi

# --- read the effective runtime version --------------------------------------

APP_DIR="frontend/CretasFoodTrace"
if [[ ! -f "$APP_DIR/app.json" ]]; then
    echo "ERROR: $APP_DIR/app.json not found — run from repo root" >&2
    exit 2
fi

RUNTIME_VERSION="$(
    cd "$APP_DIR"
    npx expo config --json | jq -r '.runtimeVersion // .version'
)"
if [[ -z "$RUNTIME_VERSION" || "$RUNTIME_VERSION" == "null" ]]; then
    echo "ERROR: cannot resolve runtimeVersion from Expo config" >&2
    exit 2
fi

if [[ ! "$RUNTIME_VERSION" =~ $SAFE_COMPONENT ]]; then
    echo "ERROR: runtime version '$RUNTIME_VERSION' fails $SAFE_COMPONENT" >&2
    exit 2
fi

TIMESTAMP="$(date +%s%3N)"
TARGET="updates/${RUNTIME_VERSION}/${CHANNEL}/${TIMESTAMP}"
TARGET_TMP="updates/${RUNTIME_VERSION}/${CHANNEL}/${TIMESTAMP}.tmp"

echo "[push-bundle] runtimeVersion=$RUNTIME_VERSION channel=$CHANNEL platform=$PLATFORM timestamp=$TIMESTAMP"

# --- 1. expo export -----------------------------------------------------------

echo "[push-bundle] 1/6 npx expo export --clear --platform $PLATFORM"
(
    cd frontend/CretasFoodTrace
    rm -rf dist
    npx expo export --clear --platform "$PLATFORM"
)

# --- 2. expoConfig.json (expo export does not auto-emit; spec §7.1) ----------

echo "[push-bundle] 2/6 npx expo config --json > dist/expoConfig.json"
(
    cd frontend/CretasFoodTrace
    npx expo config --json > dist/expoConfig.json
)

# 2.5 Normalize Windows backslash asset paths in metadata.json. `expo export`
# on Windows emits `assets\\<hash>`; the Linux OTA server treats backslash as a
# literal filename char → FileNotFoundError → "Bundle metadata corrupted" 500.
echo "[push-bundle] 2.5 normalizing metadata.json path separators (Windows safe)"
"${PYTHON:-python}" "$SCRIPT_DIR/_fix_meta_slashes.py" frontend/CretasFoodTrace/dist/metadata.json

# Sanity: expected files must exist.
for f in frontend/CretasFoodTrace/dist/metadata.json frontend/CretasFoodTrace/dist/expoConfig.json; do
    if [[ ! -f "$f" ]]; then
        echo "ERROR: expected output $f missing after expo export" >&2
        exit 3
    fi
done

# --- 3. tar + scp -------------------------------------------------------------

TARBALL="/tmp/ota-bundle-${TIMESTAMP}.tar.gz"
echo "[push-bundle] 3/6 packaging $TARBALL"
tar -czf "$TARBALL" -C frontend/CretasFoodTrace/dist .

echo "[push-bundle] 4/6 uploading staging bundle to $SERVER:$SERVER_BUNDLE_ROOT/$TARGET_TMP"
scp -q "$TARBALL" "$SERVER:/tmp/"

# Extract into a hidden staging directory. The manifest server only reads
# numeric final directories, so clients cannot observe this update until the
# CDN upload has completed and the directory is atomically promoted.
ssh "$SERVER" bash -s <<REMOTE_EOF
set -euo pipefail
mkdir -p "${SERVER_BUNDLE_ROOT}/${TARGET_TMP}"
tar -xzf "/tmp/ota-bundle-${TIMESTAMP}.tar.gz" -C "${SERVER_BUNDLE_ROOT}/${TARGET_TMP}/"
rm "/tmp/ota-bundle-${TIMESTAMP}.tar.gz"
REMOTE_EOF
rm "$TARBALL"

cleanup_remote_staging() {
    ssh "$SERVER" "rm -rf '${SERVER_BUNDLE_ROOT}/${TARGET_TMP}'" >/dev/null 2>&1 || true
}
trap cleanup_remote_staging ERR

echo "[push-bundle] 5/6 gzip + mirror launch bundle/assets to OSS/CDN"
OSS_DEST="$(ota_upload_bundle_to_oss \
    frontend/CretasFoodTrace/dist \
    "$RUNTIME_VERSION" \
    "$CHANNEL" \
    "$TIMESTAMP")"
echo "[push-bundle]   CDN objects ready: $OSS_DEST"

echo "[push-bundle] 5.5/6 atomically promoting staged bundle"
ssh "$SERVER" "mv '${SERVER_BUNDLE_ROOT}/${TARGET_TMP}' '${SERVER_BUNDLE_ROOT}/${TARGET}'"
trap - ERR

# --- 6. register via admin API ------------------------------------------------

# Register over SSH on the server itself: 47:8083 is firewalled to the 139
# gateway only (a dev box hitting it directly times out / exit 28), but
# localhost on 47 always reaches it.
echo "[push-bundle] 6/6 register via $SERVER localhost:8083"
HTTP_CODE="$(ssh "$SERVER" "curl -s -o /dev/null -w '%{http_code}' \
    -X POST http://localhost:8083/api/ota/admin/register \
    -H 'Authorization: Bearer $ADMIN_TOKEN' \
    -H 'Content-Type: application/json' \
    -d '{\"runtimeVersion\":\"$RUNTIME_VERSION\",\"channel\":\"$CHANNEL\",\"timestamp\":\"$TIMESTAMP\"}'")"

if [[ "$HTTP_CODE" != "200" ]]; then
    echo "ERROR: /admin/register returned $HTTP_CODE" >&2
    exit 4
fi

echo "[push-bundle] ✓ bundle live: rv=$RUNTIME_VERSION channel=$CHANNEL ts=$TIMESTAMP"
echo "[push-bundle]   verify: curl -H 'expo-protocol-version: 1' -H 'expo-platform: $PLATFORM' \\"
echo "                       -H 'expo-runtime-version: $RUNTIME_VERSION' \\"
echo "                       -H 'expo-channel-name: $CHANNEL' $SERVER_API/api/ota/manifest"
