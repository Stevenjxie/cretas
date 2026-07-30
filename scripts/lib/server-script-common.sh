#!/usr/bin/env bash
# check-server-script-drift.sh 与 install-server-scripts.sh 的公共部分。
#
# 为什么抽出来: 这两个工具必须对「怎么连、怎么读、怎么比」完全一致。检查器说 MATCH 而
# 安装器按另一套逻辑判断"需要覆盖", 就是又一个"文档描述的机制与实际运行的机制脱节"。
# 清单也只有一份, 在 scripts/deploy/server-script-inventory.conf。
#
# 只提供函数, 不做任何顶层动作 —— 调用方 source 之后自己决定 WORK_DIR 与 trap。

# 结构性忽略: 安装时的备份、Python 缓存、隐藏文件。这些不是"漂移", 是正常产物。
server_script_is_structural_noise() {
    case "$1" in
        *.bak.*|__pycache__|.*) return 0 ;;
        *) return 1 ;;
    esac
}

server_script_host_target() {
    case "$1" in
        tokyo) printf 'ubuntu@10.66.66.1' ;;
        ecs)   printf 'root@47.100.235.168' ;;
        *) return 1 ;;
    esac
}

server_script_host_opts() {
    # 一律带上 BatchMode/ConnectTimeout, 顺带保证数组非空 —— 空数组展开在旧 bash 的
    # `set -u` 下会直接报错。
    #
    # `-n` 不是可选项: 调用方的主循环是 `while read ... <<< "$INVENTORY"`, 不带 -n 的 ssh
    # 会从同一个 stdin 读走清单剩余全部行, 循环只跑第一条就结束 —— 实测漂移检查器第一版
    # 就这样只查了 11 条里的 1 条, 然后打印「✅ 一致」并 exit 0。
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
server_script_host_needs_sudo() {
    case "$1" in
        tokyo) return 0 ;;
        *) return 1 ;;
    esac
}

server_script_safe_remote_path() {
    [[ $1 =~ ^/[A-Za-z0-9._/-]+$ ]]
}

# 把远端文件抓到 $3, 并把状态写到 stdout 的一行: PRESENT|ABSENT|UNREADABLE:<原因>
#
# 关键防呆: 远端先 printf 一行 `PRESENT <字节数>`, 再 cat 内容。本地拿到后核对实收字节数
# 与声明字节数。这样"静默截断"和"ssh 成功但 sudo 失败给了空内容"都无法冒充一份干净的
# 对比结果 —— 这条链路上被空结果骗过不止一次。
server_script_remote_fetch() {
    local host="$1" path="$2" body="$3"
    local target rc header declared actual raw
    local -a opts=()

    server_script_safe_remote_path "$path" || { printf 'UNREADABLE:unsafe_path\n'; return; }
    target=$(server_script_host_target "$host") || { printf 'UNREADABLE:unknown_host\n'; return; }
    mapfile -t opts < <(server_script_host_opts "$host")

    local catcmd="cat -- '$path'"
    if server_script_host_needs_sudo "$host"; then
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

# 行尾归一化后的内容哈希。仓库可能 CRLF, 服务器是 LF; 行尾不是语义差异。
server_script_norm_sha() { tr -d '\r' < "$1" | sha256sum | cut -d ' ' -f 1; }

# 读清单, 去注释与空行。清单缺失或为空都是硬错 —— 一份空清单会让两个工具都"没发现问题"。
server_script_load_inventory() {
    local file="$1"
    if [ ! -s "$file" ]; then
        echo "error=inventory_missing file=$file" >&2
        return 2
    fi
    local data
    data=$(grep -vE '^[[:space:]]*(#|$)' "$file")
    if [ -z "$data" ]; then
        echo "error=inventory_empty file=$file" >&2
        return 2
    fi
    printf '%s\n' "$data"
}
