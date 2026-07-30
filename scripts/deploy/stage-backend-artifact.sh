#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
source "$SCRIPT_DIR/release-jar-manifest.sh"
# ssh_local_path: Windows 盘符路径会被 rsync/scp 当成主机名, 传输直接失败。
source "$PROJECT_ROOT/scripts/lib/deploy-common.sh"

SERVER=${CRETAS_BACKEND_SERVER:-root@47.100.235.168}
REMOTE_CACHE_DIR=${CRETAS_REMOTE_JAR_CACHE_DIR:-/www/wwwroot/cretas/release-cache/sha256}
MANIFEST=$(release_manifest_default_path)
CONFIRM=

usage() {
    cat <<'EOF'
Usage: scripts/deploy/stage-backend-artifact.sh --confirm-stage YES-STAGE [--manifest PATH]

Validates a trusted JAR against the clean reviewed build commit, then uploads
it to an immutable server-side SHA-256 cache. This command never installs the
JAR, restarts a service, or changes nginx upstream state.
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --confirm-stage) CONFIRM=${2:-}; shift 2 ;;
        --manifest) MANIFEST=${2:-}; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "ERROR: unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

[ "$CONFIRM" = "YES-STAGE" ] \
    || { echo "ERROR: staging requires --confirm-stage YES-STAGE" >&2; exit 2; }
release_manifest_require_clean_worktree "$PROJECT_ROOT" \
    || { echo "ERROR: staging requires a clean reviewed worktree" >&2; exit 1; }
[ -f "$MANIFEST" ] || { echo "ERROR: release manifest not found: $MANIFEST" >&2; exit 1; }

build_commit=$(release_manifest_field "$MANIFEST" build_commit)
backend_tree=$(release_manifest_field "$MANIFEST" backend_tree)
jar_sha=$(release_manifest_field "$MANIFEST" jar_sha256 | tr '[:upper:]' '[:lower:]')
jar_relative=$(release_manifest_field "$MANIFEST" jar_path)
head_commit=$(git -C "$PROJECT_ROOT" rev-parse HEAD)
head_tree=$(git -C "$PROJECT_ROOT" rev-parse "HEAD:$RELEASE_BACKEND_PATH")

[ "$build_commit" = "$head_commit" ] \
    || { echo "ERROR: manifest build commit is not the current reviewed HEAD" >&2; exit 1; }
[ "$backend_tree" = "$head_tree" ] \
    || { echo "ERROR: manifest backend tree is not the current reviewed backend tree" >&2; exit 1; }
[[ "$jar_sha" =~ ^[0-9a-f]{64}$ ]] || { echo "ERROR: invalid manifest JAR SHA-256" >&2; exit 1; }
[ "$jar_relative" = "$RELEASE_JAR_NAME" ] || { echo "ERROR: unexpected manifest JAR name" >&2; exit 1; }

manifest_dir=$(cd "$(dirname "$MANIFEST")" && pwd)
jar_path="$manifest_dir/$jar_relative"
release_manifest_verify_jar "$jar_path" || { echo "ERROR: JAR integrity check failed" >&2; exit 1; }
actual_sha=$(sha256sum "$jar_path" | awk '{print tolower($1)}')
[ "$actual_sha" = "$jar_sha" ] || { echo "ERROR: JAR SHA-256 does not match manifest" >&2; exit 1; }

remote_path="$REMOTE_CACHE_DIR/$jar_sha.jar"
started_at=$(date +%s)
if ssh -o ConnectTimeout=10 "$SERVER" \
    "[ -f '$remote_path' ] && [ \"\$(sha256sum '$remote_path' | awk '{print \$1}')\" = '$jar_sha' ]"; then
    echo "Backend artifact already staged: SHA-256=$jar_sha elapsed=$(( $(date +%s) - started_at ))s"
    exit 0
fi

remote_tmp="$REMOTE_CACHE_DIR/.${jar_sha}.$$"

# rsync 走 SSH 时 delta 算法默认开启, 但它只有在【目标端已存在同名文件】时才有基准
# 可比。这里的目标名同时含 $jar_sha (每次构建必变) 和 $$ (PID 必变), 而且成功后立刻
# `mv` 走 —— 基准 100% 不存在, 每次 stage 都是全量 168MB。
#
# 而 Spring Boot fat jar 的内嵌依赖 jar 是 STORED 未压缩存放的, 两次构建之间大部分
# 逐字节相同。2026-07-29 在服务器上对两次连续发布的 jar 实测:
#     Total file size  176,280,322 bytes
#     Literal data      21,396,082 bytes  (真正需要传的, ~20MB)
#     Matched data     154,884,240 bytes  (可从基准复用的, ~148MB)
#     speedup 8.91×
# $REMOTE_CACHE_DIR 里本来就躺满了历史 jar (deploy-backend.sh 种基准的同一个来源),
# 之前完全没用上。上传前用其中最近一次的 jar 把目标位置种一个基准即可。
#
# 为什么就地实现而不抽到 scripts/lib/deploy-common.sh: deploy-backend.sh 那份把基准
# 种到【另一个目录】($REMOTE_TMP/<name>, 源是 cache dir), 这里是在【同一个 cache dir】
# 内种; 抽公共函数得同时参数化源目录/目标全路径, 而在 deploy-backend.sh 不动的前提下
# 公共函数只有一个调用方 —— 属于投机抽象。deploy-common.sh 又被 mall/embedding/
# smartbi/web-admin 多个部署脚本 source, 往里加东西只是白白放大并发改动的冲突面。
# 若日后 deploy-backend.sh 有机会一并重构, 两处再收敛到 deploy-common.sh。
#
# 安全性: 种子失败、缓存为空、ssh 不通时静默退回全量传输, 绝不让 stage 失败 —— 基准
# 是脏的只会让 delta 效率变差, 不会传出错误字节 (rsync 自带 checksum, 之后还有
# SHA-256 + unzip -tqq 校验)。
seed_rsync_delta_basis() {
    local target=$1
    ssh -o ConnectTimeout=10 "$SERVER" "
        newest=\$(ls -t '$REMOTE_CACHE_DIR'/*.jar 2>/dev/null | head -1)
        [ -n \"\$newest\" ] || exit 0
        cp -f \"\$newest\" '$target' 2>/dev/null || true
    " 2>/dev/null || true
}

# 把 --stats 里的 Literal/Matched/speedup 提出来打进 stage 的输出。没有它就只能看到
# "elapsed Xs", 无法区分【种子没种上→全量重传】和【种子生效但链路本身慢】——
# 2026-07-29 就是靠手工在服务器上跑一次 rsync --stats 才确认 delta 兑现, 这种事不该
# 每次都手工做。
report_rsync_delta() {
    local stats_log=$1 literal matched speedup
    [ -f "$stats_log" ] || return 0
    literal=$(sed -nE 's/^Literal data: ([0-9,]+) bytes.*/\1/p' "$stats_log" | head -1)
    matched=$(sed -nE 's/^Matched data: ([0-9,]+) bytes.*/\1/p' "$stats_log" | head -1)
    speedup=$(sed -nE 's/.*speedup is ([0-9.]+).*/\1/p' "$stats_log" | head -1)
    [ -n "$literal" ] || return 0
    echo "rsync delta: literal=${literal//,/} bytes matched=${matched//,/} bytes speedup=${speedup:-?}x"
}

stats_log=$(mktemp)
trap 'rm -f "$stats_log"' EXIT

ssh -o ConnectTimeout=10 "$SERVER" "mkdir -p '$REMOTE_CACHE_DIR' && chmod 700 '$REMOTE_CACHE_DIR'"
seed_rsync_delta_basis "$remote_tmp"
# -z: 与 deploy-backend.sh 主通道 (`rsync -az --stats`) 一致 —— 同一条链路、同一种
# 文件、已在 prod 跑过。实测链路 4.00 MB/s, 压缩的 CPU 成本远低于省下的传输时间;
# 种子失败退回全量时收益最大。
# --stats: 没有它就无法验证 delta 是否真的生效, 见 report_rsync_delta。
if ! rsync -az --stats --timeout=90 "$(ssh_local_path "$jar_path")" "$SERVER:$remote_tmp" \
    > "$stats_log"; then
    # 种过基准后失败会在缓存目录里留下一个整份 168MB 的残留 (文件名是点开头且不以
    # .jar 结尾, 不会污染下次选基准, 但仍是磁盘泄漏), 清掉。
    ssh -o ConnectTimeout=10 "$SERVER" "rm -f '$remote_tmp'" 2>/dev/null || true
    echo "ERROR: rsync upload failed" >&2
    exit 1
fi
report_rsync_delta "$stats_log"

ssh -o ConnectTimeout=10 "$SERVER" "
    set -eu
    actual=\$(sha256sum '$remote_tmp' | awk '{print \$1}')
    [ \"\$actual\" = '$jar_sha' ]
    unzip -tqq '$remote_tmp'
    chmod 0444 '$remote_tmp'
    mv -f '$remote_tmp' '$remote_path'
"

echo "Backend artifact staged without deployment: SHA-256=$jar_sha elapsed=$(( $(date +%s) - started_at ))s path=$remote_path"
