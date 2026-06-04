#!/usr/bin/env bash
# Push a fresh JS/asset bundle to the self-hosted OTA server on 47.
#
# Pipeline:
#   1. npx expo export --platform <p>      → frontend/CretasFoodTrace/dist/
#   2. npx expo config --json              → frontend/CretasFoodTrace/dist/expoConfig.json
#   3. tar -czf … && scp                   → server 47 /tmp
#   4. ssh: mkdir + extract into <ts>.tmp/ + atomic mv → <ts>/   (spec §7.2)
#   5. curl POST /api/ota/admin/register   → server validates + acknowledges
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

# --- read runtime version from app.json --------------------------------------

APP_JSON="frontend/CretasFoodTrace/app.json"
if [[ ! -f "$APP_JSON" ]]; then
    echo "ERROR: $APP_JSON not found — run from repo root" >&2
    exit 2
fi

RUNTIME_VERSION="$(jq -r '.expo.version' "$APP_JSON")"
if [[ -z "$RUNTIME_VERSION" || "$RUNTIME_VERSION" == "null" ]]; then
    echo "ERROR: cannot read .expo.version from $APP_JSON" >&2
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

echo "[push-bundle] 1/5 npx expo export --platform $PLATFORM"
(
    cd frontend/CretasFoodTrace
    rm -rf dist
    npx expo export --platform "$PLATFORM"
)

# --- 2. expoConfig.json (expo export does not auto-emit; spec §7.1) ----------

echo "[push-bundle] 2/5 npx expo config --json > dist/expoConfig.json"
(
    cd frontend/CretasFoodTrace
    npx expo config --json > dist/expoConfig.json
)

# 2.5 Normalize Windows backslash asset paths in metadata.json. `expo export`
# on Windows emits `assets\\<hash>`; the Linux OTA server treats backslash as a
# literal filename char → FileNotFoundError → "Bundle metadata corrupted" 500.
echo "[push-bundle] 2.5 normalizing metadata.json path separators (Windows safe)"
"${PYTHON:-python}" "$(dirname "$0")/_fix_meta_slashes.py" frontend/CretasFoodTrace/dist/metadata.json

# Sanity: expected files must exist.
for f in frontend/CretasFoodTrace/dist/metadata.json frontend/CretasFoodTrace/dist/expoConfig.json; do
    if [[ ! -f "$f" ]]; then
        echo "ERROR: expected output $f missing after expo export" >&2
        exit 3
    fi
done

# --- 3. tar + scp -------------------------------------------------------------

TARBALL="/tmp/ota-bundle-${TIMESTAMP}.tar.gz"
echo "[push-bundle] 3/5 packaging $TARBALL"
tar -czf "$TARBALL" -C frontend/CretasFoodTrace/dist .

echo "[push-bundle] 4/5 uploading + atomic-extracting to $SERVER:$SERVER_BUNDLE_ROOT/$TARGET"
scp -q "$TARBALL" "$SERVER:/tmp/"

# shellcheck disable=SC2087 -- heredoc intentionally expands locals
ssh "$SERVER" bash -s <<REMOTE_EOF
set -euo pipefail
mkdir -p "${SERVER_BUNDLE_ROOT}/${TARGET_TMP}"
tar -xzf "/tmp/ota-bundle-${TIMESTAMP}.tar.gz" -C "${SERVER_BUNDLE_ROOT}/${TARGET_TMP}/"
mv "${SERVER_BUNDLE_ROOT}/${TARGET_TMP}" "${SERVER_BUNDLE_ROOT}/${TARGET}"
rm "/tmp/ota-bundle-${TIMESTAMP}.tar.gz"
REMOTE_EOF
rm "$TARBALL"

# --- 5. register via admin API ------------------------------------------------

# Register over SSH on the server itself: 47:8083 is firewalled to the 139
# gateway only (a dev box hitting it directly times out / exit 28), but
# localhost on 47 always reaches it.
echo "[push-bundle] 5/5 register via $SERVER localhost:8083"
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
