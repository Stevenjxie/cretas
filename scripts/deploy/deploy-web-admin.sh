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
WEB_DEPLOY_STARTED_AT=$(date +%s)

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
TMP_INDEX="/tmp/web-admin-index.$$.html"
BACKUP_KEEP=3
WEB_DEPLOY_REPORT_PATH="${CRETAS_WEB_DEPLOY_REPORT_PATH:-$HOME/.cache/cretas/deploy-reports/web-${WEB_DEPLOY_STARTED_AT}-$$.json}"
WEB_DEPLOY_OUTCOME=unknown
WEB_HTTP_CODE=
WEB_HASH_LOCAL=
WEB_HASH_SERVER=
WEB_HASH_GATEWAY_HTTP=
WEB_HASH_PUBLIC_HTTPS=

# ==================== 加载共享函数库 ====================
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
if [ -f "$PROJECT_ROOT/scripts/lib/deploy-common.sh" ]; then
    source "$PROJECT_ROOT/scripts/lib/deploy-common.sh"
else
    echo "❌ 未找到 $PROJECT_ROOT/scripts/lib/deploy-common.sh"; exit 1
fi
if [ -f "$PROJECT_ROOT/scripts/deploy/release-web-manifest.sh" ]; then
    source "$PROJECT_ROOT/scripts/deploy/release-web-manifest.sh"
else
    echo "❌ 未找到 $PROJECT_ROOT/scripts/deploy/release-web-manifest.sh"; exit 1
fi

# 防止多个 chat/terminal 同时部署 web-admin。原子目录交换 (.staging → mv) 不是跨进程
# 原子的: 两个并发部署会互相踩 .staging 和 .bak.TS, 后写的 dist 静默覆盖先写的。
# 锁定到进程退出自动释放; 与 deploy-backend 用不同锁名, 二者可并行。
acquire_deploy_lock "cretas-web-admin-deploy" || exit 1
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

web_deploy_json_escape() {
    printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g; :a; N; $!ba; s/\n/\\n/g'
}

write_web_deploy_report() {
    local exit_code=${1:-1} finished total result commit web_tree tmp
    finished=$(date +%s)
    total=$((finished - WEB_DEPLOY_STARTED_AT))
    if [ "$exit_code" -eq 0 ]; then result=SUCCESS; else result=FAILED; fi
    commit=$(git -C "$PROJECT_ROOT" rev-parse HEAD 2>/dev/null || true)
    web_tree=$(git -C "$PROJECT_ROOT" rev-parse HEAD:web-admin 2>/dev/null || true)
    mkdir -p "$(dirname "$WEB_DEPLOY_REPORT_PATH")" || return 1
    tmp="$WEB_DEPLOY_REPORT_PATH.tmp.$$"
    {
        printf '{\n'
        printf '  "format": "cretas-web-deploy-report-v1",\n'
        printf '  "result": "%s",\n' "$result"
        printf '  "outcome": "%s",\n' "$(web_deploy_json_escape "$WEB_DEPLOY_OUTCOME")"
        printf '  "exit_code": %s,\n' "$exit_code"
        printf '  "total_wall_seconds": %s,\n' "$total"
        printf '  "commit": "%s",\n' "$(web_deploy_json_escape "$commit")"
        printf '  "web_tree": "%s",\n' "$(web_deploy_json_escape "$web_tree")"
        printf '  "archive_sha256": "%s",\n' "$(web_deploy_json_escape "${WEB_RELEASE_ARCHIVE_SHA256:-}")"
        printf '  "index_sha256": "%s",\n' "$(web_deploy_json_escape "${WEB_RELEASE_INDEX_SHA256:-}")"
        printf '  "http_code": "%s",\n' "$(web_deploy_json_escape "$WEB_HTTP_CODE")"
        printf '  "four_way_hashes": {"local": "%s", "server": "%s", "gateway_http": "%s", "public_https": "%s"}\n' \
            "$(web_deploy_json_escape "$WEB_HASH_LOCAL")" \
            "$(web_deploy_json_escape "$WEB_HASH_SERVER")" \
            "$(web_deploy_json_escape "$WEB_HASH_GATEWAY_HTTP")" \
            "$(web_deploy_json_escape "$WEB_HASH_PUBLIC_HTTPS")"
        printf '}\n'
    } >"$tmp" || { rm -f "$tmp"; return 1; }
    mv -f "$tmp" "$WEB_DEPLOY_REPORT_PATH"
}

web_deploy_on_exit() {
    local rc=$?
    write_web_deploy_report "$rc" || true
}
trap web_deploy_on_exit EXIT

# index.html 引用的 entry chunk 必须真的取得到。
#
# 为什么四方哈希 + 站点根 200 盖不住这件事: 四方哈希比的是 index.html 本身, 四个观测点看到
# 同一个坏 index.html 时它【照样 pass】; 站点根 200 也只证明 index.html 能被服务。若原子交换
# 装进的 index.html 引用了一个不存在的 chunk(半截解压/错 archive/旧 chunk 保留窗口错配),
# 两项全通过而所有用户白屏。
#
# 这正是 LOCAL_ENTRY_HASH 当初要防的事 —— 但它此前只被赋值和判空, 全脚本【没有任何消费者】,
# 那句"跳过 post-deploy 内容验证"描述的是一项从未实现过的验证。
verify_entry_chunk_reachable() {
    local base_url=$1 entry_url entry_code
    if [ -z "$LOCAL_ENTRY_HASH" ]; then
        printf 'WEB_ENTRY_CHUNK=unavailable\n' >&2
        log "⚠️  无 entry chunk 可验(提取阶段已告警)"
        return 0
    fi
    entry_url="${base_url%/}/$LOCAL_ENTRY_HASH"
    entry_code=$(curl -s -o /dev/null -w "%{http_code}" "$entry_url" || echo "000")
    log "   entry chunk HTTP $entry_code ($LOCAL_ENTRY_HASH)"
    if [ "$entry_code" = "200" ]; then
        printf 'WEB_ENTRY_CHUNK=pass\n'
        return 0
    fi
    printf 'WEB_ENTRY_CHUNK=failed\n' >&2
    log "❌ index.html 引用的 entry chunk 取不到 (HTTP $entry_code) —— 拒绝把本次发布标记为成功"
    return 1
}

verify_prod_web_four_way() {
    WEB_HASH_LOCAL=${WEB_RELEASE_INDEX_SHA256:-}
    WEB_HASH_SERVER=$(ssh "$GATEWAY" "sha256sum '/www/wwwroot/web-admin/index.html' 2>/dev/null | awk '{print \$1}'" | tr -d '\r\n')
    WEB_HASH_GATEWAY_HTTP=$(ssh "$GATEWAY" "curl -fsS http://127.0.0.1:8086/ | sha256sum | awk '{print \$1}'" | tr -d '\r\n')
    WEB_HASH_PUBLIC_HTTPS=$(curl -fsS https://admin.cretaceousfuture.com/ | sha256sum | awk '{print $1}')

    printf 'WEB_HASH_LOCAL=%s\n' "$WEB_HASH_LOCAL"
    printf 'WEB_HASH_SERVER=%s\n' "$WEB_HASH_SERVER"
    printf 'WEB_HASH_GATEWAY_HTTP=%s\n' "$WEB_HASH_GATEWAY_HTTP"
    printf 'WEB_HASH_PUBLIC_HTTPS=%s\n' "$WEB_HASH_PUBLIC_HTTPS"
    if [ -n "$WEB_HASH_LOCAL" ] \
        && [ "$WEB_HASH_LOCAL" = "$WEB_HASH_SERVER" ] \
        && [ "$WEB_HASH_LOCAL" = "$WEB_HASH_GATEWAY_HTTP" ] \
        && [ "$WEB_HASH_LOCAL" = "$WEB_HASH_PUBLIC_HTTPS" ]; then
        printf 'WEB_HASH_FOUR_WAY=pass\n'
        return 0
    fi
    printf 'WEB_HASH_FOUR_WAY=failed\n' >&2
    log "❌ Web 四方哈希不一致，拒绝把本次发布标记为成功"
    return 1
}

# 可信 Web archive 优先：只有 clean exact origin/main、构建提交可解析、
# web-admin Git tree 一致，且不可变 tar.gz SHA/index/引用完整性全部通过时才复用。
# 任一失败都回退既有依赖恢复 + 单次 npm build，禁止按 mtime/目录存在复用。
WEB_MANIFEST_PATH=$(web_release_default_manifest)
WEB_DIST_REUSED=0
WEB_ARCHIVE_PATH=""
if web_release_validate_cached "$WEB_MANIFEST_PATH" "$PROJECT_ROOT"; then
    WEB_DIST_REUSED=1
    WEB_ARCHIVE_PATH="$WEB_RELEASE_ARCHIVE_PATH"
    log "✓ Trusted Web dist manifest hit; npm ci/build skipped"
    log "   Build commit: $WEB_RELEASE_BUILD_COMMIT"
    log "   web-admin tree: $WEB_RELEASE_WEB_TREE"
    log "   archive SHA-256: $WEB_RELEASE_ARCHIVE_SHA256"
else
    if [ "${CRETAS_REQUIRE_TRUSTED_ARTIFACT:-0}" = "1" ]; then
        log "❌ 统一发布入口已完成 manifest 校验/单次回退，但子部署未命中可信 Web archive；拒绝第二次 Web 构建"
        exit 1
    fi
    LOCAL_BUILD_DIR="$PROJECT_ROOT/web-admin/dist"
    log "ℹ️ Trusted Web dist unavailable or invalid; falling back to one local build"
    # 每个 worktree 独立安装，禁止 junction 共享。
    ensure_web_admin_dependencies
fi

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
printf '%s\n' "$WEB_RELEASE_ARCHIVE_SHA256" > "\$STAGING/.cretas-release-sha256"

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
if [ "$WEB_DIST_REUSED" = "0" ]; then
    cd "$PROJECT_ROOT/web-admin"

    # Apr 29 2026 fix: preserve npm's real exit code instead of tail's status.
    set -o pipefail
    npm run build 2>&1 | tail -5
    BUILD_RC=${PIPESTATUS[0]}
    set +o pipefail
    if [ "$BUILD_RC" != "0" ]; then
        log "❌ 构建失败 (npm run build exit=$BUILD_RC) — 拒绝继续部署"
        exit 1
    fi

    if ! web_release_verify_dist "$LOCAL_BUILD_DIR"; then
        log "❌ 构建失败: dist/index.html、assets 或引用完整性检查未通过"
        exit 1
    fi

    # Freshness is only a fallback-build assertion. Trusted cache reuse is
    # proven by hashes and provenance, never by mtime.
    DIST_AGE=$(($(date +%s) - $(stat -c %Y "$LOCAL_BUILD_DIR/index.html" 2>/dev/null || stat -f %m "$LOCAL_BUILD_DIR/index.html")))
    if [ "$DIST_AGE" -gt 120 ]; then
        log "❌ dist/index.html 修改时间已 ${DIST_AGE}s 前 (> 120s 阈值)"
        log "   build 可能未真正运行. 拒绝继续部署."
        exit 1
    fi

    if web_release_write "$PROJECT_ROOT" "$LOCAL_BUILD_DIR" "$WEB_MANIFEST_PATH" "npm run build"; then
        WEB_ARCHIVE_PATH="$WEB_RELEASE_ARCHIVE_PATH"
        log "✓ Fallback build passed integrity checks; trusted Web manifest updated"
    else
        log "❌ 构建成功但可信 Web manifest 写入失败 — 拒绝部署无来源制品"
        exit 1
    fi
else
    log "📦 [1/4] 复用可信 Web dist（未执行 npm ci/build）"
fi

# 从不可变 archive 提取 index；后续上传的也是这一个已验证 archive。
web_release_extract_index "$WEB_ARCHIVE_PATH" "$TMP_INDEX" || {
    log "❌ 无法从可信 Web archive 提取 index.html"
    exit 1
}
# ⚠️ 这里【不能】用 grep -P。release-cretas.sh 必须以 LC_ALL=C 运行(否则 Java preflight 里
# 的 [A-Z] 按 collation 展开会匹配小写, 假报 "import 无法解析"), 而 GNU grep 在 C locale 下
# 直接拒绝 -P: "supports only unibyte and UTF-8 locales" → 提取【恒为空】。于是走统一入口
# 发布时这一项每次都被跳过, 而且只打一行 warning, 看起来像"偶发无法提取"。
# POSIX 字符类 + -E 在 C 与 en_US.UTF-8 下行为一致, 同时避开区间按 collation 展开那个坑。
LOCAL_ENTRY_HASH=$(grep -oE 'assets/index-[[:alnum:]_-]+\.js' "$TMP_INDEX" | head -1)
if [ -z "$LOCAL_ENTRY_HASH" ]; then
    log "⚠️  无法从 index.html 提取 entry chunk —— post-deploy 内容验证【无法执行】"
fi

ASSET_COUNT="$WEB_RELEASE_ASSET_COUNT"
ARCHIVE_SIZE=$(du -sh "$WEB_ARCHIVE_PATH" | cut -f1)
log "   ✓ 制品就绪: $ASSET_COUNT 个 assets, archive $ARCHIVE_SIZE"

# ==================== 2. 打包 ====================
log "📦 [2/4] 复用已验证 tarball..."
cp "$WEB_ARCHIVE_PATH" "$TMP_TAR"
TAR_SIZE=$(du -sh "$TMP_TAR" | cut -f1)
log "   ✓ Tarball: $TAR_SIZE"

if [ "$DRY_RUN" = "1" ]; then
    WEB_DEPLOY_OUTCOME=dry-run
    log "🧪 dry-run: 跳过上传和部署"
    log "   本地 tarball 保留: $TMP_TAR"
    rm -f "$TMP_INDEX"
    exit 0
fi

# 远端已运行同一不可变 archive 时，仍校验远端 index 和 HTTP，
# 但跳过上传、解压和原子交换。--env all 保留两阶段显式部署。
if [ "$ENV" != "all" ]; then
    REMOTE_RELEASE_SHA=$(ssh "$GATEWAY" "cat '$REMOTE_PATH/.cretas-release-sha256' 2>/dev/null || true" | tr -d '\r\n')
    if [ "$REMOTE_RELEASE_SHA" = "$WEB_RELEASE_ARCHIVE_SHA256" ]; then
        REMOTE_INDEX_SHA=$(ssh "$GATEWAY" "sha256sum '$REMOTE_PATH/index.html' 2>/dev/null | cut -d' ' -f1" | tr -d '\r\n')
        if [ "$ENV" = "prod" ]; then
            NOOP_VERIFY_URL="http://139.196.165.140:8086/"
        else
            NOOP_VERIFY_URL="http://139.196.165.140:8097/"
        fi
        NOOP_HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$NOOP_VERIFY_URL" || echo "000")
        if [ "$REMOTE_INDEX_SHA" = "$WEB_RELEASE_INDEX_SHA256" ] && [ "$NOOP_HTTP_CODE" = "200" ]; then
            WEB_HTTP_CODE=$NOOP_HTTP_CODE
            if [ "$ENV" = "prod" ]; then
                if ! verify_prod_web_four_way; then
                    rm -f "$TMP_TAR" "$TMP_INDEX"
                    exit 1
                fi
            fi
            WEB_DEPLOY_OUTCOME=no-op
            rm -f "$TMP_TAR" "$TMP_INDEX"
            log "✅ 无需重新部署：远端 archive/index 指纹一致且 HTTP 200"
            log "   archive SHA-256: $WEB_RELEASE_ARCHIVE_SHA256"
            exit 0
        fi
        log "⚠️  远端 archive 指纹相同但 index/HTTP 验证失败，继续原子重部署"
    fi
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
scp -q "$(ssh_local_path "$TMP_TAR")" "$GATEWAY:$REMOTE_TAR"

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

rm -f "$TMP_TAR" "$TMP_INDEX"

# ==================== 5. 验证 ====================
log "🔍 验证..."
if [ "$ENV" = "test" ] || [ "$ENV" = "all" ]; then
    VERIFY_URL="http://139.196.165.140:8097/"
else
    VERIFY_URL="http://139.196.165.140:8086/"
fi
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$VERIFY_URL" || echo "000")
WEB_HTTP_CODE=$HTTP_CODE
log "   HTTP $HTTP_CODE ($VERIFY_URL)"

if [ "$HTTP_CODE" != "200" ]; then
    log "⚠️  验证失败 (HTTP $HTTP_CODE),请手动检查"
    exit 1
fi
verify_entry_chunk_reachable "$VERIFY_URL"
if [ "$ENV" = "prod" ]; then
    verify_prod_web_four_way
fi

# R43 fix: --env all 之前 silently 只部 test, 现在测试通过后 prompt 部 prod
if [ "$ENV" = "all" ]; then
    log "✅ Test 部署完成"
    echo ""
    echo "⚠️  ⚠️  ⚠️  PROD 部署 (第二阶段)  ⚠️  ⚠️  ⚠️"
    echo "    目标: /www/wwwroot/web-admin (139:8086 / admin.cretaceousfuture.com)"
    echo "    影响: 所有 prod 用户立刻看到新代码"
    echo ""
    # 复用同一已验证 archive + 上传 (test 那次已 rm'd 远端 tar)
    log "📦 [prod 1/3] 复用已验证 tarball..."
    cp "$WEB_ARCHIVE_PATH" "$TMP_TAR"
    cd "$PROJECT_ROOT"
    log "   ✓ Tarball: $(du -h "$TMP_TAR" | awk '{print $1}')"

    log "📤 [prod 2/3] 上传 prod (remote: $REMOTE_TAR)..."
    scp -q "$(ssh_local_path "$TMP_TAR")" "$GATEWAY:$REMOTE_TAR"

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
    WEB_HTTP_CODE=$PROD_CODE
    log "   HTTP $PROD_CODE (http://139.196.165.140:8086/)"
    if [ "$PROD_CODE" = "200" ]; then
        verify_prod_web_four_way
        log "✅ Prod 部署完成"
    else
        log "⚠️  Prod 验证失败 (HTTP $PROD_CODE)"
        exit 1
    fi
fi

WEB_DEPLOY_OUTCOME=deployed
log "✅ 部署完成"
