#!/bin/bash
# deploy-apk.sh — Android APK 发布 + 国内 CDN 分发 + 下载页同步 (单一事实源)
#
# 单一事实源 = frontend/CretasFoodTrace/android/app/build.gradle 的 versionName。
# 本脚本根治"版本漂移": 一条命令把 APK 推到 OSS(CDN 回源) + 重写下载页全站版本文案 +
# 部署下载页到 139 + 校验可下。绝不手动改多处链接。
#
# 硬闸 (生死线):
#   1. 签名校验 — 发布前比对 APK 签名 SHA-256 == release keystore 的 SHA-256。
#      若是 debug 签名 (keystore.properties 缺失时的 fallback), 立即拒绝发布。
#   2. 残留扫描 — sed 后 grep 全 download-page/ 确认无旧版本号 / 占位符 / example.com。
#
# 用法:
#   ./scripts/deploy/deploy-apk.sh --build               # 构建签名 APK → 发布全流程
#   ./scripts/deploy/deploy-apk.sh --apk ~/Downloads/cretas-v1.0.1.apk
#   ./scripts/deploy/deploy-apk.sh --build --dry-run     # 构建+校验, 不上传/不部署
#   ./scripts/deploy/deploy-apk.sh --apk <path> --with-server-copy   # 额外传一份到 139 downloads/
#
# 依赖: keytool(JDK), ossutil(账号 B), 可选 aliyun(CDN 刷新). keystore.properties 必须存在。

set -euo pipefail

# ==================== 配置 ====================
OSS_BUCKET="${OSS_BUCKET:-cretas-download}"           # CDN 源站桶 (账号 A/C, 与 CDN 同账号)
OSS_CONFIG="${OSS_CONFIG:-$HOME/.ossutilconfig-apk}"  # 账号 A/C ossutil 配置 (与默认账号 B cretas-media 区分)
CDN_DOMAIN="${CDN_DOMAIN:-dl.cretaceousfuture.com}"
PAGE_GATEWAY="${PAGE_GATEWAY:-root@139.196.165.140}"
PAGE_REMOTE_DIR="${PAGE_REMOTE_DIR:-/www/wwwroot/download}"          # download.cretaceousfuture.com 根
APK_REMOTE_DIR="${APK_REMOTE_DIR:-/www/wwwroot/download/downloads}"  # nginx /downloads/ 回退路径
OSSUTIL="${OSSUTIL:-ossutil64}"
APK_NAME_PREFIX="cretas-v"   # 最终文件名 = cretas-vX.Y.Z.apk
LATEST_ALIAS="cretas-latest.apk"

# ==================== 参数 ====================
DO_BUILD=0
APK_PATH=""
DRY_RUN=0
WITH_SERVER_COPY=0
SKIP_PAGE_DEPLOY=0
PAGE_ONLY=0

while [ $# -gt 0 ]; do
  case "$1" in
    --build) DO_BUILD=1; shift ;;
    --apk) APK_PATH="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --with-server-copy) WITH_SERVER_COPY=1; shift ;;
    --skip-page-deploy) SKIP_PAGE_DEPLOY=1; shift ;;
    --page-only) PAGE_ONLY=1; shift ;;   # 仅重写+部署下载页, 跳过 APK/签名/OSS/CDN (APK 未变时改页面用)
    *) echo "❌ 未知参数: $1"; echo "用法见脚本头部注释"; exit 1 ;;
  esac
done

log() { echo "[$(date '+%H:%M:%S')] $*"; }
die() { echo "[$(date '+%H:%M:%S')] ❌ $*" >&2; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# ssh_local_path: Windows 盘符路径会被 rsync/scp 当成主机名, 传输直接失败。
source "$PROJECT_ROOT/scripts/lib/deploy-common.sh"
ANDROID_DIR="$PROJECT_ROOT/frontend/CretasFoodTrace/android"
GRADLE_FILE="$ANDROID_DIR/app/build.gradle"
PAGE_DIR="$PROJECT_ROOT/download-page"

[ -f "$GRADLE_FILE" ] || die "找不到 build.gradle: $GRADLE_FILE"
[ -d "$PAGE_DIR" ] || die "找不到 download-page/: $PAGE_DIR"

# ==================== 0. 版本 (单一事实源) ====================
VER="$(grep -oE 'versionName "[0-9]+\.[0-9]+\.[0-9]+"' "$GRADLE_FILE" | head -1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+')"
[ -n "$VER" ] || die "无法从 build.gradle 解析 versionName"
APK_FILE="${APK_NAME_PREFIX}${VER}.apk"
CDN_URL="https://${CDN_DOMAIN}/${APK_FILE}"
log "📌 版本 (build.gradle versionName): v$VER"
log "   APK 文件名: $APK_FILE"
log "   CDN 直链:   $CDN_URL"

if [ "$PAGE_ONLY" = "0" ]; then
# ==================== 1. 定位 / 构建 APK ====================
DEFAULT_APK="$ANDROID_DIR/app/build/outputs/apk/release/app-release.apk"
if [ "$DO_BUILD" = "1" ]; then
  log "📦 [build] gradlew assembleRelease (Windows CMake MAX_PATH 注意见 build.gradle)..."
  ( cd "$ANDROID_DIR" && EXPO_PUBLIC_ENVIRONMENT=production EXPO_PUBLIC_DEBUG_MODE=false ./gradlew assembleRelease )
  APK_PATH="$DEFAULT_APK"
fi
[ -z "$APK_PATH" ] && APK_PATH="$DEFAULT_APK"
[ -f "$APK_PATH" ] || die "找不到 APK: $APK_PATH (用 --build 构建, 或 --apk <path> 指定)"
APK_SIZE_BYTES="$(stat -c %s "$APK_PATH" 2>/dev/null || stat -f %z "$APK_PATH")"
log "   ✓ APK: $APK_PATH ($(awk "BEGIN{printf \"%.1f\", $APK_SIZE_BYTES/1048576}") MB)"

# ==================== 2. 签名 SHA-256 硬闸 (生死线) ====================
# 解析 keystore.properties (env var 优先, 便于跨 worktree/main checkout)
KP="${CRETAS_KEYSTORE_PROPERTIES:-$ANDROID_DIR/keystore.properties}"
[ -f "$KP" ] || die "找不到 keystore.properties ($KP) — 无法校验签名, 拒绝发布。
   设置 CRETAS_KEYSTORE_PROPERTIES 指向你的 keystore.properties, 或放到 $ANDROID_DIR/keystore.properties"
get_prop() { grep -E "^$1=" "$KP" | head -1 | cut -d= -f2- | tr -d '\r'; }
KS_FILE="$(get_prop RELEASE_STORE_FILE)"
KS_PASS="$(get_prop RELEASE_STORE_PASSWORD)"
KS_ALIAS="$(get_prop RELEASE_KEY_ALIAS)"
[ -f "$KS_FILE" ] || die "keystore.properties 指向的 keystore 不存在: $KS_FILE"

# 归一化: 去冒号/空白 + 小写 (keytool 出 E2:C5:.. 大写带冒号; apksigner 出小写无冒号)
norm_sha() { tr -d ':[:space:]\r\n' | tr 'A-F' 'a-f'; }
EXPECTED_SHA="$(keytool -list -v -keystore "$KS_FILE" -storepass "$KS_PASS" -alias "$KS_ALIAS" 2>/dev/null \
  | grep -iE 'SHA256:' | head -1 | sed -E 's/.*SHA256:[[:space:]]*//' | norm_sha)"
# 优先 apksigner (支持 v1/v2/v3 签名方案); 回退 keytool -printcert (仅识别 v1 jar 签名)
APKSIGNER=""
for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/AppData/Local/Android/Sdk" "$HOME/Android/Sdk"; do
  [ -n "$d" ] || continue
  cand="$(ls "$d"/build-tools/*/apksigner* 2>/dev/null | sort -V | tail -1)"
  [ -n "$cand" ] && { APKSIGNER="$cand"; break; }
done
if [ -n "$APKSIGNER" ]; then
  APK_SHA="$("$APKSIGNER" verify --print-certs "$APK_PATH" 2>/dev/null \
    | grep -iE 'certificate SHA-256 digest' | head -1 | sed -E 's/.*digest:[[:space:]]*//' | norm_sha)"
else
  APK_SHA="$(keytool -printcert -jarfile "$APK_PATH" 2>/dev/null \
    | grep -iE 'SHA256:' | head -1 | sed -E 's/.*SHA256:[[:space:]]*//' | norm_sha)"
fi
[ -n "$EXPECTED_SHA" ] || die "无法从 keystore 读取 SHA-256 (密码错误?)"
[ -n "$APK_SHA" ] || die "无法从 APK 读取签名 SHA-256 (未签名?) — apksigner/keytool 均失败"
log "🔐 签名校验:"
log "   release key : $EXPECTED_SHA"
log "   APK signer  : $APK_SHA"
if [ "$APK_SHA" != "$EXPECTED_SHA" ]; then
  die "签名不匹配! APK 不是用 release key 签的 (很可能是 debug 签名 fallback)。
   绝不能分发 — 老用户将无法覆盖更新。检查 keystore.properties 是否在构建机生效。"
fi
log "   ✓ 签名 = release key, 通过硬闸"
fi  # PAGE_ONLY=0: 步骤 1-2 (APK 定位/构建 + 签名硬闸) 仅完整发布

# ==================== 3. 重写下载页 (单一事实源) ====================
log "📝 [page] 重写 download-page/*.html 版本文案..."
shopt -s nullglob
PAGE_FILES=("$PAGE_DIR"/*.html)
[ ${#PAGE_FILES[@]} -gt 0 ] || die "download-page/ 下没有 .html"
for f in "${PAGE_FILES[@]}"; do
  # 1) APK 直链文件名: cretas-vX.Y.Z.apk → 当前版本
  sed -i -E "s#${APK_NAME_PREFIX}[0-9]+\.[0-9]+\.[0-9]+\.apk#${APK_FILE}#g" "$f"
  # 2) APP_VERSION = 'X.Y.Z' → 当前版本 (页面其余版本文案由 JS 从此变量渲染)
  sed -i -E "s#(APP_VERSION[[:space:]]*=[[:space:]]*')[0-9]+\.[0-9]+\.[0-9]+(')#\1${VER}\2#g" "$f"
  log "   ✓ $(basename "$f")"
done

# ==================== 4. 残留扫描 (硬闸) ====================
log "🔎 [scan] 残留扫描 download-page/ ..."
RESID=0
# 任何不等于当前版本的 cretas-v*.apk
if grep -rEn "${APK_NAME_PREFIX}[0-9]+\.[0-9]+\.[0-9]+\.apk" "$PAGE_DIR" | grep -v "$APK_FILE" >/dev/null 2>&1; then
  log "   🔴 发现旧版本 APK 链接残留:"; grep -rEn "${APK_NAME_PREFIX}[0-9]+\.[0-9]+\.[0-9]+\.apk" "$PAGE_DIR" | grep -v "$APK_FILE"; RESID=1
fi
# 占位 / 示例域名
if grep -rEn "download\.example\.com|app-\\\$\{|YOUR_|TODO_APK" "$PAGE_DIR" >/dev/null 2>&1; then
  log "   🔴 发现占位/示例残留:"; grep -rEn "download\.example\.com|YOUR_|TODO_APK" "$PAGE_DIR"; RESID=1
fi
[ "$RESID" = "0" ] && log "   ✓ 无残留" || die "下载页有残留, 修正后重跑 (避免部分用户下到旧包)"

if [ "$DRY_RUN" = "1" ]; then
  log "🧪 dry-run: 跳过 OSS 上传 / 页面部署 / CDN 刷新 / 验证"
  log "   (签名已校验通过, 下载页已就地重写为 v$VER)"
  exit 0
fi

if [ "$PAGE_ONLY" = "0" ]; then
# ==================== 5. 上传 OSS (CDN 回源) ====================
command -v "$OSSUTIL" >/dev/null 2>&1 || die "$OSSUTIL 未安装 (OSS). 见 .claude/rules/server-operations.md"
[ -f "$OSS_CONFIG" ] || die "找不到 OSS 配置 $OSS_CONFIG (账号 A/C, 需对 $OSS_BUCKET 有写权限)"
CT="Content-Type:application/vnd.android.package-archive"
# 不设 per-object ACL: 桶已是 public-read, 对象继承; 账号策略禁止单对象 public ACL.
OSSARGS=(--config-file "$OSS_CONFIG" -f --meta "$CT")
log "☁️  [oss] 上传 $APK_FILE → oss://$OSS_BUCKET/ ..."
"$OSSUTIL" cp "${OSSARGS[@]}" "$APK_PATH" "oss://$OSS_BUCKET/$APK_FILE" >/dev/null
log "   ✓ 上传 $APK_FILE"
log "☁️  [oss] 更新 latest 别名 → $LATEST_ALIAS ..."
"$OSSUTIL" cp "${OSSARGS[@]}" "$APK_PATH" "oss://$OSS_BUCKET/$LATEST_ALIAS" >/dev/null
log "   ✓ 上传 $LATEST_ALIAS"

# ==================== 6. (可选) 服务器副本 ====================
if [ "$WITH_SERVER_COPY" = "1" ]; then
  log "📤 [server] 传一份到 $PAGE_GATEWAY:$APK_REMOTE_DIR/ (139 出口慢, 仅作回退)..."
  ssh "$PAGE_GATEWAY" "mkdir -p '$APK_REMOTE_DIR' && chown -R www:www '$PAGE_REMOTE_DIR' 2>/dev/null || true"
  scp -q "$(ssh_local_path "$APK_PATH")" "$PAGE_GATEWAY:$APK_REMOTE_DIR/$APK_FILE"
  REMOTE_MD5="$(ssh "$PAGE_GATEWAY" "md5sum '$APK_REMOTE_DIR/$APK_FILE'" | awk '{print $1}')"
  LOCAL_MD5="$(md5sum "$APK_PATH" | awk '{print $1}')"
  [ "$REMOTE_MD5" = "$LOCAL_MD5" ] || die "服务器副本 md5 不匹配 (local=$LOCAL_MD5 remote=$REMOTE_MD5)"
  log "   ✓ 服务器副本 md5 一致 ($LOCAL_MD5)"
fi
fi  # PAGE_ONLY=0: 步骤 5-6 (OSS 上传 + 服务器副本) 仅完整发布

# ==================== 7. 部署下载页到 139 ====================
if [ "$SKIP_PAGE_DEPLOY" = "0" ]; then
  log "🚀 [page] 部署 download-page/ → $PAGE_GATEWAY:$PAGE_REMOTE_DIR/ ..."
  ssh "$PAGE_GATEWAY" "mkdir -p '$PAGE_REMOTE_DIR'"
  scp -q "$PAGE_DIR"/*.html "$PAGE_GATEWAY:$PAGE_REMOTE_DIR/"
  # 图标 (可选, 存在才传)
  ICON_SRC="$PROJECT_ROOT/frontend/CretasFoodTrace/assets/icon.png"
  [ -f "$ICON_SRC" ] && scp -q "$(ssh_local_path "$ICON_SRC")" "$PAGE_GATEWAY:$PAGE_REMOTE_DIR/icon.png" || true
  ssh "$PAGE_GATEWAY" "chown -R www:www '$PAGE_REMOTE_DIR' 2>/dev/null || true"
  log "   ✓ 下载页已部署"
fi

if [ "$PAGE_ONLY" = "0" ]; then
# ==================== 8. CDN 刷新 (best-effort, latest 别名) ====================
if command -v aliyun >/dev/null 2>&1; then
  log "♻️  [cdn] 刷新 latest 别名缓存..."
  aliyun cdn RefreshObjectCaches --ObjectPath "https://${CDN_DOMAIN}/${LATEST_ALIAS}" --ObjectType File >/dev/null 2>&1 \
    && log "   ✓ 已提交刷新" || log "   ⚠️ CDN 刷新失败 (非致命, 版本化文件名无需刷新)"
else
  log "   ⚠️ aliyun CLI 未安装, 跳过 CDN 刷新 (版本化文件名 $APK_FILE 是新对象, 无需刷新)"
fi

# ==================== 9. 验证可下 ====================
log "🔍 [verify] curl -sI $CDN_URL ..."
HDRS="$(curl -sIL --max-time 30 "$CDN_URL" || true)"
HTTP_CODE="$(printf '%s' "$HDRS" | grep -iE '^HTTP/' | tail -1 | awk '{print $2}')"
CLEN="$(printf '%s' "$HDRS" | grep -iE '^Content-Length:' | tail -1 | awk '{print $2}' | tr -d '\r')"
XCACHE="$(printf '%s' "$HDRS" | grep -iE '^(X-Cache|X-Swift-CacheTime|Via):' | head -1 | tr -d '\r')"
log "   HTTP $HTTP_CODE  Content-Length=$CLEN  ($XCACHE)"
[ "$HTTP_CODE" = "200" ] || die "CDN 返回 $HTTP_CODE != 200 (DNS/CDN/OSS 回源未就绪?)"
if [ -n "$CLEN" ] && [ "$CLEN" != "$APK_SIZE_BYTES" ]; then
  die "CDN Content-Length($CLEN) != 本地 APK($APK_SIZE_BYTES) — 上传可能截断"
fi
log "   ✓ CDN 可下且大小一致"
fi  # PAGE_ONLY=0: CDN 刷新 + 可下验证仅完整发布

echo ""
log "$([ "$PAGE_ONLY" = "1" ] && echo "✅ 下载页已更新部署 (page-only) v$VER" || echo "✅ 发布完成 v$VER")"
log "   下载页:   https://download.${CDN_DOMAIN#dl.}/   (即 download.cretaceousfuture.com)"
log "   APK 直链: $CDN_URL"
if [ "$PAGE_ONLY" = "0" ]; then
echo ""
log "下一步 (版本网关): 更新 47 服务器 .env.prod 的"
log "   APP_VERSION_LATEST=$VER"
log "   APP_VERSION_FILE_SIZE=$APK_SIZE_BYTES"
log "   然后 systemctl restart cretas-backend (prod) / cretas-backend-test (test)"
fi
