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
#
# ⛔ ENVFILE 必须和 channel 一起定 —— 漏了会发出打不通后端的包 (2026-08-02 发版前拦下)。
#
# babel.config.js 的 react-native-dotenv 读的是 `process.env.ENVFILE || '.env'`,
# 而 src/constants/config.ts 的 API_BASE_URL 就来自 `@env` 的 REACT_APP_API_URL。
# 本脚本此前只设 EXPO_PUBLIC_ENV(它只影响 app.config.js 里的 runtimeVersion/channel),
# 从不设 ENVFILE, 于是 `npx expo export` 落到 `.env`:
#
#   · 干净 worktree 里根本没有 `.env` (它是 gitignored) → REACT_APP_API_URL 为空
#     → config.ts 回落到 `http://10.0.2.2:10010` (安卓模拟器宿主地址) → 真机全废;
#   · 而 README 让"从仓库根目录跑", 主工作目录里那个 gitignored `.env` 实测是
#     `http://139.196.165.140:8086` —— 那是 **web-admin 的 nginx**, 不是移动端 API,
#     还是明文 HTTP。
#
# 也就是说两条路都发不出能用的生产包, 且**打进去的地址取决于某台机器上一个
# 未跟踪文件当时的内容**。eas.json 的 APK 构建早就把这件事做对了
# (production → .env.production / preview → .env.test), OTA 这条链漏了同一份映射。
# 这里补齐, 并且显式导出 —— 让 bundle 里的 API 地址由 channel 唯一决定。
if [[ "$CHANNEL" == "production" ]]; then
    export EXPO_PUBLIC_ENV="production"
    export ENVFILE="${ENVFILE:-.env.production}"
else
    export EXPO_PUBLIC_ENV="test"
    export ENVFILE="${ENVFILE:-.env.test}"
fi

if [[ ! -f "frontend/CretasFoodTrace/$ENVFILE" ]]; then
    echo "ERROR: frontend/CretasFoodTrace/$ENVFILE 不存在 —— 拒绝导出一个 API 地址未知的包" >&2
    exit 2
fi
echo "  ENVFILE=$ENVFILE  ($(grep -E '^REACT_APP_API_URL=' "frontend/CretasFoodTrace/$ENVFILE" | head -1))"

# --- step timing (pure instrumentation; no behaviour change) -------------------
# 目的: 这条链有两段聚合耗时看不穿 —— expo export 之后到打包之前, 以及打包之后到
# 生效之前。没有分步数字就只能靠猜哪里该并行。这里只记时间, 不改任何顺序或逻辑。
STEP_T0="$(date +%s)"
STEP_LAST="$STEP_T0"
STEP_LOG=()

mark_step() {
    local now delta
    now="$(date +%s)"
    delta=$(( now - STEP_LAST ))
    STEP_LOG+=("$(printf '%-44s %5ds' "$1" "$delta")")
    STEP_LAST="$now"
}

print_step_summary() {
    echo ""
    echo "[push-bundle] ---- step timings ----"
    local line
    for line in "${STEP_LOG[@]}"; do
        echo "[push-bundle]   $line"
    done
    printf '[push-bundle]   %-44s %5ds
' "TOTAL" "$(( $(date +%s) - STEP_T0 ))"
}

# --- read the effective runtime version --------------------------------------

APP_DIR="frontend/CretasFoodTrace"
if [[ ! -f "$APP_DIR/app.json" ]]; then
    echo "ERROR: $APP_DIR/app.json not found — run from repo root" >&2
    exit 2
fi

# 🔴 2026-08-17: 加这个 override 的理由 —— **原生工程里的 runtimeVersion 会和 app.json 漂**。
# 实测: app.json 写 "1.0.4", 而 android/app/src/main/res/values/strings.xml 里的
# expo_runtime_version 还停在 "1.0.3"(android/ 是跟踪在 git 里的, prebuild 不会重生成它)。
# 于是**装着 1.0.4 二进制的机器向服务器要 1.0.3 的 bundle**, 而我们每次都推进 1.0.4 树 ——
# 零交集, 自 1.0.4 发布(8-09)以来 OTA 一次都没送达过。
# ⇒ 迁移期需要能显式推到「设备实际在要的那棵树」。
# ⚠️ 用之前必须确认原生依赖没漂(package.json 的 dependencies 一个字没动),
#    否则就是把不兼容的 JS bundle 推给旧二进制 —— 那正是 v1.0.4 崩溃的同一种病。
# 判据: 服务器日志 `grep OTA_PULL ... | grep runtime=` 看设备**实际报的**是哪个版本。
if [[ -n "${OTA_RUNTIME_VERSION_OVERRIDE:-}" ]]; then
    RUNTIME_VERSION="$OTA_RUNTIME_VERSION_OVERRIDE"
    echo "[push-bundle] ⚠️ runtimeVersion 被 OTA_RUNTIME_VERSION_OVERRIDE 覆盖为 '$RUNTIME_VERSION'" >&2
    echo "[push-bundle] ⚠️ (app.json 声明的是 $(cd "$APP_DIR" && npx expo config --json | jq -r '.runtimeVersion // .version'))" >&2
else
RUNTIME_VERSION="$(
    cd "$APP_DIR"
    npx expo config --json | jq -r '.runtimeVersion // .version'
)"
fi
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

mark_step "0  read runtimeVersion (expo config #1)"

echo "[push-bundle] runtimeVersion=$RUNTIME_VERSION channel=$CHANNEL platform=$PLATFORM timestamp=$TIMESTAMP"

# --- 1. expo export -----------------------------------------------------------

echo "[push-bundle] 1/6 npx expo export --clear --platform $PLATFORM"
(
    cd frontend/CretasFoodTrace
    rm -rf dist
    npx expo export --clear --platform "$PLATFORM"
)

mark_step "1  expo export --clear"

# --- 2. expoConfig.json (expo export does not auto-emit; spec §7.1) ----------

echo "[push-bundle] 2/6 npx expo config --json > dist/expoConfig.json"
(
    cd frontend/CretasFoodTrace
    npx expo config --json > dist/expoConfig.json
)

mark_step "2  expo config #2 -> expoConfig.json"

# 2.5 Normalize Windows backslash asset paths in metadata.json. `expo export`
# on Windows emits `assets\\<hash>`; the Linux OTA server treats backslash as a
# literal filename char → FileNotFoundError → "Bundle metadata corrupted" 500.
echo "[push-bundle] 2.5 normalizing metadata.json path separators (Windows safe)"
"${PYTHON:-python}" "$SCRIPT_DIR/_fix_meta_slashes.py" frontend/CretasFoodTrace/dist/metadata.json

mark_step "2.5 normalize metadata.json paths"

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

mark_step "3  tar -czf local tarball"

echo "[push-bundle] 4/6 uploading staging bundle to $SERVER:$SERVER_BUNDLE_ROOT/$TARGET_TMP"
scp -q "$TARBALL" "$SERVER:/tmp/"

mark_step "4a scp tarball -> server"

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

mark_step "4b ssh extract into .tmp staging"

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

mark_step "5  gzip + mirror to OSS/CDN"

echo "[push-bundle] 5.5/6 atomically promoting staged bundle"
ssh "$SERVER" "mv '${SERVER_BUNDLE_ROOT}/${TARGET_TMP}' '${SERVER_BUNDLE_ROOT}/${TARGET}'"
trap - ERR

mark_step "5.5 atomic promote"

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

mark_step "6  register via admin API"

echo "[push-bundle] ✓ bundle live: rv=$RUNTIME_VERSION channel=$CHANNEL ts=$TIMESTAMP"
echo "[push-bundle]   verify: curl -H 'expo-protocol-version: 1' -H 'expo-platform: $PLATFORM' \\"
echo "                       -H 'expo-runtime-version: $RUNTIME_VERSION' \\"
echo "                       -H 'expo-channel-name: $CHANNEL' $SERVER_API/api/ota/manifest"

print_step_summary
