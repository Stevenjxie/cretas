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
# HOST|REMOTE_PATH|REPO_PATH|NOTE
# REPO_PATH 为 '-' 表示 IGNORE —— 刻意声明而非静默跳过, 这样"为什么不检查它"是可
# 审阅的。IGNORE 的都是东京出口/VPN 自身的运维脚本, 不属于 cretas 发布链路。
INVENTORY=$(cat <<'EOF'
tokyo|/usr/local/sbin/github-artifact-stage|scripts/deploy/lightsail/github-artifact-stage|GitHub 制品下载+解包
tokyo|/usr/local/sbin/oss-put-artifact|scripts/deploy/lightsail/oss-put-artifact|签名 URL 上传到上海 OSS
tokyo|/usr/local/sbin/github-cache-put|scripts/deploy/lightsail/github-cache-put|东京侧 GitHub 制品缓存写入
tokyo|/usr/local/sbin/github-cache-clean|scripts/deploy/lightsail/github-cache-clean|缓存 LRU 驱逐
tokyo|/usr/local/sbin/github-artifact-cache-rollback|scripts/deploy/lightsail/github-artifact-cache-rollback|缓存设施回滚
tokyo|/usr/local/sbin/install-tokyo-usage-meter|-|东京 VPN 流量计, 非发布链路
tokyo|/usr/local/sbin/test-amnezia-openvpn-netns|-|VPN netns 自测, 非发布链路
tokyo|/usr/local/sbin/tokyo-openvpn-firewall|-|VPN 防火墙, 非发布链路
tokyo|/usr/local/sbin/tokyo-vpn-usage-collector|-|VPN 流量采集, 非发布链路
ecs|/usr/local/sbin/oss-sign-put.py|scripts/deploy/ecs/oss-sign-put.py|OSS 签名 URL 生成
ecs|/usr/local/sbin/oss-verify-artifact.sh|scripts/deploy/ecs/oss-verify-artifact.sh|ECS 侧下载+校验+落缓存
EOF
)

# 结构性忽略: 安装时的备份、Python 缓存、隐藏文件。这些不是"漂移", 是正常产物。
is_structural_noise() {
    case "$1" in
        *.bak.*|__pycache__|.*) return 0 ;;
        *) return 1 ;;
    esac
}

host_ssh_target() {
    case "$1" in
        tokyo) printf 'ubuntu@10.66.66.1' ;;
        ecs)   printf 'root@47.100.235.168' ;;
        *) return 1 ;;
    esac
}

host_ssh_opts() {
    # 一律带上 BatchMode/ConnectTimeout, 顺带保证数组非空 —— 空数组展开在旧 bash
    # 的 `set -u` 下会直接报错。
    #
    # `-n` 不是可选项: 主循环是 `while read ... <<< "$INVENTORY"`, 不带 -n 的 ssh
    # 会从同一个 stdin 读走清单剩余全部行, 循环只跑第一条就结束 —— 实测第一版就这样
    # 只查了 11 条里的 1 条, 然后打印「✅ 一致」并 exit 0。
    printf '%s\n' -n -o BatchMode=yes -o ConnectTimeout=10
    case "$1" in
        # 东京走 Steve 钉在本地出口的私网地址 + 专用 key
        tokyo) printf '%s\n' -i "$HOME/.ssh/ai-egress-tokyo-windows_ed25519" -o IdentitiesOnly=yes ;;
        ecs)   ;;
        *) return 1 ;;
    esac
}

# 东京的 /usr/local/sbin 文件是 root:root 0750, ubuntu 读不了 —— 必须 sudo -n。
# 目录本身是 0755, 所以 `[ -f ]` / `stat` 不需要提权, 只有 cat 需要。
host_needs_sudo() {
    case "$1" in
        tokyo) return 0 ;;
        *) return 1 ;;
    esac
}

WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT

# 把远端文件抓到 $1, 并把状态写到 stdout 的一行: PRESENT|ABSENT|UNREADABLE:<原因>
#
# 关键防呆: 远端先 printf 一行 `PRESENT <字节数>`, 再 cat 内容。本地拿到后核对实收
# 字节数与声明字节数。这样"静默截断"和"ssh 成功但 sudo 失败给了空内容"都无法冒充
# 一份干净的对比结果 —— handoff 记录的四次栽跟头全是空结果被当成正常结果。
remote_fetch() {
    local host="$1" path="$2" body="$3"
    local target rc header declared actual raw
    local -a opts=()

    [[ $path =~ ^/[A-Za-z0-9._/-]+$ ]] || { printf 'UNREADABLE:unsafe_path\n'; return; }
    target=$(host_ssh_target "$host") || { printf 'UNREADABLE:unknown_host\n'; return; }
    mapfile -t opts < <(host_ssh_opts "$host")

    local catcmd="cat -- '$path'"
    if host_needs_sudo "$host"; then
        catcmd="sudo -n cat -- '$path'"
    fi
    local inner="if [ -f '$path' ]; then printf 'PRESENT %s\\n' \"\$(stat -c %s '$path')\"; $catcmd; else printf 'ABSENT 0\\n'; fi"

    raw="$body.raw"
    ssh "${opts[@]}" "$target" "$inner" > "$raw" 2> "$body.err"
    rc=$?
    if ((rc != 0)); then
        printf 'UNREADABLE:ssh_exit_%s\n' "$rc"
        return
    fi

    header=$(head -n 1 "$raw")
    tail -n +2 "$raw" > "$body"
    case "$header" in
        ABSENT*) printf 'ABSENT\n'; return ;;
        PRESENT*) ;;
        *) printf 'UNREADABLE:bad_protocol_header\n'; return ;;
    esac

    declared=${header#PRESENT }
    actual=$(wc -c < "$body" | tr -d ' ')
    if [[ ! $declared =~ ^[0-9]+$ ]] || [ "$declared" != "$actual" ]; then
        printf 'UNREADABLE:size_mismatch_declared_%s_got_%s\n' "$declared" "$actual"
        return
    fi
    printf 'PRESENT\n'
}

norm_sha() { tr -d '\r' < "$1" | sha256sum | cut -d ' ' -f 1; }

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
