#!/usr/bin/env bash
# 合并到 main 之后跑一次, 把 CI 制品那趟跨境运输挪出发布窗口。
#
# 为什么需要它: `--prefer-ci-artifact` 现在默认开, 发布时会自己去取 CI 制品 —— 但那趟运输
# (GitHub → 东京 → OSS → ECS, 实测 61s)是【在发布那一刻才发起的】。制品其实在 push 后几分钟
# 就躺在 GitHub 上了。先跑一次预热, 发布时 release-ci-artifact.sh 直接短路(实测 2.6s)。
#
# 幂等: 已经预热过就 ~3s 返回, 反复跑没有副作用。
# 有界等待: CI 构建 Java 制品约 4 分钟, 所以合并后【立刻】跑通常是 pending。--wait 让它
# 有界轮询到制品出现为止, 适合合并完丢后台不管。
#
# ⚠️ 必须在一个 clean 且恰好等于 origin/main 的检出点上跑 —— 与 release-ci-artifact.sh 同一把
# 闸(prod 只从 main 部署)。在候选分支上跑会直接被拒, 那是对的。
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)

TESTS=""
WAIT_SECONDS=0
POLL_INTERVAL=${CRETAS_PREWARM_POLL_INTERVAL:-30}

usage() {
    cat >&2 <<'EOF'
usage: prewarm-main-artifact.sh --tests <MavenTestSelector> [--wait <seconds>]

  --tests   与发布时会用的选择器一致或更窄。判据是【集合包含】: 要求 ⊆ CI 跑过的那组,
            所以传一个具体类名即可, 不必与发布时逐字相同。
  --wait    制品还没出现时最多等多少秒(默认 0 = 不等, 直接报 pending 退 0)。
            CI 构建 Java 制品约 4 分钟, 合并后立刻跑建议 --wait 420。

退出码: 0 = 已预热 / 仍 pending(都不算失败, 这是纯优化)；非 0 = 真出错(验签不过、
传输失败、选择器覆盖不足等), 那些【不】因为是预热就放宽。
EOF
    exit 2
}

while (($#)); do
    case "$1" in
        --tests) (($# >= 2)) || usage; TESTS=$2; shift 2 ;;
        --wait) (($# >= 2)) || usage; WAIT_SECONDS=$2; shift 2 ;;
        -h|--help) usage ;;
        *) echo "ERROR: unknown argument: $1" >&2; usage ;;
    esac
done

[ -n "$TESTS" ] || { echo "ERROR: --tests 不能为空" >&2; usage; }
case "$WAIT_SECONDS" in ''|*[!0123456789]*) echo "ERROR: --wait 必须是非负整数" >&2; exit 2 ;; esac

git -C "$PROJECT_ROOT" fetch -q origin main || {
    echo "ERROR: git fetch origin main 失败" >&2; exit 1; }

log=$(mktemp)
trap 'rm -f "$log"' EXIT

deadline=$(( $(date +%s) + WAIT_SECONDS ))
attempt=0
while :; do
    attempt=$((attempt + 1))
    # --prewarm 的语义: 「还没有匹配制品」退 0 并打 pending; 其它任何原因仍是硬失败。
    # 所以这里【不能】把非 0 当成"还没好"吞掉 —— 那会把验签失败伪装成正常等待。
    if ! "$SCRIPT_DIR/release-ci-artifact.sh" --prewarm --tests "$TESTS" >"$log" 2>&1; then
        cat "$log" >&2
        echo "PREWARM=failed attempt=$attempt" >&2
        exit 1
    fi
    cat "$log"

    if grep -Fq 'CI_ARTIFACT_READY' "$log"; then
        if grep -Fq 'CI_ARTIFACT_PREWARM_HIT' "$log"; then
            echo "PREWARM=already-warm attempts=$attempt"
        else
            echo "PREWARM=done attempts=$attempt"
        fi
        exit 0
    fi

    remaining=$(( deadline - $(date +%s) ))
    if [ "$remaining" -le 0 ]; then
        # 仍然 pending 不算失败: 发布时 --prefer-ci-artifact 会自己再试一次, 大不了
        # 当场付那 61s 运输, 或者回退本地构建。预热只是把它提前, 提前不成不该阻断任何事。
        echo "PREWARM=pending attempts=$attempt (CI 尚未产出匹配制品; 发布时会自行重试)"
        exit 0
    fi
    sleep "$(( remaining < POLL_INTERVAL ? remaining : POLL_INTERVAL ))"
done
