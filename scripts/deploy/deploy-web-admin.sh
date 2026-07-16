#!/bin/bash
# web-admin 部署脚本 v2.1 — Vite build + 原子目录交换 + 旧 assets 有限期延续 + test/prod 分离
#
# 为什么要做原子交换:
#   Vite 用 content-hash 命名 chunk (e.g. DynamicModulePage-DZGo_ThO.js)。每次 deploy
#   会产生新 hash 的 chunk 文件。如果只做 overlay (tar 解压到现有目录),老 chunk 文件
#   会无限累积 — 最后 /assets/ 会塞满几千个过期文件。
#
#   原子交换: 部署到 .staging → mv 旧目录到 .bak.TS → mv .staging 到目标路径。瞬间完成。
#
# v2.1 (Jul 13 2026) — stale-tab 404 修复:
#   v2.0 每次交换整个目录, 旧 hash 的 chunk 文件立刻消失。未刷新的老 tab 导航到
#   懒加载路由时请求旧 chunk URL → 404 → 用户被迫刷新。
#   现在原子交换前, 把仍在保留窗口内 (默认 24h, `--asset-retention-hours` 可调,
#   传 0 退回 v2.0 行为) 的旧 chunk 用 `cp -np` (no-clobber + 保留原 mtime) 延续进新
#   assets/ 目录 — 保留窗口从 chunk 首次构建时算起, 不会因反复部署被续期到无限累积,
#   过期后自然被下次部署淘汰。代价是 assets/ 目录在窗口期内比纯替换略大, 换来老 tab
#   在窗口期内导航不 404。
#
# v2.0 (Apr 18 2026) — Bug #30 fix:
#   之前 REMOTE_PATH 硬编码 /www/wwwroot/web-admin (prod), 运行 script 就直接上 prod,
#   违反 "不动 prod" 硬规则. 现在强制 --env 参数区分:
#     --env test (DEFAULT)  → /www/wwwroot/web-admin-test  (139:8097 vhost)
#     --env prod            → /www/wwwroot/web-admin       (139:8086 / admin domain)
#     --env all             → 先 test, smoke 后 prod (对齐 deploy-backend.sh)
#
# 用法:
#   ./scripts/deploy/deploy-web-admin.sh              # 默认 test
#   ./scripts/deploy/deploy-web-admin.sh --env test   # 显式 test
#   ./scripts/deploy/deploy-web-admin.sh --env prod   # prod (会 confirm 提示)
#   ./scripts/deploy/deploy-web-admin.sh --dry-run    # 构建但不上传
#
# 服务器路径:
#   test: 139.196.165.140:/www/wwwroot/web-admin-test/  (vhost 8097)
#   prod: 139.196.165.140:/www/wwwroot/web-admin/       (vhost 8086 + admin.cretaceousfuture.com 443)

set -e

# ==================== 参数解析 ====================
ENV="test"   # 默认 test (Bug #30 fix, 之前默认 prod 导致误操作)
DRY_RUN=0
PROD_CONFIRM="${CRETAS_WEB_PROD_CONFIRM:-}"
ASSET_RETENTION_HOURS=24   # 旧 chunk 延续窗口(小时); 0 = 退回 v2.0 纯原子替换

while [ $# -gt 0 ]; do
    case "$1" in
        --env)
            ENV="$2"
            shift 2
            ;;
        --env=*)
            ENV="${1#*=}"
            shift
            ;;
        --dry-run)
            DRY_RUN=1
            shift
            ;;
        --confirm-prod)
            PROD_CONFIRM="${2:-}"
            shift 2
            ;;
        --confirm-prod=*)
            PROD_CONFIRM="${1#*=}"
            shift
            ;;
        --asset-retention-hours)
            ASSET_RETENTION_HOURS="$2"
            shift 2
            ;;
        --asset-retention-hours=*)
            ASSET_RETENTION_HOURS="${1#*=}"
            shift
            ;;
        *)
            echo "❌ 未知参数: $1"
            echo "用法: $0 [--env test|prod|all] [--confirm-prod YES-PROD] [--dry-run] [--asset-retention-hours N]"
            exit 1
            ;;
    esac
done

case "$ENV" in
    test|prod|all) ;;
    *)
        echo "❌ --env 必须是 test / prod / all (你传了: $ENV)"
        exit 1
        ;;
esac

if ! [[ "$ASSET_RETENTION_HOURS" =~ ^[0-9]+$ ]]; then
    echo "❌ --asset-retention-hours 必须是非负整数 (你传了: $ASSET_RETENTION_HOURS)"
    exit 1
fi

confirm_prod_deploy() {
    if [ "$PROD_CONFIRM" = "YES-PROD" ]; then
        echo "    Production deployment explicitly confirmed by caller."
        return 0
    fi
    if [ -n "$PROD_CONFIRM" ]; then
        echo "ERROR: invalid production confirmation '$PROD_CONFIRM' (expected YES-PROD)"
        return 1
    fi
    if [ ! -t 0 ]; then
        echo "ERROR: production deployment requires --confirm-prod YES-PROD or CRETAS_WEB_PROD_CONFIRM=YES-PROD"
        return 1
    fi
    read -r -p "    Enter 'YES-PROD' to continue, anything else to cancel: " confirm
    if [ "$confirm" != "YES-PROD" ]; then
        echo "Production deployment cancelled."
        return 1
    fi
}

# Fail before git/network/build work when a non-interactive production caller
# forgot to make the production choice explicit.
if [[ "$ENV" =~ ^(prod|all)$ ]]; then
    confirm_prod_deploy
fi

# ==================== 配置 ====================
GATEWAY="root@139.196.165.140"
REMOTE_BACKUP_DIR="/www/wwwroot/web-admin-backups"
LOCAL_BUILD_DIR="web-admin/dist"
TMP_TAR="/tmp/web-admin-dist.$$.tar.gz"
BACKUP_KEEP=3

# ==================== 加载共享函数库 ====================
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
if [ -f "$PROJECT_ROOT/scripts/lib/deploy-common.sh" ]; then
    source "$PROJECT_ROOT/scripts/lib/deploy-common.sh"
else
    echo "❌ 未找到 $PROJECT_ROOT/scripts/lib/deploy-common.sh"; exit 1
fi
# 注: source 引入 common 的 log() (双参数 LEVEL msg); 本脚本下方重新定义单参数 log() 覆盖之.
# check_git_sync 在覆盖前调用, 用的是 common log; 之后的 log "..." 用本脚本单参数版.

ensure_web_admin_dependencies() {
    local web_admin_dir="$PROJECT_ROOT/web-admin"
    local vite_bin="$web_admin_dir/node_modules/.bin/vite"
    local vite_cmd="$web_admin_dir/node_modules/.bin/vite.cmd"
    local manifest="$web_admin_dir/node_modules/.cretas-package-lock.sha256"
    local started_at lock_hash manifest_tmp elapsed

    started_at=$(date +%s)
    if command -v sha256sum >/dev/null 2>&1; then
        lock_hash=$(sha256sum "$web_admin_dir/package-lock.json" | awk '{print $1}')
    else
        lock_hash=$(shasum -a 256 "$web_admin_dir/package-lock.json" | awk '{print $1}')
    fi

    if [ -f "$manifest" ] \
        && [ "$(tr -d '\r\n' < "$manifest")" = "$lock_hash" ] \
        && { [ -x "$vite_bin" ] || [ -f "$vite_cmd" ]; }; then
        elapsed=$(( $(date +%s) - started_at ))
        log "✓ Trusted package-lock dependency cache hit; npm ci skipped"
        log "   Dependency reuse stage: ${elapsed}s"
        return 0
    fi

    log "📦 Dependency cache miss; running npm ci --legacy-peer-deps --prefer-offline --no-audit --no-fund..."
    if ! (cd "$web_admin_dir" && npm ci --legacy-peer-deps --prefer-offline --no-audit --no-fund); then
        log "❌ Web 依赖恢复失败 — 拒绝确认或构建部署"
        return 1
    fi

    if [ ! -x "$vite_bin" ] && [ ! -f "$vite_cmd" ]; then
        log "❌ npm ci 完成后仍未找到本地 Vite 可执行文件 — 拒绝继续部署"
        return 1
    fi

    manifest_tmp="${manifest}.tmp.$$"
    printf '%s\n' "$lock_hash" > "$manifest_tmp"
    mv -f "$manifest_tmp" "$manifest"
    elapsed=$(( $(date +%s) - started_at ))
    log "✓ Web 依赖恢复完成，已原子记录 package-lock digest"
    log "   Dependency restore stage: ${elapsed}s"
}

# ==================== Git Sync Pre-check ====================
# 防 stale-local-deploy (May 11 2026 bug fix; per feedback_organizer_must_git_pull_before_deploy.md):
# deploy 从本地工作树 build Vite dist, 本地落后 origin/main → ship stale code.
#
# Jul 7 2026 事故修复: --env 含 prod (prod/all) 时启用 strict 模式 — 非
# origin/main HEAD 或脏工作树直接 ABORT (之前只 WARN).
GIT_SYNC_STRICT=""
if [[ "$ENV" =~ ^(prod|all)$ ]]; then
    GIT_SYNC_STRICT="1"
fi
check_git_sync "$PROJECT_ROOT" "[0/4] Git sync pre-check..." "$GIT_SYNC_STRICT"

log() {
    echo "[$(date '+%H:%M:%S')] $*"
}

# 依赖门禁放在 prod 确认和构建之前：干净 worktree 没有 node_modules 时先自动恢复，
# 避免用户确认生产部署后才因 vite 缺失中断。每个 worktree 独立安装，禁止 junction 共享。
ensure_web_admin_dependencies

# 根据 --env 决定目标路径
if [ "$ENV" = "prod" ]; then
    REMOTE_PATH="/www/wwwroot/web-admin"
    echo "⚠️  ⚠️  ⚠️  PROD 部署  ⚠️  ⚠️  ⚠️"
    echo "    目标: $REMOTE_PATH (139:8086 / admin.cretaceousfuture.com)"
    echo "    影响: 所有 prod 用户立刻看到新代码"
    echo ""
    echo ""
elif [ "$ENV" = "all" ]; then
    # all 模式: test → smoke → prod, 单独循环处理见下
    echo "🔄 --env all: 先部 test → smoke → prod (需要最后 confirm)"
    REMOTE_PATH="/www/wwwroot/web-admin-test"  # 先 test
else
    # test (default)
    REMOTE_PATH="/www/wwwroot/web-admin-test"
    echo "🧪 TEST 部署 (默认): $REMOTE_PATH (139:8097)"
fi

# 原子交换部署: 远端解压 tarball 到 staging → 备份旧版 → mv staging→current → 清理旧 backup.
# 用 staging+mv 实现原子替换 (避免 nginx 读到新旧 chunk 混合的半成品).
# $1 = 远程目标路径 (test: /www/wwwroot/web-admin-test, prod: /www/wwwroot/web-admin)
# 依赖全局: $GATEWAY $REMOTE_TAR $REMOTE_BACKUP_DIR $BACKUP_KEEP (调用时须已定义)
atomic_swap_webadmin() {
    local target_path="$1"
    ssh "$GATEWAY" bash <<REMOTE_DEPLOY
set -e
TS=\$(date +%Y%m%d_%H%M%S)
STAGING="${target_path}.staging"
CURRENT="$target_path"
BACKUP_DIR="$REMOTE_BACKUP_DIR"

mkdir -p "\$BACKUP_DIR"

# 解压到 staging
rm -rf "\$STAGING"
mkdir -p "\$STAGING"
tar xzf "$REMOTE_TAR" -C "\$STAGING"

# 记录旧/新 assets 数量 (用于对比)
OLD_ASSET_COUNT=0
if [ -d "\$CURRENT/assets" ]; then
    OLD_ASSET_COUNT=\$(find "\$CURRENT/assets" -type f 2>/dev/null | wc -l)
fi
NEW_ASSET_COUNT=\$(find "\$STAGING/assets" -type f 2>/dev/null | wc -l)

# stale-tab 保留期延续: 把仍在窗口内 (mtime < ${ASSET_RETENTION_HOURS}h) 的旧 chunk
# 用 cp -np (no-clobber, 保留原 mtime) 延续进新 assets/。窗口从 chunk 首次构建时算起
# (mtime 不因本次延续被刷新),所以不会因反复部署无限累积 — 过期后下次部署自然不再延续。
# ASSET_RETENTION_HOURS=0 时整段跳过, 退回 v2.0 纯原子替换。
CARRIED_COUNT=0
if [ $(( ASSET_RETENTION_HOURS )) -gt 0 ] && [ -d "\$CURRENT/assets" ]; then
    mkdir -p "\$STAGING/assets"
    while IFS= read -r -d '' f; do
        cp -np "\$f" "\$STAGING/assets/" 2>/dev/null && CARRIED_COUNT=\$((CARRIED_COUNT + 1))
    done < <(find "\$CURRENT/assets" -type f -mmin -$(( ASSET_RETENTION_HOURS * 60 )) -print0 2>/dev/null)
fi

echo "   旧 assets: \$OLD_ASSET_COUNT → 新 assets: \$NEW_ASSET_COUNT (+ \$CARRIED_COUNT 个保留期内旧 chunk 延续, 窗口 ${ASSET_RETENTION_HOURS}h)"

# 原子交换 (备份旧版 + mv)
if [ -e "\$CURRENT" ]; then
    mv "\$CURRENT" "\$BACKUP_DIR/web-admin.bak.\$TS"
fi
mv "\$STAGING" "\$CURRENT"
echo "   ✓ 原子交换完成 (backup: web-admin.bak.\$TS)"

# 清理旧 backups (保留最近 $BACKUP_KEEP 份)
ls -1dt "\$BACKUP_DIR"/web-admin.bak.* 2>/dev/null | tail -n +$(($BACKUP_KEEP + 1)) | while read old; do
    rm -rf "\$old"
    echo "   - removed: \$(basename \$old)"
done

rm -f "$REMOTE_TAR"
echo "   ✓ index.html mtime: \$(stat -c '%y' "\$CURRENT/index.html" 2>/dev/null | cut -d. -f1)"
REMOTE_DEPLOY
}

# ==================== 1. 本地构建 ====================
log "📦 [1/4] 本地构建 web-admin..."
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT/web-admin"

# Apr 29 2026 fix (张权 banner deploy 故障): 之前 `npm run build 2>&1 | tail -5`
# 通过 pipe 把 npm 的 exit code 吃掉了 (pipe 的 exit code 是 tail 的 0), build 失败但
# 后续 dist/index.html 检查仍通过 (旧 dist 还在), 部署上传旧 dist + 报"成功".
# 用 PIPESTATUS 拿到 npm 的真实 exit code, 失败立即 exit.
set -o pipefail
npm run build 2>&1 | tail -5
BUILD_RC=${PIPESTATUS[0]}
set +o pipefail
if [ "$BUILD_RC" != "0" ]; then
    log "❌ 构建失败 (npm run build exit=$BUILD_RC) — 拒绝继续部署"
    exit 1
fi

if [ ! -f "dist/index.html" ]; then
    log "❌ 构建失败: dist/index.html 不存在"
    exit 1
fi

# Apr 29 2026 强化: build 跑完 dist 应 < 120s. 之前是 warning, 现在 hard fail.
# 防止 build 被 silent skip / npm cache 复用旧 dist 等情况.
DIST_AGE=$(($(date +%s) - $(stat -c %Y dist/index.html 2>/dev/null || stat -f %m dist/index.html)))
if [ "$DIST_AGE" -gt 120 ]; then
    log "❌ dist/index.html 修改时间已 ${DIST_AGE}s 前 (> 120s 阈值)"
    log "   build 可能未真正运行. 拒绝继续部署."
    log "   排查: cd web-admin && rm -rf dist && npm run build 看错误"
    exit 1
fi

# 提取本地 dist 的 entry chunk hash, 用于部署后内容验证
LOCAL_ENTRY_HASH=$(grep -oP 'assets/index-[A-Za-z0-9_-]+\.js' dist/index.html | head -1)
if [ -z "$LOCAL_ENTRY_HASH" ]; then
    log "⚠️  无法提取本地 dist 的 entry chunk, 跳过 post-deploy 内容验证"
fi

ASSET_COUNT=$(find dist/assets -type f 2>/dev/null | wc -l | tr -d ' ')
DIST_SIZE=$(du -sh dist/ | cut -f1)
log "   ✓ 构建完成: $ASSET_COUNT 个 assets, $DIST_SIZE"

# ==================== 2. 打包 ====================
log "📦 [2/4] 打包 tarball..."
tar czf "$TMP_TAR" -C dist .
TAR_SIZE=$(du -sh "$TMP_TAR" | cut -f1)
log "   ✓ Tarball: $TAR_SIZE"

if [ "$DRY_RUN" = "1" ]; then
    log "🧪 dry-run: 跳过上传和部署"
    log "   本地 tarball 保留: $TMP_TAR"
    exit 0
fi

# ==================== 3. 上传 ====================
# May 13 2026 race fix: per-PID remote tarball path eliminates concurrent-deploy
# collision on shared /tmp/web-admin-dist.tar.gz. Symptom hit 3x in one evening:
# scp printed "✓ 上传完成", then atomic-swap heredoc tar xzf failed with
# "Cannot open /tmp/web-admin-dist.tar.gz" — because a parallel deploy's heredoc
# rm -f (line ~238) deleted the shared file in the ~1-3s gap between scp and
# this script's own heredoc. Per-PID path means each deploy owns its own tarball.
REMOTE_TAR="/tmp/web-admin-dist.$$.tar.gz"
log "📤 [3/4] 上传到 $GATEWAY (remote: $REMOTE_TAR)..."
scp -q "$TMP_TAR" "$GATEWAY:$REMOTE_TAR"

# Post-scp verify: defense in depth. If scp ever silently mis-writes (partial
# transfer, wrong dest, /tmp full), tar xzf fails downstream with confusing
# "Cannot open" — opaque to operator. Catch here with explicit size match.
LOCAL_SIZE=$(stat -c %s "$TMP_TAR" 2>/dev/null || stat -f %z "$TMP_TAR" 2>/dev/null || echo "0")
REMOTE_SIZE=$(ssh "$GATEWAY" "stat -c %s '$REMOTE_TAR' 2>/dev/null || echo MISSING" | tr -d '\r\n')
if [ "$REMOTE_SIZE" != "$LOCAL_SIZE" ]; then
    log "❌ post-scp verify FAILED: local=${LOCAL_SIZE}B remote=${REMOTE_SIZE}"
    log "   Remote file missing/partial/wrong-size. Aborting before atomic-swap."
    ssh "$GATEWAY" "rm -f '$REMOTE_TAR'" 2>/dev/null || true
    exit 1
fi
log "   ✓ 上传完成 (verified ${LOCAL_SIZE}B match)"

# ==================== 4. 原子部署 + 旧版本清理 ====================
log "🚀 [4/4] 原子交换 + 清理旧 backups..."
atomic_swap_webadmin "$REMOTE_PATH"

rm -f "$TMP_TAR"

# ==================== 5. 验证 ====================
log "🔍 验证..."
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://139.196.165.140:8086/" || echo "000")
log "   HTTP $HTTP_CODE (http://139.196.165.140:8086/)"

if [ "$HTTP_CODE" != "200" ]; then
    log "⚠️  验证失败 (HTTP $HTTP_CODE),请手动检查"
    exit 1
fi

# R43 fix: --env all 之前 silently 只部 test, 现在测试通过后 prompt 部 prod
if [ "$ENV" = "all" ]; then
    log "✅ Test 部署完成"
    echo ""
    echo "⚠️  ⚠️  ⚠️  PROD 部署 (第二阶段)  ⚠️  ⚠️  ⚠️"
    echo "    目标: /www/wwwroot/web-admin (139:8086 / admin.cretaceousfuture.com)"
    echo "    影响: 所有 prod 用户立刻看到新代码"
    echo ""
    # 重新打包 + 上传 (test 那次已 rm'd $REMOTE_TAR via heredoc cleanup)
    log "📦 [prod 1/3] 重新打包..."
    cd "$PROJECT_ROOT/web-admin/dist"
    tar czf "$TMP_TAR" .
    cd "$PROJECT_ROOT"
    log "   ✓ Tarball: $(du -h "$TMP_TAR" | awk '{print $1}')"

    log "📤 [prod 2/3] 上传 prod (remote: $REMOTE_TAR)..."
    scp -q "$TMP_TAR" "$GATEWAY:$REMOTE_TAR"

    # Post-scp verify (mirror of test path — same race-fix rationale)
    LOCAL_SIZE=$(stat -c %s "$TMP_TAR" 2>/dev/null || stat -f %z "$TMP_TAR" 2>/dev/null || echo "0")
    REMOTE_SIZE=$(ssh "$GATEWAY" "stat -c %s '$REMOTE_TAR' 2>/dev/null || echo MISSING" | tr -d '\r\n')
    if [ "$REMOTE_SIZE" != "$LOCAL_SIZE" ]; then
        log "❌ prod post-scp verify FAILED: local=${LOCAL_SIZE}B remote=${REMOTE_SIZE}"
        ssh "$GATEWAY" "rm -f '$REMOTE_TAR'" 2>/dev/null || true
        exit 1
    fi
    log "   ✓ 上传完成 (verified ${LOCAL_SIZE}B match)"

    log "🚀 [prod 3/3] 原子交换 prod..."
    REMOTE_PATH="/www/wwwroot/web-admin"  # prod target
    atomic_swap_webadmin "$REMOTE_PATH"
    rm -f "$TMP_TAR"

    log "🔍 验证 prod..."
    PROD_CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://139.196.165.140:8086/" || echo "000")
    log "   HTTP $PROD_CODE (http://139.196.165.140:8086/)"
    if [ "$PROD_CODE" = "200" ]; then
        log "✅ Prod 部署完成"
    else
        log "⚠️  Prod 验证失败 (HTTP $PROD_CODE)"
        exit 1
    fi
fi

log "✅ 部署完成"
