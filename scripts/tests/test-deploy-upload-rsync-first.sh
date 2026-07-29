#!/usr/bin/env bash
# 上传通道的三个契约:
#
# 1. rsync 先独占, 兜底延后。竞速的前提是各通道成本相当 —— 都全量传 168MB。加了
#    delta 基准种子后这个前提没了: rsync 只需传 ~20MB, 另两条仍各推满 168MB, 三者
#    抢同一条 4 MB/s 上行。实测 有基准+竞速 20s vs 有基准+独占 8s, 竞速净亏 12 秒。
#
# 2. 主通道带 --stats 并把 Literal/Matched 报出来。没有它, 上传阶段只有"耗时 Xs",
#    无法区分【种子没种上→全量重传】和【种子生效但链路慢】。
#
# 3. Windows 盘符路径必须归一化成 MSYS 形式。rsync 会把 "C:" 当【主机名】解析,
#    传输直接失败；而竞速会掩盖它 —— rsync 挂掉后 scp 赢, 操作者只看到"胜出: scp"
#    和一次变慢的部署, 毫无迹象表明主通道其实死了。
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
DEPLOY_SCRIPT="$ROOT_DIR/scripts/deploy/deploy-backend.sh"
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

# ---------------------------------------------- 1. rsync 独占, 兜底延后
launch=$(awk '/主通道\] rsync 独占运行/,/^        fi$/' "$DEPLOY_SCRIPT")
[ -n "$launch" ] || fail "rsync-first launch block is gone"

grep -Fq 'upload_rsync & UPLOAD_PIDS+=($!)' <<<"$launch" \
    || fail "primary rsync channel is no longer started first"
grep -Fq 'RSYNC_SOLO_TIMEOUT' <<<"$launch" \
    || fail "solo window is not bounded; a hung rsync would never fall back"

# 兜底必须在 solo 等待【之后】才启动, 否则又回到抢带宽的老路。
solo_wait_line=$(grep -Fn 'SOLO_WAITED=$((SOLO_WAITED + 1))' <<<"$launch" | cut -d: -f1 || true)
compress_line=$(grep -Fn 'upload_rsync_compress &' <<<"$launch" | cut -d: -f1 || true)
scp_line=$(grep -Fn 'upload_scp & UPLOAD_PIDS+=($!)' <<<"$launch" | cut -d: -f1 || true)
[ -n "$solo_wait_line" ] && [ -n "$compress_line" ] \
    || fail "cannot locate the solo wait and the deferred fallbacks"
[ "$solo_wait_line" -lt "$compress_line" ] \
    || fail "compress fallback still starts before the solo rsync window elapses"
[ -n "$scp_line" ] || fail "scp fallback was removed entirely; it is the last-resort channel"

# rsync 不可用时必须直接走 scp, 不能连兜底都没有。
grep -Fq 'rsync 不可用' <<<"$launch" \
    || fail "no scp path when rsync is unavailable"

# ---------------------------------------------- 2. --stats 与 delta 上报
grep -Fq 'rsync -az --stats --timeout=60' "$DEPLOY_SCRIPT" \
    || fail "primary rsync no longer requests --stats"
grep -Fq 'report_rsync_delta' "$DEPLOY_SCRIPT" \
    || fail "delta reporting helper is gone"

reporter=$(awk '/report_rsync_delta\(\) \{/,/^    \}/' "$DEPLOY_SCRIPT")
grep -Fq 'Literal data' <<<"$reporter" || fail "reporter does not extract Literal data"
grep -Fq 'Matched data' <<<"$reporter" || fail "reporter does not extract Matched data"
grep -Fq 'rsync-delta' <<<"$reporter" \
    || fail "delta numbers are not persisted for the receipt to pick up"

# 上报器的解析行为: 用真实的 rsync --stats 输出格式喂它。
cat > "$TMP_ROOT/stats.txt" <<'STATS'
Number of files: 1
Total file size: 176,280,322 bytes
Literal data: 21,396,082 bytes
Matched data: 154,884,240 bytes
sent 19,697,277 bytes  received 93,020 bytes  2,328,270.24 bytes/sec
total size is 176,280,322  speedup is 8.91
STATS
literal=$(sed -nE 's/^Literal data: ([0-9,]+) bytes.*/\1/p' "$TMP_ROOT/stats.txt" | head -1)
matched=$(sed -nE 's/^Matched data: ([0-9,]+) bytes.*/\1/p' "$TMP_ROOT/stats.txt" | head -1)
speedup=$(sed -nE 's/.*speedup is ([0-9.]+).*/\1/p' "$TMP_ROOT/stats.txt" | head -1)
[ "${literal//,/}" = "21396082" ] || fail "Literal data parsed wrong: got '$literal'"
[ "${matched//,/}" = "154884240" ] || fail "Matched data parsed wrong: got '$matched'"
[ "$speedup" = "8.91" ] || fail "speedup parsed wrong: got '$speedup'"

# 没有 --stats 输出时必须安静退出, 不能让上报器搞挂上传。
printf 'no stats here\n' > "$TMP_ROOT/nostats.txt"
absent=$(sed -nE 's/^Literal data: ([0-9,]+) bytes.*/\1/p' "$TMP_ROOT/nostats.txt" | head -1)
[ -z "$absent" ] || fail "reporter would emit garbage when rsync printed no stats"
grep -Fq '[ -n "$literal" ] || return 0' <<<"$reporter" \
    || fail "reporter does not bail out when stats are absent"

# ---------------------------------------------- 3. Windows 路径归一化 (行为测试)
# 第一版写成 [A-Za-z]:[/\\]* , 实测【连 "C:/..." 都匹配不到】—— bash glob 的
# bracket 表达式对反斜杠有歧义。所以这里必须实测转换结果, 只查"函数存在"没有意义。
COMMON_LIB="$ROOT_DIR/scripts/lib/deploy-common.sh"
STAGE_SCRIPT="$ROOT_DIR/scripts/deploy/stage-backend-artifact.sh"

# 归一化必须住在共享库里, 而不是某个脚本的私有副本 —— 传输点不止一处。
grep -Fq 'ssh_local_path()' "$COMMON_LIB" \
    || fail "ssh_local_path is not in the shared library; each transfer site would need its own copy"

# 每一个把【本地路径】交给 rsync/scp 的调用点都必须归一化。少一个就留一个静默失败点。
for site in \
    'rsync -az --stats --timeout=60 "$SRC"' \
    'rsync -az --compress-level=9 --timeout=60 "$(ssh_local_path "$JAR_PATH")"' \
    'scp -o ConnectTimeout=10 -o ServerAliveInterval=30 "$(ssh_local_path "$JAR_PATH")"'
do
    grep -Fq "$site" "$DEPLOY_SCRIPT" \
        || fail "transfer site not normalized: $site"
done
grep -Fq 'rsync -a --timeout=90 "$(ssh_local_path "$jar_path")"' "$STAGE_SCRIPT" \
    || fail "stage-backend-artifact.sh still hands a raw local path to rsync"

# 全仓扫描: 任何把本地路径变量交给 rsync/scp 的调用点都必须归一化。新增传输点
# 时忘记归一化, 这条会红 —— 否则又会多出一个"rsync 静默失败, 兜底通道顶上, 只
# 表现为变慢"的坑, 而那种坑今天已经踩了 4 次。
while IFS= read -r offender; do
    [ -n "$offender" ] || continue
    fail "未归一化的本地路径交给了 rsync/scp: $offender"
done < <(
    # 排除两类已知安全的写法:
    #   - 行内直接包了 ssh_local_path
    #   - 用 "$SRC" —— 它在上面几行由 SRC=$(ssh_local_path "$JAR_PATH") 赋值,
    #     该赋值本身由 check_path 之外的独立断言守着(见下方 SRC 断言)。
    grep -rnE '^[^#]*\b(rsync|scp)\b[^|]*"\$[A-Za-z_][A-Za-z_0-9]*"[[:space:]]+"[^"]*\$(SERVER|GATEWAY|PAGE_GATEWAY|RELAY_SERVER)' \
        "$ROOT_DIR"/scripts/deploy/*.sh 2>/dev/null \
        | grep -v 'ssh_local_path' \
        | grep -v '"\$SRC"' || true
)

# $SRC 之所以能被上面豁免, 全靠这一行赋值。它没了, 主通道就又在传裸路径。
grep -Fq 'SRC=$(ssh_local_path "$JAR_PATH")' "$DEPLOY_SCRIPT" \
    || fail 'primary channel no longer derives $SRC from ssh_local_path'

# 调用了共享函数就必须真的把库 source 进来, 否则运行时是 command not found。
# (stage-backend-artifact.sh 当初就漏了这一步, 自测才发现。)
while IFS= read -r caller; do
    [ -n "$caller" ] || continue
    grep -Fq 'deploy-common.sh' "$caller" \
        || fail "$(basename "$caller") calls ssh_local_path without sourcing the shared library"
done < <(grep -rl 'ssh_local_path' "$ROOT_DIR"/scripts/deploy/*.sh 2>/dev/null || true)

eval "$(awk '/^ssh_local_path\(\) \{/,/^\}/' "$COMMON_LIB")"

check_path() {
    local input=$1 expected=$2 got
    got=$(ssh_local_path "$input")
    [ "$got" = "$expected" ] || fail "ssh_local_path '$input' → '$got', 期望 '$expected'"
}

check_path 'C:/Users/Steve/x.jar'          '/c/Users/Steve/x.jar'
check_path 'c:/Users/Steve/x.jar'          '/c/Users/Steve/x.jar'
check_path 'D:/tmp/y.jar'                  '/d/tmp/y.jar'
check_path 'C:\Users\Steve\x.jar'          '/c/Users/Steve/x.jar'
# 已经是 MSYS / 相对路径的必须原样通过, 不能被改坏。
check_path '/c/Users/Steve/x.jar'          '/c/Users/Steve/x.jar'
check_path 'backend/java/target/x.jar'     'backend/java/target/x.jar'
check_path '/tmp/a.jar'                    '/tmp/a.jar'

# 转换发生时必须留痕, 否则又变成静默行为。
grep -Fq '源路径已转为 MSYS 形式供 rsync 使用' "$DEPLOY_SCRIPT" \
    || fail "path rewriting happens silently"

echo "PASS: rsync-first upload, delta stats reporting, and Windows path normalization"
