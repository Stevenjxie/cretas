#!/usr/bin/env bash
# 把仓库里的服务器脚本安装到东京 / 上海 ECS。
#
# Why: 在这之前根本没有可重复的安装路径 —— 每次都是手敲 scp + cp -p 做备份。ECS 上那
# 5 个 `.bak.<时间戳>` 就是这么来的。**那正是漂移发生的机制本身**: 没有工具, 就没有一致的
# 备份、没有一致的权限、也没有"装完确认它真的落地了"这一步。
#
# ⛔ 最重要的一道闸: 【拒绝覆盖 DRIFTED 的文件】。
#
# 2026-07-30 事故: 有人在 ECS 上加固了 oss-verify-artifact.sh 的信任模型却从未提交, 另一个
# session 基于仓库版本改完装上去, 把 deployable_trust_verified 改回了 true —— 等于重新装回
# 一个漏洞, 而跑出来的那个 true 还被当成"链路打通"的证据。
#
# 服务器上有而仓库没有的内容, 默认假定是【仓库落后】而不是【服务器脏】。要覆盖必须显式
# --accept-overwrite-drift, 而正确做法通常是先把服务器加固取回仓库。
#
# 退出码: 0 全部达成 / 1 有条目被拒或安装失败 / 2 用法错、清单错、访问不到
set -uo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
if [ ! -d "$REPO_ROOT/scripts/deploy" ] || [ ! -e "$REPO_ROOT/.git" ]; then
    echo "error=repo_root_unresolved root=$REPO_ROOT" >&2
    echo "本脚本必须从仓库内的 scripts/deploy/ 下运行 (不要复制到别处)。" >&2
    exit 2
fi
# shellcheck source=scripts/lib/server-script-common.sh
. "$REPO_ROOT/scripts/lib/server-script-common.sh"

CONFIRM=""
ONLY_HOST=""
ONLY_NAME=""
ACCEPT_DRIFT=0
REMOTE_MODE=0750

usage() {
    cat >&2 <<'EOF'
usage: install-server-scripts.sh [--host tokyo|ecs] [--only <basename>]
                                 [--accept-overwrite-drift]
                                 [--confirm YES-INSTALL]

不带 --confirm 时只报告将要做什么, 不写任何东西 (dry-run 是默认)。

  --host <name>                只装单台主机
  --only <basename>            只装某一个脚本 (如 oss-verify-artifact.sh)
  --accept-overwrite-drift     允许用仓库版本覆盖【已漂移】的服务器文件。
                               ⚠️ 默认拒绝。服务器更严格时正确做法是把加固取回仓库,
                               而不是反向覆盖 —— 2026-07-30 就是这么装回一个漏洞的。
  --confirm YES-INSTALL        真的写入

装完会自动跑 check-server-script-drift.sh 自证落地; 它不返 0 本脚本就不返 0。
EOF
    exit 2
}

while (($#)); do
    case "$1" in
        --host) (($# >= 2)) || usage; ONLY_HOST=$2; shift 2 ;;
        --only) (($# >= 2)) || usage; ONLY_NAME=$2; shift 2 ;;
        --accept-overwrite-drift) ACCEPT_DRIFT=1; shift ;;
        --confirm) (($# >= 2)) || usage; CONFIRM=$2; shift 2 ;;
        -h|--help) usage ;;
        *) echo "unknown argument: $1" >&2; usage ;;
    esac
done

APPLY=0
if [ -n "$CONFIRM" ]; then
    [ "$CONFIRM" = "YES-INSTALL" ] || { echo "error=confirm_token_mismatch" >&2; usage; }
    APPLY=1
fi

INVENTORY=$(server_script_load_inventory "$REPO_ROOT/scripts/deploy/server-script-inventory.conf") || exit 2

WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT
TS=$(date -u +%Y%m%dT%H%M%SZ)

n_match=0 n_installed=0 n_new=0 n_refused=0 n_failed=0 n_unreadable=0 n_skipped=0
declare -a ROWS=()
add_row() { ROWS+=("$1|$2|$3|$4"); }

# 把一个文件装上去。备份用与人工惯例相同的 <name>.bak.<UTC时间戳>, 这样历史备份仍然可读。
install_one() {
    local host="$1" remote="$2" local_file="$3"
    local target tmp
    local -a opts=()
    target=$(server_script_host_target "$host") || return 1
    mapfile -t opts < <(server_script_host_opts "$host")
    tmp="/tmp/.install-server-scripts.$$.$(basename "$remote")"

    # scp 不认 -n (那是 ssh 的"stdin 接 /dev/null"), 必须真正把它剔除。
    # ⚠️ 别写 "${opts[@]/-n/}" —— 那是逐元素做子串替换, 会把 -n 变成一个【空字符串参数】
    # 交给 scp, scp 把空参数当路径, 直接失败。实测就这么错过一次(报 FAILED, 服务器未被动)。
    local -a scp_opts=()
    local o
    for o in "${opts[@]}"; do
        [ "$o" = "-n" ] || scp_opts+=("$o")
    done

    # scp 的本地路径必须是 /d/... 形式; D:/... 会让 D: 被当成主机名。
    scp -q "${scp_opts[@]}" "$local_file" "$target:$tmp" 2>/dev/null || return 1

    local sudo_prefix=""
    server_script_host_needs_sudo "$host" && sudo_prefix="sudo -n "
    ssh "${opts[@]}" "$target" "
        set -eu
        if [ -f '$remote' ]; then ${sudo_prefix}cp -p '$remote' '$remote.bak.$TS'; fi
        ${sudo_prefix}install -o root -g root -m $REMOTE_MODE '$tmp' '$remote'
        rm -f '$tmp'
    " >/dev/null 2>&1 || { ssh "${opts[@]}" "$target" "rm -f '$tmp'" >/dev/null 2>&1; return 1; }
    return 0
}

while IFS='|' read -r host remote repo note; do
    [ -n "${host:-}" ] || continue
    [ -z "$ONLY_HOST" ] || [ "$host" = "$ONLY_HOST" ] || continue
    base=$(basename "$remote")
    [ -z "$ONLY_NAME" ] || [ "$base" = "$ONLY_NAME" ] || continue

    if [ "$repo" = "-" ]; then
        add_row "$host" "$base" SKIPPED "$note"
        ((n_skipped++))
        continue
    fi

    repo_file="$REPO_ROOT/$repo"
    if [ ! -f "$repo_file" ]; then
        add_row "$host" "$base" FAILED "仓库里没有 $repo"
        ((n_failed++))
        continue
    fi

    body="$WORK_DIR/$host.$base"
    state=$(server_script_remote_fetch "$host" "$remote" "$body")
    case "$state" in
        UNREADABLE:*)
            add_row "$host" "$base" UNREADABLE "${state#UNREADABLE:}"
            ((n_unreadable++))
            continue ;;
        ABSENT)
            if ((APPLY)); then
                if install_one "$host" "$remote" "$repo_file"; then
                    add_row "$host" "$base" INSTALLED_NEW "服务器此前没有"
                    ((n_new++))
                else
                    add_row "$host" "$base" FAILED "安装失败(新增)"
                    ((n_failed++))
                fi
            else
                add_row "$host" "$base" WOULD_INSTALL_NEW "服务器此前没有"
                ((n_new++))
            fi
            continue ;;
    esac

    if [ "$(server_script_norm_sha "$body")" = "$(server_script_norm_sha "$repo_file")" ]; then
        add_row "$host" "$base" MATCH "已一致, 无需操作"
        ((n_match++))
        continue
    fi

    # 这里就是那道闸。
    if ((!ACCEPT_DRIFT)); then
        add_row "$host" "$base" REFUSED_DRIFT "服务器与仓库不一致; 先确认哪边更严格"
        ((n_refused++))
        continue
    fi
    if ((APPLY)); then
        if install_one "$host" "$remote" "$repo_file"; then
            add_row "$host" "$base" OVERWROTE_DRIFT "已备份为 $base.bak.$TS"
            ((n_installed++))
        else
            add_row "$host" "$base" FAILED "安装失败(覆盖)"
            ((n_failed++))
        fi
    else
        add_row "$host" "$base" WOULD_OVERWRITE_DRIFT "会先备份为 $base.bak.$TS"
        ((n_installed++))
    fi
done <<< "$INVENTORY"

printf '\n%-6s  %-34s  %-22s  %s\n' HOST SCRIPT ACTION DETAIL
printf '%-6s  %-34s  %-22s  %s\n' '------' '----------------------------------' \
    '----------------------' '------'
for row in "${ROWS[@]:-}"; do
    [ -n "${row:-}" ] || continue
    IFS='|' read -r h s a d <<< "$row"
    printf '%-6s  %-34s  %-22s  %s\n' "$h" "$s" "$a" "$d"
done

printf '\nMATCH=%s NEW=%s OVERWROTE=%s REFUSED_DRIFT=%s FAILED=%s UNREADABLE=%s SKIPPED=%s (apply=%s)\n' \
    "$n_match" "$n_new" "$n_installed" "$n_refused" "$n_failed" "$n_unreadable" "$n_skipped" "$APPLY"

if ((n_unreadable > 0)); then
    echo "⚠️  有条目读不到 —— 先修访问再谈安装。" >&2
    exit 2
fi
if ((n_refused > 0)); then
    cat >&2 <<'EOF'
⚠️  有条目因为【服务器与仓库不一致】被拒绝覆盖。

先判断哪边更严格:
    ./scripts/deploy/check-server-script-drift.sh --diff
服务器更严格 → 把加固取回仓库 (git 提交服务器版本), 不要反向覆盖。
确认仓库版本才是对的 → 再加 --accept-overwrite-drift 重跑。
EOF
    exit 1
fi
if ((n_failed > 0)); then
    echo "⚠️  有条目安装失败。" >&2
    exit 1
fi

if ((APPLY)) && ((n_new + n_installed > 0)); then
    echo
    echo "=== 装完自证: check-server-script-drift.sh ==="
    # 装了东西却不确认它真的落地, 就是这套工具要消除的那种脱节。
    if "$REPO_ROOT/scripts/deploy/check-server-script-drift.sh" ${ONLY_HOST:+--host "$ONLY_HOST"}; then
        echo "✅ 安装完成且已自证与仓库一致。"
    else
        echo "❌ 装完但漂移检查未通过 —— 不要当成成功。" >&2
        exit 1
    fi
elif ((APPLY)); then
    echo "✅ 无需安装 (全部已一致)。"
else
    echo "ℹ️  dry-run: 未写入任何东西。加 --confirm YES-INSTALL 才会真的装。"
fi
