#!/bin/bash
# mobile-rest-ai 部署脚本 v1.0 — 手机端餐饮 AI 问答演示 (Vite build + 原子目录交换)
#
# 为什么需要这个脚本 (2026-07-28 飞轮回接发布实测):
#   `release-cretas.sh` 只检测 `backend/java/cretas-api/` 与 `web-admin/` 两个组件,
#   **完全不管 mobile-rest-ai**。飞轮回接卡4 改了 mobile-rest-ai 的问答链路
#   (直连 Python synthesis → 改走 Java 统一 executeIntent),代码 merge 进 main 后
#   线上包还停在 Jul 24 —— 改动等于白做, 而且没有任何报错提示。
#   人工 rsync 能救一次, 但下次照样漏。故补此脚本。
#
# ⚠️ 服务器在 139 不是 47:
#   mobile-rest-ai 由 139.196.165.140 的 nginx 以 alias 提供:
#     location ^~ /mobile-ai/rest/  → alias /www/wwwroot/mobile-ai/rest/
#   (见 139:/www/server/panel/vhost/nginx/admin.cretaceousfuture.com.conf 与 web-admin.conf)
#   vite.config.ts 的 `base: '/mobile-ai/rest/'` 必须与该 alias 一致, 否则资源 404。
#
# 只有 prod 一个环境 (没有 mobile-ai-test 路径), 所以 prod 确认是强制的。
#
# 用法:
#   ./scripts/deploy/deploy-mobile-rest-ai.sh --dry-run              # 只构建, 不上传
#   ./scripts/deploy/deploy-mobile-rest-ai.sh --confirm-prod YES-PROD
#
# 公网地址: https://admin.cretaceousfuture.com/mobile-ai/rest/

set -e

# ==================== 加载共享函数库 ====================
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
if [ -f "$PROJECT_ROOT/scripts/lib/deploy-common.sh" ]; then
    source "$PROJECT_ROOT/scripts/lib/deploy-common.sh"
else
    echo "❌ 未找到 $PROJECT_ROOT/scripts/lib/deploy-common.sh"; exit 1
fi

SERVER="root@139.196.165.140"
REMOTE_PATH="/www/wwwroot/mobile-ai/rest"
BACKUP_DIR="/www/wwwroot/mobile-ai"
BACKUP_KEEP=3
PUBLIC_URL="https://admin.cretaceousfuture.com/mobile-ai/rest/"
SRC_DIR="$PROJECT_ROOT/mobile-rest-ai"

DRY_RUN=0
PROD_CONFIRM="${CRETAS_MOBILE_REST_PROD_CONFIRM:-}"

while [ $# -gt 0 ]; do
    case "$1" in
        --dry-run) DRY_RUN=1; shift ;;
        --confirm-prod) PROD_CONFIRM="${2:-}"; shift 2 ;;
        --confirm-prod=*) PROD_CONFIRM="${1#*=}"; shift ;;
        -h|--help)
            echo "用法: $0 [--dry-run] [--confirm-prod YES-PROD]"
            exit 0
            ;;
        *) echo "ERROR: 未知参数: $1" >&2; exit 2 ;;
    esac
done

if [ "$DRY_RUN" != "1" ] && [ "$PROD_CONFIRM" != "YES-PROD" ]; then
    echo "ERROR: mobile-rest-ai 只有生产环境; 部署必须显式 --confirm-prod YES-PROD" >&2
    echo "       (只想验证构建请用 --dry-run)" >&2
    exit 2
fi

[ -d "$SRC_DIR" ] || { log "ERROR" "未找到 $SRC_DIR"; exit 1; }

# ==================== Git Sync Pre-check ====================
# 与 deploy-web-admin.sh 同款守卫: 从本地工作树 build, 本地落后 origin/main → ship stale code。
# 真部署一律 strict (非 origin/main HEAD 或脏工作树直接 ABORT); --dry-run 放宽为 WARN。
GIT_SYNC_STRICT=""
[ "$DRY_RUN" = "1" ] || GIT_SYNC_STRICT="1"
check_git_sync "$PROJECT_ROOT" "[0/4] Git sync pre-check..." "$GIT_SYNC_STRICT"

# ==================== 构建 ====================
log "INFO" "[1/4] 构建 mobile-rest-ai..."
cd "$SRC_DIR"
if [ ! -d node_modules ]; then
    log "INFO" "  node_modules 缺失, 安装依赖 (worktree 内独立安装, ⛔ 禁止 mklink /J 共享主仓)..."
    npm install --prefer-offline --legacy-peer-deps
fi
npm run build   # vue-tsc --noEmit && vite build

[ -f dist/index.html ] || { log "ERROR" "构建产物缺失: dist/index.html"; exit 1; }

# base 路径自检: index.html 必须引用 /mobile-ai/rest/assets/...,
# 否则 nginx alias 下资源全 404 (vite.config.ts 的 base 被改坏时立刻拦住)。
if ! grep -q '/mobile-ai/rest/assets/' dist/index.html; then
    log "ERROR" "dist/index.html 未引用 /mobile-ai/rest/assets/ — 检查 vite.config.ts 的 base"
    exit 1
fi

BUNDLE=$(grep -oE '/mobile-ai/rest/assets/index-[A-Za-z0-9_-]+\.js' dist/index.html | head -1)
BUNDLE_FILE="dist${BUNDLE#/mobile-ai/rest}"
[ -f "$BUNDLE_FILE" ] || { log "ERROR" "index.html 引用的 bundle 不存在: $BUNDLE_FILE"; exit 1; }
log "INFO" "  ✓ 构建完成, bundle=$(basename "$BUNDLE_FILE")"

if [ "$DRY_RUN" = "1" ]; then
    log "INFO" "[dry-run] 构建通过, 未上传。产物: $SRC_DIR/dist"
    exit 0
fi

# ==================== 上传到 staging ====================
log "INFO" "[2/4] 上传到远端 staging..."
STAGING="${REMOTE_PATH}.staging"
ssh "$SERVER" "rm -rf '$STAGING' && mkdir -p '$STAGING'"
rsync -az --delete dist/ "$SERVER:$STAGING/"

# ==================== 原子交换 ====================
# staging → 备份旧目录 → mv staging 到目标。避免 nginx 读到 index.html 与 assets 不匹配的半成品。
log "INFO" "[3/4] 原子交换..."
ssh "$SERVER" bash -s <<REMOTE
set -e
TS=\$(date +%Y%m%d-%H%M%S)
if [ -d "$REMOTE_PATH" ]; then
    mv "$REMOTE_PATH" "$BACKUP_DIR/rest.bak.\$TS"
fi
mv "$STAGING" "$REMOTE_PATH"
echo "   ✓ 原子交换完成 (backup: rest.bak.\$TS)"
# 只保留最近 $BACKUP_KEEP 份备份
ls -1dt "$BACKUP_DIR"/rest.bak.* 2>/dev/null | tail -n +$((BACKUP_KEEP + 1)) | while read -r old; do
    rm -rf "\$old" && echo "   清理旧备份: \$(basename "\$old")"
done
REMOTE

# ==================== 部署后校验 ====================
# ⛔ exit 0 ≠ 真上线 (per feedback_deploy_exit0_not_deployed_and_read_validation_upfront):
# 必须回读**线上实际提供**的 index.html, 确认它引用的正是本次构建的 bundle hash。
log "INFO" "[4/4] 校验线上实际提供的包..."
SERVED=$(curl -s -m 30 "$PUBLIC_URL" | grep -oE '/mobile-ai/rest/assets/index-[A-Za-z0-9_-]+\.js' | head -1)
if [ "$SERVED" != "$BUNDLE" ]; then
    log "ERROR" "线上 index.html 引用的是 '$SERVED', 期望 '$BUNDLE' — 部署未真正生效"
    exit 1
fi
CODE=$(curl -s -o /dev/null -w '%{http_code}' -m 30 "https://admin.cretaceousfuture.com$BUNDLE")
[ "$CODE" = "200" ] || { log "ERROR" "bundle 不可访问 (HTTP $CODE): $BUNDLE"; exit 1; }

log "INFO" "=========================================="
log "INFO" "mobile-rest-ai 部署完成"
log "INFO" "  $PUBLIC_URL"
log "INFO" "  bundle: $(basename "$BUNDLE")"
log "INFO" "=========================================="
