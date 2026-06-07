#!/bin/bash
# web-admin 部署脚本 v2.0 — Vite build + 原子目录交换 + 旧 assets 清理 + test/prod 分离
#
# 为什么要做原子交换:
#   Vite 用 content-hash 命名 chunk (e.g. DynamicModulePage-DZGo_ThO.js)。每次 deploy
#   会产生新 hash 的 chunk 文件。如果只做 overlay (tar 解压到现有目录),老 chunk 文件
#   会无限累积 — 最后 /assets/ 会塞满几千个过期文件。浏览器 tab 如果长时间不刷新,
#   还会加载旧 chunk 的 URL (已经修过 bug 的旧版本代码) → 404 日志噪音。
#
#   原子交换: 部署到 .staging → mv 旧目录到 .bak.TS → mv .staging 到目标路径。
#   瞬间完成,old chunks 被一次性清理。代价是: 未刷新的老 tab 在导航时会 chunk load
#   fail → 用户被迫刷新。这是正确的行为,stale tab 不应该继续运行。
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
        *)
            echo "❌ 未知参数: $1"
            echo "用法: $0 [--env test|prod|all] [--dry-run]"
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

# ==================== Git Sync Pre-check ====================
# 防 stale-local-deploy (May 11 2026 bug fix; per feedback_organizer_must_git_pull_before_deploy.md):
# deploy 从本地工作树 build Vite dist, 本地落后 origin/main → ship stale code.
check_git_sync "$PROJECT_ROOT" "[0/4] Git sync pre-check..."

# 根据 --env 决定目标路径
if [ "$ENV" = "prod" ]; then
    REMOTE_PATH="/www/wwwroot/web-admin"
    echo "⚠️  ⚠️  ⚠️  PROD 部署  ⚠️  ⚠️  ⚠️"
    echo "    目标: $REMOTE_PATH (139:8086 / admin.cretaceousfuture.com)"
    echo "    影响: 所有 prod 用户立刻看到新代码"
    echo ""
    read -p "    真的部 prod? 输 'YES-PROD' 继续, 其他退出: " confirm
    if [ "$confirm" != "YES-PROD" ]; then
        echo "❌ 已取消 (你输: '$confirm')"
        exit 1
    fi
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

log() {
    echo "[$(date '+%H:%M:%S')] $*"
}

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
echo "   旧 assets: \$OLD_ASSET_COUNT → 新 assets: \$NEW_ASSET_COUNT"

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
    read -p "    输 'YES-PROD' 继续部 prod, 其他跳过 prod: " confirm
    if [ "$confirm" != "YES-PROD" ]; then
        echo "✅ Test 已部署, 跳过 prod (你输: '$confirm')"
        exit 0
    fi

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
