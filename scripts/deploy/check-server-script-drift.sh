#!/usr/bin/env bash
# 服务器脚本漂移检查 —— 对比服务器实际安装的脚本与仓库版本。
#
# Why: 这个仓库反复出现「文档描述的机制与实际运行的机制脱节, 而且没有任何东西在
# 检查这种脱节」。2026-07-30 一晚在三处独立撞见, 其中最贵的一次:
# 有人在 ECS 上加固了 oss-verify-artifact.sh 的信任模型却从未提交, 另一个 session
# 基于仓库版本改完装上去, 把 deployable_trust_verified 改回了 true —— 等于重新装
# 回一个漏洞, 而跑出来的那个 true 还被当成「链路打通」的证据。
#
# ⚠️ 服务器更严格时以服务器为准: DRIFTED 的修法通常是把服务器加固取回仓库, 而不
# 是用仓库版本覆盖服务器。本工具只报告, 从不写入任何一侧。
#
# 这个工具不省任何部署时间。它要两台跨境 ssh, 故意不挂在发布热路径上 —— 改完任何
# 服务器脚本后手动跑一次即可。
#
# 退出码:
#   0  全部 MATCH / IGNORED
#   1  有 DRIFTED / MISSING_IN_REPO / MISSING_ON_SERVER —— 已知的不一致
#   2  有 UNREADABLE 或用法错误 —— 「查不出来」, 与「查出来是坏的」必须分开
#
# 刻意不用 `set -e`: 这是报告工具, 单个条目失败必须继续检查其余条目并在汇总里
# 明确标 UNREADABLE, 而不是中途退出留下一份看起来干净的残缺报告。
set -uo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
# REPO_ROOT 是从脚本位置推出来的。脚本被复制/软链到别处时它会算错, 那时每一条都会
# 因为"仓库里找不到文件"而静默变成 MISSING_IN_REPO —— 一份看起来言之有据的假报告。
# 这正是本工具存在的理由那类 bug, 它自己不能有。实测: 把副本放 /tmp 跑, 5 条 tracked
# 全变 MISSING_IN_REPO。
if [ ! -d "$REPO_ROOT/scripts/deploy" ] || [ ! -e "$REPO_ROOT/.git" ]; then
    echo "error=repo_root_unresolved root=$REPO_ROOT" >&2
    echo "本脚本必须从仓库内的 scripts/deploy/ 下运行 (不要复制到别处)。" >&2
    exit 2
fi
SHOW_DIFF=0
ONLY_HOST=""

usage() {
    cat >&2 <<'EOF'
usage: check-server-script-drift.sh [--host tokyo|ecs] [--diff]

  --host <name>  只检查单台主机 (tokyo | ecs)
  --diff         对 DRIFTED 条目打印 unified diff (仓库 → 服务器, 已归一化行尾)
EOF
    exit 2
}

while (($#)); do
    case "$1" in
        --host) (($# >= 2)) || usage; ONLY_HOST=$2; shift 2 ;;
        --diff) SHOW_DIFF=1; shift ;;
        -h|--help) usage ;;
        *) echo "unknown argument: $1" >&2; usage ;;
    esac
done

# ==================== 清单 ====================
# 单一来源在 server-script-inventory.conf —— install-server-scripts.sh 读的是同一个文件。
# 两个工具各存一份清单就等于又造了一个漂移源, 而它们存在的理由正是消除漂移。
# 清单单一来源: server-script-inventory.conf。连接/读取/比较的实现单一来源:
# scripts/lib/server-script-common.sh —— install-server-scripts.sh 读的是同两份。
# 两个工具各存一份就等于又造了一个漂移源, 而它们存在的理由正是消除漂移。
# shellcheck source=scripts/lib/server-script-common.sh
. "$REPO_ROOT/scripts/lib/server-script-common.sh"

INVENTORY=$(server_script_load_inventory "$REPO_ROOT/scripts/deploy/server-script-inventory.conf") || exit 2

is_structural_noise() { server_script_is_structural_noise "$@"; }
host_ssh_target()     { server_script_host_target "$@"; }
host_ssh_opts()       { server_script_host_opts "$@"; }
host_needs_sudo()     { server_script_host_needs_sudo "$@"; }
remote_fetch()        { server_script_remote_fetch "$@"; }
norm_sha()            { server_script_norm_sha "$@"; }

WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT

# ==================== 主循环 ====================
declare -a ROWS=()
declare -A INVENTORIED=()
declare -A WATCHED_DIRS=()
n_match=0 n_drift=0 n_missing_repo=0 n_missing_server=0 n_unreadable=0 n_ignored=0

add_row() { ROWS+=("$1|$2|$3|$4"); }

while IFS='|' read -r host remote repo note; do
    [ -n "${host:-}" ] || continue
    [ -z "$ONLY_HOST" ] || [ "$host" = "$ONLY_HOST" ] || continue

    INVENTORIED["$host:$(basename "$remote")"]=1
    WATCHED_DIRS["$host:$(dirname "$remote")"]=1

    if [ "$repo" = "-" ]; then
        add_row "$host" "$(basename "$remote")" IGNORED "$note"
        ((n_ignored++))
        continue
    fi

    body="$WORK_DIR/$host.$(basename "$remote")"
    state=$(remote_fetch "$host" "$remote" "$body")
    repo_file="$REPO_ROOT/$repo"

    case "$state" in
        UNREADABLE:*)
            add_row "$host" "$(basename "$remote")" UNREADABLE "${state#UNREADABLE:} ($(tr -d '\r\n' < "$body.err" 2>/dev/null | cut -c1-80))"
            ((n_unreadable++))
            continue ;;
        ABSENT)
            if [ -f "$repo_file" ]; then
                add_row "$host" "$(basename "$remote")" MISSING_ON_SERVER "仓库有 $repo, 服务器没装"
                ((n_missing_server++))
            else
                add_row "$host" "$(basename "$remote")" UNREADABLE "清单条目两侧都不存在, 清单可能写错"
                ((n_unreadable++))
            fi
            continue ;;
    esac

    if [ ! -f "$repo_file" ]; then
        add_row "$host" "$(basename "$remote")" MISSING_IN_REPO "服务器在跑, 但 $repo 不存在"
        ((n_missing_repo++))
        continue
    fi

    srv_sha=$(norm_sha "$body")
    repo_sha=$(norm_sha "$repo_file")
    crlf_note=""
    if grep -q $'\r' "$body" 2>/dev/null || grep -q $'\r' "$repo_file" 2>/dev/null; then
        crlf_note=" [行尾已归一化后比较]"
    fi

    if [ "$srv_sha" = "$repo_sha" ]; then
        add_row "$host" "$(basename "$remote")" MATCH "${srv_sha:0:12}$crlf_note"
        ((n_match++))
    else
        add_row "$host" "$(basename "$remote")" DRIFTED "仓库 ${repo_sha:0:12} vs 服务器 ${srv_sha:0:12}$crlf_note"
        ((n_drift++))
        if ((SHOW_DIFF)); then
            echo "----- diff: $repo (仓库) → $host:$remote (服务器) -----"
            diff -u --label "repo/$repo" --label "$host:$remote" \
                <(tr -d '\r' < "$repo_file") <(tr -d '\r' < "$body")
            echo "----- end diff -----"
        fi
    fi
done <<< "$INVENTORY"

# ==================== 发现清单外的服务器文件 ====================
# 这一段才是真正抓到"从未提交的加固"的那一步: 只比清单里的条目, 永远发现不了
# github-cache-put 这种压根没进过仓库的脚本。
if ((${#WATCHED_DIRS[@]} == 0)); then
    echo "⚠️  没有任何待检查目录 —— 清单为空或 --host 过滤掉了全部条目。" >&2
    exit 2
fi
for key in "${!WATCHED_DIRS[@]}"; do
    host=${key%%:*}
    dir=${key#*:}
    target=$(host_ssh_target "$host") || continue
    mapfile -t opts < <(host_ssh_opts "$host")
    listing=$(ssh "${opts[@]}" "$target" "ls -1 -- '$dir'" 2> "$WORK_DIR/ls.err")
    if (($? != 0)); then
        add_row "$host" "$dir/" UNREADABLE "无法列目录: $(tr -d '\r\n' < "$WORK_DIR/ls.err" | cut -c1-80)"
        ((n_unreadable++))
        continue
    fi
    while IFS= read -r entry; do
        entry=$(printf '%s' "$entry" | tr -d '\r')
        [ -n "$entry" ] || continue
        is_structural_noise "$entry" && continue
        [ -z "${INVENTORIED[$host:$entry]:-}" ] || continue
        add_row "$host" "$entry" MISSING_IN_REPO "服务器有, 清单未登记 (仓库未跟踪)"
        ((n_missing_repo++))
    done <<< "$listing"
done

# ==================== 汇总 ====================
printf '\n%-6s  %-34s  %-17s  %s\n' HOST SCRIPT STATUS DETAIL
printf '%-6s  %-34s  %-17s  %s\n' '------' '----------------------------------' '-----------------' '------'
for row in "${ROWS[@]}"; do
    IFS='|' read -r h s st d <<< "$row"
    printf '%-6s  %-34s  %-17s  %s\n' "$h" "$s" "$st" "$d"
done

printf '\nMATCH=%s DRIFTED=%s MISSING_IN_REPO=%s MISSING_ON_SERVER=%s UNREADABLE=%s IGNORED=%s\n' \
    "$n_match" "$n_drift" "$n_missing_repo" "$n_missing_server" "$n_unreadable" "$n_ignored"

if ((n_unreadable > 0)); then
    echo "⚠️  有条目查不出结果 —— 这不等于「一致」, 也不等于「漂移」。先修访问再判断。" >&2
    exit 2
fi
if ((n_drift > 0 || n_missing_repo > 0 || n_missing_server > 0)); then
    echo "⚠️  发现不一致。服务器更严格时以服务器为准, 把加固取回仓库, 不要反向覆盖。" >&2
    exit 1
fi
echo "✅ 服务器安装版与仓库版一致。"
