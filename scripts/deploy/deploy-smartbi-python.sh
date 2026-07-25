#!/bin/bash
# Python Services 部署脚本 (SmartBI + 其他模块)
# 部署到阿里云服务器
#
# 用法:
#   ./deploy-smartbi-python.sh              # 部署代码，重启生产 Python (8083)
#   ./deploy-smartbi-python.sh --env test   # 部署代码，重启测试 Python (8084)
#   ./deploy-smartbi-python.sh --env all    # 部署代码，重启两套 Python
#   ./deploy-smartbi-python.sh --env prod --migration-target V20261028_04
#   ./deploy-smartbi-python.sh --env prod --migration-only --migration-target V20261028_04

set -eo pipefail

# 加载共享函数库 (Apr 22 2026 fix: was looking for `$SCRIPT_DIR/scripts/lib/...`
# which gave `scripts/deploy/scripts/lib/...` — doesn't exist. Use PROJECT_ROOT
# pattern matching deploy-backend.sh so wait_for_health is available in both.)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
if [ -f "$PROJECT_ROOT/scripts/lib/deploy-common.sh" ]; then
    source "$PROJECT_ROOT/scripts/lib/deploy-common.sh"
else
    echo "警告: 未找到 $PROJECT_ROOT/scripts/lib/deploy-common.sh，使用内联函数"
    log() { echo "[$(date '+%Y-%m-%dT%H:%M:%S')] [$1] ${*:2}"; }
    # Minimal fallback so downstream wait_for_health_via_ssh calls don't crash deploy.
    # 镜像 common 版签名 <ssh_target> <port> <path> <retries> <interval>: SSH 进服务器
    # 本机 curl localhost:<port><path> (绕 SG). (改动1 后实际调用的是 _via_ssh, 此 fallback
    # 原本错误地定义 wait_for_health 导致 common 丢时仍崩 — 修正为匹配的函数名/签名.)
    wait_for_health_via_ssh() {
        local ssh_target="$1" port="$2" path="${3:-/health}" retries="${4:-15}" interval="${5:-2}"
        local i
        for ((i=0; i<retries; i++)); do
            if ssh -o ConnectTimeout=3 "$ssh_target" "curl -fsS -m 3 http://localhost:${port}${path} >/dev/null 2>&1"; then return 0; fi
            sleep "$interval"
        done
        return 1
    }
fi

# 配置
SERVER="root@47.100.235.168"
REMOTE_DIR="/www/wwwroot/cretas/code/backend/python"
REMOTE_CRETAS_DIR="/www/wwwroot/cretas"
LOCAL_DIR="backend/python"
SERVER_IP="${SERVER#*@}"
PYTHON_RUNTIME_BIN="${CRETAS_PYTHON_RUNTIME_BIN:-python3.11}"
PYTHON_VENV_NAME="${CRETAS_PYTHON_VENV_NAME:-venv311}"
PYPI_INDEX_URL="${CRETAS_PYPI_INDEX_URL:-https://mirrors.aliyun.com/pypi/simple}"

if [[ "$PYTHON_RUNTIME_BIN" != "python3.11" ]]; then
    echo "错误: CRETAS_PYTHON_RUNTIME_BIN 必须是 python3.11"
    exit 1
fi
if [[ ! "$PYTHON_VENV_NAME" =~ ^venv[0-9]+$ ]]; then
    echo "错误: CRETAS_PYTHON_VENV_NAME 必须匹配 venv<数字>"
    exit 1
fi
if [[ "$PYPI_INDEX_URL" != "https://mirrors.aliyun.com/pypi/simple" &&
      "$PYPI_INDEX_URL" != "https://pypi.org/simple" ]]; then
    echo "错误: CRETAS_PYPI_INDEX_URL 必须是批准的 HTTPS PyPI 索引"
    exit 1
fi

# 参数解析
DEPLOY_ENV="prod"
MIGRATION_TARGET=""
MIGRATION_ONLY="0"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --env)
            DEPLOY_ENV="$2"
            if [[ ! "$DEPLOY_ENV" =~ ^(prod|test|all)$ ]]; then
                echo "错误: --env 参数必须是 prod, test, 或 all"
                exit 1
            fi
            shift 2
            ;;
        --migration-only)
            MIGRATION_ONLY="1"
            shift
            ;;
        --migration-target)
            if [[ $# -lt 2 || -z "${2:-}" ]]; then
                echo "错误: --migration-target 需要 VERSION 参数"
                exit 1
            fi
            MIGRATION_TARGET="$2"
            if [[ ! "$MIGRATION_TARGET" =~ ^V[0-9]{8}_[0-9]{2}$ ]]; then
                echo "错误: --migration-target 必须匹配 VYYYYMMDD_NN，例如 V20261028_04"
                exit 1
            fi
            shift 2
            ;;
        -h|--help)
            echo "用法: ./deploy-smartbi-python.sh [选项]"
            echo ""
            echo "选项:"
            echo "  --env ENV                  部署环境: prod (默认), test, all"
            echo "  --migration-target VERSION 仅执行版本 <= VERSION 的 migration"
            echo "  --migration-only           只同步/执行 migration，不同步应用代码或重启"
            echo "  -h, --help                 显示帮助"
            echo ""
            echo "环境说明:"
            echo "  prod   重启生产 Python (端口 8083, 数据库 smartbi_prod_db)"
            echo "  test   重启测试 Python (端口 8084, 数据库 smartbi_db)"
            echo "  all    重启两套 Python 服务"
            exit 0
            ;;
        *)
            echo "错误: 未知参数 $1"
            exit 1
            ;;
    esac
done

if [[ "${SKIP_MIGRATIONS:-0}" == "1" && -n "$MIGRATION_TARGET" ]]; then
    echo "错误: SKIP_MIGRATIONS=1 与 --migration-target 不能同时使用"
    exit 1
fi
if [[ -n "$MIGRATION_TARGET" && "$DEPLOY_ENV" == "all" ]]; then
    echo "错误: --migration-target 不支持 --env all；请逐环境迁移、重启并验证"
    exit 1
fi
if [[ "$MIGRATION_ONLY" == "1" && -z "$MIGRATION_TARGET" ]]; then
    echo "错误: --migration-only 必须同时提供 --migration-target"
    exit 1
fi
if [[ -n "$MIGRATION_TARGET" ]]; then
    TARGET_GLOB="$PROJECT_ROOT/backend/python/smartbi/database/migrations/${MIGRATION_TARGET}__*.sql"
    if ! compgen -G "$TARGET_GLOB" >/dev/null; then
        echo "错误: migration target 在当前 exact main 中不存在: $MIGRATION_TARGET"
        exit 1
    fi
fi

echo "=========================================="
echo "Python Services 部署 (SmartBI + Modules)"
echo "部署环境: $DEPLOY_ENV"
if [[ -n "$MIGRATION_TARGET" ]]; then
    echo "Migration target: $MIGRATION_TARGET"
fi
if [[ "$MIGRATION_ONLY" == "1" ]]; then
    echo "Migration only: true (不发布应用代码、不重启服务)"
fi
echo "=========================================="

# Pre-flight: git sync check (May 11 2026 stale-local-deploy bug fix)
# Stale local working tree → deploy ships stale code → prod gets pre-PR fixes.
# Per HARD rule feedback_organizer_must_git_pull_before_deploy.md.
#
# Jul 7 2026 事故修复: --env 含 prod (prod/all) 时启用 strict 模式 — 非
# origin/main HEAD 或脏工作树直接 ABORT (之前只 WARN).
GIT_SYNC_STRICT=""
if [[ "$DEPLOY_ENV" =~ ^(prod|all)$ ]]; then
    GIT_SYNC_STRICT="1"
fi
check_git_sync "$PROJECT_ROOT" "[0/5] Git sync pre-check..." "$GIT_SYNC_STRICT"

RELEASE_COMMIT="$(git -C "$PROJECT_ROOT" rev-parse HEAD)"
if [[ ! "$RELEASE_COMMIT" =~ ^[0-9a-f]{40}$ ]]; then
    echo "错误: 无法解析 exact release commit"
    exit 1
fi
REMOTE_MIGRATION_BUNDLE="/www/wwwroot/cretas/code/.release-migrations/$RELEASE_COMMIT"
REMOTE_MIGRATION_DIR="$REMOTE_MIGRATION_BUNDLE/sql"
REMOTE_MIGRATION_SCRIPT_DIR="$REMOTE_MIGRATION_BUNDLE/scripts"
REMOTE_SYSTEMD_BUNDLE="/www/wwwroot/cretas/code/.release-systemd/$RELEASE_COMMIT"

prepare_remote_migration_bundle() {
    ssh "$SERVER" bash -s -- "$REMOTE_MIGRATION_BUNDLE" <<'REMOTE_BUNDLE'
set -euo pipefail
root="/www/wwwroot/cretas/code/.release-migrations"
bundle="$1"
if [[ ! "$bundle" =~ ^${root}/[0-9a-f]{40}$ ]]; then
    echo "invalid release migration bundle path" >&2
    exit 1
fi
if [[ "$(realpath -m "$root")" != "$root" ]]; then
    echo "release migration root resolves outside the expected path" >&2
    exit 1
fi
for path in "$root" "$bundle" "$bundle/sql" "$bundle/scripts"; do
    if [[ -L "$path" ]]; then
        echo "release migration path must not be a symlink: $path" >&2
        exit 1
    fi
done
mkdir -p "$bundle/sql" "$bundle/scripts"
if [[ "$(realpath -m "$bundle")" != "$bundle" ]]; then
    echo "release migration bundle resolves outside the expected path" >&2
    exit 1
fi
REMOTE_BUNDLE
}

sync_migration_release_bundle() {
    prepare_remote_migration_bundle
    # The SHA-scoped narrow directories plus --delete make the remote input set
    # byte-for-byte derived from this exact main, never a union with stale SQL.
    rsync -az --delete --timeout=60 \
        "$PROJECT_ROOT/backend/python/smartbi/database/migrations/" \
        "$SERVER:$REMOTE_MIGRATION_DIR/" 2>&1 | tail -5
    rsync -az --delete --timeout=60 \
        "$PROJECT_ROOT/scripts/migrations/" \
        "$SERVER:$REMOTE_MIGRATION_SCRIPT_DIR/" 2>&1 | tail -5
    ssh "$SERVER" "chmod +x \
        '$REMOTE_MIGRATION_SCRIPT_DIR/apply-smartbi-migrations.sh' \
        '$REMOTE_MIGRATION_SCRIPT_DIR/backfill-applied.sh' \
        '$REMOTE_MIGRATION_SCRIPT_DIR/test-runner.sh'"
}

run_smartbi_migrations() {
    local migration_args=()
    if [[ -n "$MIGRATION_TARGET" ]]; then
        migration_args=(--target "$MIGRATION_TARGET")
    fi
    ssh "$SERVER" bash \
        "$REMOTE_MIGRATION_SCRIPT_DIR/apply-smartbi-migrations.sh" \
        --env "$DEPLOY_ENV" --migs-dir "$REMOTE_MIGRATION_DIR" \
        "${migration_args[@]}"
}

if [[ "$MIGRATION_ONLY" == "1" ]]; then
    log "INFO" "[migration-only] 同步 SHA 隔离的 exact-main migration bundle..."
    sync_migration_release_bundle
    if ! run_smartbi_migrations; then
        log "ERROR" "[migration-only] migration FAILED；应用代码与服务进程均未改变"
        exit 1
    fi
    log "INFO" "[migration-only] 完成；未同步应用代码、未安装依赖、未重启服务"
    exit 0
fi

# 1. 检查本地文件
log "INFO" "[1/5] 检查本地文件..."
if [ ! -d "$LOCAL_DIR" ]; then
    log "ERROR" "找不到 $LOCAL_DIR 目录"
    exit 1
fi

# 2. 创建远程目录
log "INFO" "[2/5] 创建远程目录..."
ssh $SERVER "mkdir -p $REMOTE_DIR"

# 3. 同步文件到服务器
log "INFO" "[3/5] 同步文件到服务器 (rsync 增量传输)..."
rsync -az --timeout=120 \
    --exclude='__pycache__' --exclude='*.pyc' --exclude='.env' \
    --exclude='smartbi.log' --exclude='*.xlsx' --exclude='*.png' \
    --exclude='venv*' --exclude='python-services.log' \
    --exclude='python-prod.log' --exclude='python-test.log' \
    $LOCAL_DIR/ $SERVER:$REMOTE_DIR/

# 3b. 同步 ops scripts 到 /www/wwwroot/cretas/scripts/ (task #23 — 2026-05-07).
# Earlier: deploy script only synced backend/python/, leaving t6-dryrun-compare.sh
# and baseline-java-metrics.sh stale on server until manual scp. Now any change
# to scripts/t6-* or scripts/baseline-* gets synced as part of the standard deploy.
log "INFO" "[3b/5] 同步 ops scripts (T6 dryrun + Java baseline)..."
rsync -az --timeout=60 \
    "$PROJECT_ROOT/scripts/t6-dryrun-compare.sh" \
    "$PROJECT_ROOT/scripts/baseline-java-metrics.sh" \
    "$PROJECT_ROOT/scripts/phase2a/t6-in-scope-endpoints.txt" \
    "$SERVER:/www/wwwroot/cretas/scripts/" 2>&1 | tail -5
ssh "$SERVER" "chmod +x /www/wwwroot/cretas/scripts/t6-dryrun-compare.sh /www/wwwroot/cretas/scripts/baseline-java-metrics.sh"

# 3c. 同步 多域学习 毕业 CLI 到 code/scripts/ (2026-06-01 — self-learn promote loop).
# Must land at code/scripts/ (NOT cretas/scripts/) because the CLI resolves
# backend/python via Path(__file__).parents[1] = /www/wwwroot/cretas/code.
# Earlier: scripts/ was never synced, so `python scripts/promote_learnings.py`
# had no file on the server — the promote leg of the loop was un-runnable.
# v2 (2026-06-01): renamed promote_field_mappings.py -> promote_learnings.py
# (generalized multi-domain). Remove the stale old CLI from the server too.
log "INFO" "[3c/5] 同步 多域学习 毕业 CLI..."
ssh "$SERVER" "mkdir -p /www/wwwroot/cretas/code/scripts && rm -f /www/wwwroot/cretas/code/scripts/promote_field_mappings.py"
rsync -az --timeout=60 \
    "$PROJECT_ROOT/scripts/promote_learnings.py" \
    "$SERVER:/www/wwwroot/cretas/code/scripts/" 2>&1 | tail -5

# 3d. Keep active cron consumers on the same atomically selected runtime as
# the production service. Host-local crontab entries continue to own schedule
# and secrets; only the tracked scripts are synchronized.
log "INFO" "[3d/5] 同步 Python 定时任务入口..."
ssh "$SERVER" "mkdir -p /www/wwwroot/cretas/code/scripts/cron"
rsync -az --timeout=60 \
    "$PROJECT_ROOT/scripts/cron/restaurant-ai-eval.sh" \
    "$PROJECT_ROOT/scripts/cron/refresh-demo-rest.sh" \
    "$SERVER:/www/wwwroot/cretas/code/scripts/cron/" 2>&1 | tail -5
ssh "$SERVER" "chmod +x \
    /www/wwwroot/cretas/code/scripts/cron/restaurant-ai-eval.sh \
    /www/wwwroot/cretas/code/scripts/cron/refresh-demo-rest.sh"

# 3.5. Apply pending smartbi migrations (per spec 2026-05-07-smartbi-migration-runner-spec.md).
# Trigger: task #30 — 8 个 data fabric C 系列 migrations 当初部署漏跑 prod, T6.2 4h 才发现.
# Runner consumes smartbi_migrations tracker (PR-A #98) to skip already-applied
# files. On failure: abort BEFORE Python restart so old code stays on old
# schema rather than crash on missing tables.
#
# --migration-target is the supported path for staged expand/contract releases:
# it still verifies the tracker and applied checksums while stopping at the
# inclusive target. SKIP_MIGRATIONS=1 remains an emergency escape hatch only.
log "INFO" "[3.5/5] 应用 smartbi migrations (env=$DEPLOY_ENV)..."
if [[ "${SKIP_MIGRATIONS:-0}" == "1" ]]; then
    log "WARN" "[3.5/5] SKIP_MIGRATIONS=1 — 跳过 migrations apply (escape hatch)"
else
    # Run only from the SHA-isolated exact-main bundle, never the shared code dir.
    sync_migration_release_bundle
    if ! run_smartbi_migrations; then
        log "ERROR" "[3.5/5] migration FAILED — Python service NOT restarted, ABORTING deploy"
        log "ERROR" "       Investigate, then either fix + re-deploy, or set SKIP_MIGRATIONS=1 to bypass"
        exit 1
    fi
fi

# 4. 在服务器上安装依赖
log "INFO" "[4/5] 验证 Python 依赖缓存并按需安装..."
# The quoted heredoc prevents local command substitution. A previous unquoted
# heredoc executed backticks from the cryptography import-smoke comment locally.
ssh "$SERVER" bash -s -- \
    "$REMOTE_DIR" "$PYTHON_RUNTIME_BIN" "$PYTHON_VENV_NAME" "$PYPI_INDEX_URL" <<'ENDSSH'
set -euo pipefail
REMOTE_DIR="$1"
PYTHON_BIN="$2"
VENV_NAME="$3"
PYPI_INDEX_URL="$4"
cd "$REMOTE_DIR"

if [[ "$PYTHON_BIN" != "python3.11" || ! "$VENV_NAME" =~ ^venv[0-9]+$ ]]; then
    echo "[ERROR] unsupported Python runtime contract: $PYTHON_BIN / $VENV_NAME" >&2
    exit 1
fi
if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
    echo "[ERROR] $PYTHON_BIN is unavailable. Install the approved OS package before deployment." >&2
    echo "[ERROR] Existing Python service was not restarted." >&2
    exit 1
fi

echo "使用 Python: $PYTHON_BIN"
"$PYTHON_BIN" --version

VENV_DIR="$REMOTE_DIR/$VENV_NAME"
VENV_PYTHON="$VENV_DIR/bin/python"
# A directory alone is not a trustworthy venv. Rebuild it when its interpreter
# or pip entry point is missing/broken, then take the normal cache-miss path.
if [ ! -x "$VENV_PYTHON" ] || ! "$VENV_PYTHON" -m pip --version >/dev/null 2>&1; then
    echo "[Dependencies] venv missing or invalid - rebuilding with $PYTHON_BIN"
    if [ -e "$VENV_DIR" ] || [ -L "$VENV_DIR" ]; then
        if [ "$(realpath -m "$(dirname "$VENV_DIR")")" != "$(realpath -m "$REMOTE_DIR")" ]; then
            echo "[ERROR] refusing to rebuild venv outside $REMOTE_DIR" >&2
            exit 1
        fi
        rm -rf -- "$VENV_DIR"
    fi
    "$PYTHON_BIN" -m venv "$VENV_DIR"
fi

MANIFEST="$REMOTE_DIR/.deploy-requirements-manifest.$VENV_NAME"
REQUIREMENTS_SHA256="$(sha256sum requirements.txt | awk '{print $1}')"
PYTHON_FINGERPRINT="$($VENV_PYTHON -c 'import platform,sys; print(platform.python_implementation()+"-"+platform.python_version()+"-"+sys.prefix)')"
INSTALLED_SHA256="$($VENV_PYTHON -m pip freeze --all | LC_ALL=C sort | sha256sum | awk '{print $1}')"

manifest_value() {
    sed -n "s/^$1=//p" "$MANIFEST" 2>/dev/null | head -n 1
}

DEPENDENCY_CACHE_HIT=0
DEPENDENCY_CACHE_REASON="manifest missing"
if [ -f "$MANIFEST" ]; then
    if [ "$(manifest_value requirements_sha256)" != "$REQUIREMENTS_SHA256" ]; then
        DEPENDENCY_CACHE_REASON="requirements.txt content changed"
    elif [ "$(manifest_value python_fingerprint)" != "$PYTHON_FINGERPRINT" ]; then
        DEPENDENCY_CACHE_REASON="Python interpreter changed"
    elif [ "$(manifest_value installed_sha256)" != "$INSTALLED_SHA256" ]; then
        DEPENDENCY_CACHE_REASON="venv package state changed"
    elif ! "$VENV_PYTHON" -m pip check >/dev/null 2>&1; then
        DEPENDENCY_CACHE_REASON="pip check failed"
    else
        DEPENDENCY_CACHE_HIT=1
        DEPENDENCY_CACHE_REASON="requirements and verified venv match manifest"
    fi
fi

if [ "$DEPENDENCY_CACHE_HIT" = "1" ]; then
    echo "[Dependencies] cache hit - skipping pip install ($DEPENDENCY_CACHE_REASON)"
else
    echo "[Dependencies] cache miss - running pip install ($DEPENDENCY_CACHE_REASON)"
    "$VENV_PYTHON" -m pip install --no-cache-dir \
        --index-url "$PYPI_INDEX_URL" --upgrade pip
    "$VENV_PYTHON" -m pip install --no-cache-dir \
        --index-url "$PYPI_INDEX_URL" -r requirements.txt
    "$VENV_PYTHON" -m pip check
    INSTALLED_SHA256="$($VENV_PYTHON -m pip freeze --all | LC_ALL=C sort | sha256sum | awk '{print $1}')"
fi

# Import smoke test — catch missing deps BEFORE we restart the service.
# History: 2026-05-12 P0 outage when ota.services.signing added a new
# `cryptography` dependency that wasn't in requirements.txt. pip install
# succeeded silently, then uvicorn workers crashed on first import after
# restart, Python service DOWN ~5 min until manual hot-fix. This gate
# makes the deploy ABORT here instead of restarting a broken process.
echo
echo "[Import smoke] checking that main.py imports cleanly..."
if ! "$VENV_PYTHON" -c 'import sys; sys.path.insert(0, "."); import main' 2>&1; then
    echo "[ERROR] main.py import smoke FAILED — aborting deploy BEFORE restart"
    echo "[ERROR] Service is still on the OLD code; fix requirements.txt or imports, then re-deploy"
    exit 1
fi
echo "[Import smoke] OK — main.py + all routers import successfully"

echo "[Torch smoke] checking CPU-only inference runtime..."
if ! "$VENV_PYTHON" -c 'import torch; assert torch.__version__.startswith("2.4.1+cpu"), torch.__version__; assert not torch.cuda.is_available(); assert torch.tensor([1.0, 2.0]).sum().item() == 3.0' 2>&1; then
    echo "[ERROR] CPU-only Torch smoke FAILED — aborting deploy BEFORE restart" >&2
    exit 1
fi
echo "[Torch smoke] OK — Torch 2.4.1 CPU inference is available"

if [ "$DEPENDENCY_CACHE_HIT" != "1" ]; then
    MANIFEST_TMP="${MANIFEST}.tmp.$$"
    {
        printf 'requirements_sha256=%s\n' "$REQUIREMENTS_SHA256"
        printf 'python_fingerprint=%s\n' "$PYTHON_FINGERPRINT"
        printf 'installed_sha256=%s\n' "$INSTALLED_SHA256"
        printf 'runtime_bin=%s\n' "$PYTHON_BIN"
        printf 'venv_name=%s\n' "$VENV_NAME"
    } > "$MANIFEST_TMP"
    mv -f "$MANIFEST_TMP" "$MANIFEST"
    echo "[Dependencies] verified manifest updated: $MANIFEST"
fi
ENDSSH

capture_prod_runtime_target() {
    ssh "$SERVER" bash -s -- "$REMOTE_DIR" <<'RUNTIME_CAPTURE'
set -euo pipefail
remote_dir="$1"
current_link="$remote_dir/venv-current"
if [ -L "$current_link" ]; then
    target="$(readlink -f "$current_link")"
else
    exec_start="$(systemctl show cretas-python -p ExecStart --value)"
    runtime_python="$(printf '%s\n' "$exec_start" | grep -oE "${remote_dir}/venv[[:alnum:]_-]+/bin/python" | head -n 1 || true)"
    if [ -z "$runtime_python" ]; then
        echo "[ERROR] unable to determine current production Python runtime" >&2
        exit 1
    fi
    target="$(dirname "$(dirname "$runtime_python")")"
fi
if [[ ! "$target" =~ ^${remote_dir}/venv[0-9]+$ ]] || [ ! -x "$target/bin/python" ]; then
    echo "[ERROR] refusing unsafe or invalid runtime target: $target" >&2
    exit 1
fi
printf '%s\n' "$target"
RUNTIME_CAPTURE
}

sync_python_runtime_units() {
    log "INFO" "[4.25/5] 同步 Python 3.11 systemd runtime 契约..."
    ssh "$SERVER" bash -s -- "$REMOTE_SYSTEMD_BUNDLE" <<'RUNTIME_STAGE'
set -euo pipefail
root="/www/wwwroot/cretas/code/.release-systemd"
bundle="$1"
if [[ ! "$bundle" =~ ^${root}/[0-9a-f]{40}$ ]]; then
    echo "[ERROR] invalid systemd release bundle path" >&2
    exit 1
fi
if [ -L "$root" ] || [ -L "$bundle" ]; then
    echo "[ERROR] systemd release bundle must not be a symlink" >&2
    exit 1
fi
mkdir -p \
    "$bundle/cretas-gold-etl-refresh.service.d" \
    "$bundle/cretas-corpus-refresh.service.d"
if [ "$(realpath -m "$bundle")" != "$bundle" ]; then
    echo "[ERROR] systemd release bundle resolves outside expected path" >&2
    exit 1
fi
RUNTIME_STAGE

    rsync -az --timeout=60 \
        "$PROJECT_ROOT/scripts/systemd/cretas-python.service" \
        "$SERVER:$REMOTE_SYSTEMD_BUNDLE/cretas-python.service"
    rsync -az --timeout=60 \
        "$PROJECT_ROOT/scripts/systemd/cretas-gold-etl-refresh.service.d/python-runtime.conf" \
        "$SERVER:$REMOTE_SYSTEMD_BUNDLE/cretas-gold-etl-refresh.service.d/python-runtime.conf"
    rsync -az --timeout=60 \
        "$PROJECT_ROOT/scripts/systemd/cretas-corpus-refresh.service.d/python-runtime.conf" \
        "$SERVER:$REMOTE_SYSTEMD_BUNDLE/cretas-corpus-refresh.service.d/python-runtime.conf"

    ssh "$SERVER" bash -s -- "$REMOTE_SYSTEMD_BUNDLE" <<'RUNTIME_INSTALL'
set -euo pipefail
bundle="$1"
timestamp="$(date +%Y%m%d_%H%M%S)"

install_if_changed() {
    src="$1"
    dest="$2"
    if [ -f "$dest" ] && cmp -s "$src" "$dest"; then
        return
    fi
    if [ -f "$dest" ]; then
        cp -a "$dest" "${dest}.bak.python-runtime.${timestamp}"
    fi
    install -D -m 0644 "$src" "$dest"
}

systemd-analyze verify "$bundle/cretas-python.service"
install_if_changed \
    "$bundle/cretas-python.service" \
    /etc/systemd/system/cretas-python.service
install_if_changed \
    "$bundle/cretas-gold-etl-refresh.service.d/python-runtime.conf" \
    /etc/systemd/system/cretas-gold-etl-refresh.service.d/python-runtime.conf
install_if_changed \
    "$bundle/cretas-corpus-refresh.service.d/python-runtime.conf" \
    /etc/systemd/system/cretas-corpus-refresh.service.d/python-runtime.conf

systemctl daemon-reload
for unit in cretas-python cretas-gold-etl-refresh cretas-corpus-refresh; do
    if ! systemctl show "$unit" -p ExecStart --value | grep -Fq '/venv-current/bin/python'; then
        echo "[ERROR] $unit did not load the venv-current runtime contract" >&2
        exit 1
    fi
done
RUNTIME_INSTALL
}

set_runtime_link() {
    local target="$1"
    ssh "$SERVER" bash -s -- "$REMOTE_DIR" "$target" <<'RUNTIME_SWITCH'
set -euo pipefail
remote_dir="$1"
target="$2"
if [[ ! "$target" =~ ^${remote_dir}/venv[0-9]+$ ]] || [ ! -x "$target/bin/python" ]; then
    echo "[ERROR] refusing invalid runtime switch target: $target" >&2
    exit 1
fi
link="$remote_dir/venv-current"
if [ -e "$link" ] && [ ! -L "$link" ]; then
    echo "[ERROR] runtime selector exists but is not a symlink: $link" >&2
    exit 1
fi
tmp_link="$remote_dir/.venv-current.$$"
trap 'rm -f "$tmp_link"' EXIT
ln -s "$target" "$tmp_link"
mv -Tf "$tmp_link" "$link"
if [ "$(readlink -f "$link")" != "$target" ]; then
    echo "[ERROR] runtime selector verification failed" >&2
    exit 1
fi
RUNTIME_SWITCH
}

PREVIOUS_RUNTIME_TARGET="$(capture_prod_runtime_target)"
NEW_RUNTIME_TARGET="$REMOTE_DIR/$PYTHON_VENV_NAME"

if [[ "$DEPLOY_ENV" == "prod" || "$DEPLOY_ENV" == "all" ]]; then
    # Seed the selector with the currently running venv so systemd verification
    # and any interrupted pre-switch deploy remain on the known-good runtime.
    set_runtime_link "$PREVIOUS_RUNTIME_TARGET"
    sync_python_runtime_units
fi
set_runtime_link "$NEW_RUNTIME_TARGET"

# 重启对应环境的 Python 服务。生产失败时先把原子链接切回旧 venv，再恢复服务。
log "INFO" "[4.5/5] 重启 Python 服务 (环境: $DEPLOY_ENV)..."

restart_prod_via_systemd() {
    ssh "$SERVER" "
        set -e
        systemctl restart cretas-python
        systemctl is-active --quiet cretas-python
        echo 'Production Python restarted (systemd)'
    "
}

rollback_prod_runtime() {
    log "WARN" "生产 Python 3.11 验证失败，切回 $PREVIOUS_RUNTIME_TARGET"
    set_runtime_link "$PREVIOUS_RUNTIME_TARGET"
    if restart_prod_via_systemd && wait_for_health_via_ssh "$SERVER" 8083 /health 15 2; then
        log "WARN" "生产 Python 已恢复到旧运行时: $PREVIOUS_RUNTIME_TARGET"
        return 0
    fi
    log "ERROR" "生产 Python 自动回滚失败，需要立即人工处理"
    return 1
}

restart_test_via_nohup() {
    # Test credentials are host-local and must never be embedded in this script.
    ssh "$SERVER" "
        set -euo pipefail
        ENV_FILE=$REMOTE_CRETAS_DIR/.env.test
        if [ ! -r \"\$ENV_FILE\" ]; then
            echo 'ERROR: missing readable /www/wwwroot/cretas/.env.test' >&2
            exit 1
        fi
        set -a
        . \"\$ENV_FILE\"
        set +a
        for required in POSTGRES_PASSWORD FOOD_KB_POSTGRES_PASSWORD INTERNAL_API_SECRET JWT_SECRET LLM_API_KEY; do
            if [ -z \"\${!required:-}\" ]; then
                echo \"ERROR: required test secret is unset: \$required\" >&2
                exit 1
            fi
        done
        PID_PY=\$(lsof -ti :8084 2>/dev/null || true)
        if [ -n \"\$PID_PY\" ]; then kill \$PID_PY 2>/dev/null; sleep 2; fi
        cd $REMOTE_CRETAS_DIR/code/backend/python
        POSTGRES_DB=smartbi_db \
        POSTGRES_ENABLED=true \
        POSTGRES_HOST=localhost POSTGRES_PORT=5432 POSTGRES_USER=smartbi_user \
        FOOD_KB_POSTGRES_DB=cretas_db \
        FOOD_KB_POSTGRES_USER=cretas_user \
        FOOD_KB_POSTGRES_HOST=localhost FOOD_KB_POSTGRES_PORT=5432 \
        LLM_MODEL=qwen3.7-max-2026-06-08 LLM_FAST_MODEL=qwen3.5-flash \
        LLM_REASONING_MODEL=qwen3.5-flash LLM_VL_MODEL=qwen3-vl-plus-2025-12-19 \
        nohup $REMOTE_CRETAS_DIR/code/backend/python/venv-current/bin/python \
            -m uvicorn main:app --host 0.0.0.0 --port 8084 \
            > $REMOTE_CRETAS_DIR/python-test.log 2>&1 &
        echo 'Test Python restarted (nohup)'
    "
}
case "$DEPLOY_ENV" in
    prod)
        if ! restart_prod_via_systemd; then
            rollback_prod_runtime || true
            exit 1
        fi
        ;;
    test)
        restart_test_via_nohup
        ;;
    all)
        if ! restart_prod_via_systemd; then
            rollback_prod_runtime || true
            exit 1
        fi
        restart_test_via_nohup
        ;;
esac

verify_prod_runtime_contract() {
    ssh "$SERVER" bash -s -- "$REMOTE_DIR" "$NEW_RUNTIME_TARGET" "$PREVIOUS_RUNTIME_TARGET" <<'RUNTIME_VERIFY'
set -euo pipefail
remote_dir="$1"
expected_target="$2"
rollback_target="$3"
current_link="$remote_dir/venv-current"

[ "$(readlink -f "$current_link")" = "$expected_target" ]
"$current_link/bin/python" -c 'import sys, torch; assert sys.version_info[:2] == (3, 11); assert torch.__version__.startswith("2.4.1+cpu")'
[ -x "$rollback_target/bin/python" ]
systemctl is-active --quiet cretas-python
[ "$(systemctl show cretas-python -p NRestarts --value)" = "0" ]

pid="$(systemctl show cretas-python -p MainPID --value)"
tr '\0' ' ' <"/proc/$pid/cmdline" | grep -Fq '/venv-current/bin/python'
for unit in cretas-python cretas-gold-etl-refresh cretas-corpus-refresh; do
    systemctl show "$unit" -p ExecStart --value | grep -Fq '/venv-current/bin/python'
done
grep -Fq 'venv-current/bin/activate' \
    /www/wwwroot/cretas/code/scripts/cron/restaurant-ai-eval.sh
grep -Fq 'venv-current/bin/activate' \
    /www/wwwroot/cretas/code/scripts/cron/refresh-demo-rest.sh
RUNTIME_VERIFY
}

# 健康检查走 deploy-common.sh 的 wait_for_health_via_ssh: SSH 进 47 本机 curl
# localhost:<port>/health, 绕过 SG Phase 3 对 47:8083/8084 的 nginx-139-only 限制
# (本地开发机直连拿 HTTP 000 会误判服务挂). 签名: <ssh_target> <port> <path> <retries> <interval>

# 5. 验证服务
log "INFO" "[5/5] 验证服务..."
sleep 3

if [[ "$DEPLOY_ENV" == "prod" || "$DEPLOY_ENV" == "all" ]]; then
    if wait_for_health_via_ssh "$SERVER" 8083 /health 15 2; then
        if verify_prod_runtime_contract; then
            log "INFO" "[生产] Python 3.11 服务 (8083) 与回滚契约验证成功"
        else
            log "ERROR" "[生产] 健康接口成功，但 Python 3.11 runtime 契约验证失败"
            rollback_prod_runtime || true
            exit 1
        fi
    else
        log "ERROR" "[生产] 健康检查超时，开始自动回滚"
        rollback_prod_runtime || true
        exit 1
    fi
fi

if [[ "$DEPLOY_ENV" == "test" || "$DEPLOY_ENV" == "all" ]]; then
    if wait_for_health_via_ssh "$SERVER" 8084 /health 15 2; then
        log "INFO" "[测试] Python 服务 (8084) 部署成功"
    else
        log "ERROR" "[测试] 健康检查超时，请检查: ssh $SERVER 'tail -50 $REMOTE_CRETAS_DIR/python-test.log'"
        exit 1
    fi
fi

echo ""
echo "=========================================="
echo "部署完成! (环境: $DEPLOY_ENV)"
if [[ "$DEPLOY_ENV" == "prod" || "$DEPLOY_ENV" == "all" ]]; then
    echo "生产: http://${SERVER_IP}:8083/health"
fi
if [[ "$DEPLOY_ENV" == "test" || "$DEPLOY_ENV" == "all" ]]; then
    echo "测试: http://${SERVER_IP}:8084/health"
fi
echo "=========================================="
