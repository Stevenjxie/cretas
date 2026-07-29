#!/usr/bin/env bash
set -euo pipefail

# deploy-smartbi-python.sh 的 EPIPE / 日志可读性单测。
#
# 背景 (被修的 bug):
#   1) 脚本内多处形如
#          printf '%s' "$body" | grep -Fq 'marker'
#          systemctl show "$unit" -p ExecStart --value | grep -Fq 'marker'
#      在 `set -o pipefail` 下这是**静默错误**的: grep -q 一命中就退出并关掉读端 →
#      左边的生产者死于 SIGPIPE(141) → pipefail 把整条流水线判为失败 → 命中被报告成
#      没命中。喂进去的健康响应/systemctl 输出目前都远小于 64KB 管道缓冲, 生产者能
#      抢在 grep 退出前写完, 于是"侥幸正确"; 响应体一变大就翻转。
#      翻转方向在这里尤其糟: 健康检查与 runtime 契约校验会误判为"不满足", 触发
#      **本不该发生的生产回滚**。
#   2) 每处 rsync 都是 `rsync ... 2>&1 | tail -5`, 而 rsync 的关键错误在**开头**
#      (connection unexpectedly closed / No such file / Permission denied), tail -5
#      恰好把它砍掉 —— 退出码靠 pipefail 保住了, 现象是"知道失败但看不懂为什么"。
#
# 这份测试的核心是**大输入**: 每个检查都用 >64KB / MB 级输入跑一遍, 旧实现在这些
# 用例上会给出相反的答案 (文末 mutation 段把旧形状原样跑一遍来证明这一点)。
#
# 测试方式: 直接从生产脚本里**原样抽出**远端 heredoc 的脚本体来跑, 而不是抄一份
# 副本 —— 跑的就是真正会被 ssh 送上服务器执行的那段代码。外部命令 (curl /
# systemctl / rsync / python) 用 shell 函数打桩; 只有两处绝对路径 (/proc、
# /www/wwwroot/cretas) 会被重写到沙箱, 匹配逻辑本身一个字都不改。
#
# 用法: bash scripts/tests/test-smartbi-python-deploy-epipe.sh

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
SOURCE_SCRIPT="$ROOT_DIR/scripts/deploy/deploy-smartbi-python.sh"
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

FAILURES=0

fail() {
    echo "FAIL: $*" >&2
    FAILURES=$((FAILURES + 1))
}

die() {
    echo "FATAL: $*" >&2
    exit 1
}

[ -f "$SOURCE_SCRIPT" ] || die "找不到被测脚本: $SOURCE_SCRIPT"
bash -n "$SOURCE_SCRIPT" || die "被测脚本自身语法不通过"

# ---------------------------------------------------------------------------
# 0. 工具: 抽取 / 大输入构造 / 断言
# ---------------------------------------------------------------------------

# 抽出 `<<'MARKER' ... MARKER` 之间的脚本体 (不含首尾标记行)。
extract_heredoc() {
    local marker=$1
    awk -v start="<<'$marker'" -v m="$marker" '
        !inb && index($0, start) { inb = 1; next }
        inb && $0 == m { exit }
        inb { print }
    ' "$SOURCE_SCRIPT"
}

# 抽出 `# >>> NAME` .. `# <<< NAME` 之间的块 (含标记行, 便于回读注释)。
extract_marked_block() {
    local name=$1
    awk -v s="# >>> $name" -v e="# <<< $name" '
        $0 == s { inb = 1 }
        inb { print }
        $0 == e { exit }
    ' "$SOURCE_SCRIPT"
}

file_size() {
    local n
    n=$(wc -c <"$1")
    printf '%s' "${n//[[:space:]]/}"
}

PIPE_BUF=65536
# 中性噪音行: 不含任何被检查的 marker, 所以只影响体积不影响匹配结果。
NOISE_LINE='noise-line-that-contains-no-marker-whatsoever-0123456789abcdefghijklmnopqrstuvwxyz'
NOISE_BLOCK=$NOISE_LINE
while [ "${#NOISE_BLOCK}" -lt "$PIPE_BUF" ]; do NOISE_BLOCK="$NOISE_BLOCK"$'\n'"$NOISE_BLOCK"; done
[ "${#NOISE_BLOCK}" -gt "$PIPE_BUF" ] || die "噪音块没超过管道缓冲, 大输入用例无效"

# write_body <dest> <min_bytes> <where: front|back|none> <payload>
# front = payload 在**第一行**, 后面全是噪音 —— 旧实现最容易翻车的形状
#         (grep 立刻命中退出, 生产者还剩几 MB 没写完)。
# back  = payload 在最后一行 (grep 必须读完全部输入才命中)。
# none  = 完全没有 payload。
write_body() {
    local dest=$1 min_bytes=$2 where=$3 payload=${4:-}
    : >"$dest"
    [ "$where" = front ] && printf '%s\n' "$payload" >>"$dest"
    while [ "$(file_size "$dest")" -lt "$min_bytes" ]; do
        printf '%s\n' "$NOISE_BLOCK" >>"$dest"
    done
    [ "$where" = back ] && printf '%s\n' "$payload" >>"$dest"
    return 0
}

# 跑一个 (prelude + body) 组装出来的脚本, 回显退出码与合并输出。
LAST_OUTPUT=""
run_script() {
    local prelude=$1 body=$2
    shift 2
    local combined="$TMP_ROOT/run.$$.sh" status=0
    cat "$prelude" "$body" >"$combined"
    LAST_OUTPUT=$(bash "$combined" "$@" 2>&1) || status=$?
    rm -f "$combined"
    return "$status"
}

assert_ok() {
    local label=$1 prelude=$2 body=$3
    shift 3
    if ! run_script "$prelude" "$body" "$@"; then
        fail "$label: 期望通过却失败了"$'\n'"$LAST_OUTPUT"
    fi
}

assert_fails() {
    local label=$1 prelude=$2 body=$3
    shift 3
    if run_script "$prelude" "$body" "$@"; then
        fail "$label: 期望失败却通过了 (检查被改宽了)"
        return
    fi
    # 防"空洞通过": 失败必须来自被测的检查, 而不是打桩没接上。
    case "$LAST_OUTPUT" in
        *"unexpected curl url"*|*"command not found"*|*"No such file or directory"*)
            fail "$label: 失败原因是打桩没接上, 不是被测检查"$'\n'"$LAST_OUTPUT" ;;
    esac
}

# ---------------------------------------------------------------------------
# 1. 回归哨兵: 生产脚本里不允许再出现 `... | grep` 这个形状 (注释除外)。
# ---------------------------------------------------------------------------
offenders=$(grep -n '| *grep' "$SOURCE_SCRIPT" | grep -v '^[0-9]\+:[[:space:]]*#' || true)
[ -z "$offenders" ] || fail "deploy-smartbi-python.sh 里仍有管道进 grep 的代码:"$'\n'"$offenders"

# 同样禁掉 `rsync ... | tail`: 那正是"错误头部被砍掉"的形状。
rsync_tail=$(grep -n 'rsync .*| *tail' "$SOURCE_SCRIPT" | grep -v '^[0-9]\+:[[:space:]]*#' || true)
[ -z "$rsync_tail" ] || fail "deploy-smartbi-python.sh 里仍有 rsync 管道进 tail:"$'\n'"$rsync_tail"

# 已知且**刻意保留**的例外: manifest_value 里的 `sed -n ... | head -n 1`。
# 它同样是"消费者提前关管道"的形状, 但 head 在退出前已经把那一行写给了命令替换,
# 取到的**值**永远正确; 受污染的只有退出码, 而调用点是 `[ "$(manifest_value k)" != x ]`,
# 根本不看退出码。也就是说它不会像 grep -q 那样翻转答案。改它要动依赖缓存判定,
# 收益为零风险非零, 故不在本次范围内。这里钉住"只有这一处"。
early_close_pipes=$(grep -n '| *head' "$SOURCE_SCRIPT" | grep -v '^[0-9]\+:[[:space:]]*#' || true)
expected_exception='    sed -n "s/^$1=//p" "$MANIFEST" 2>/dev/null | head -n 1'
actual_exception=$(printf '%s\n' "$early_close_pipes" | sed 's/^[0-9]*://')
if [ "$actual_exception" != "$expected_exception" ]; then
    fail "提前关管道的形状不止已知例外一处 (或例外本身变了):"$'\n'"$early_close_pipes"
fi

# ---------------------------------------------------------------------------
# 2. BUSINESS_HEALTH: 回滚后的业务健康校验
# ---------------------------------------------------------------------------
BH_BODY="$TMP_ROOT/business-health.body"
extract_heredoc BUSINESS_HEALTH >"$BH_BODY"
[ -s "$BH_BODY" ] || die "抽不出 BUSINESS_HEALTH 脚本体 (heredoc 标记被改了?)"
grep -q 'postgres' "$BH_BODY" || die "BUSINESS_HEALTH 抽取结果不含预期检查, 抽取有误"

BH_PRELUDE="$TMP_ROOT/business-health.prelude"
cat >"$BH_PRELUDE" <<'PRELUDE'
curl() {
    local url=""
    for url in "$@"; do :; done
    case "$url" in
        */api/classifier/health) cat "$STUB_CLASSIFIER" ;;
        */health) cat "$STUB_HEALTH" ;;
        *) echo "unexpected curl url: $url" >&2; return 22 ;;
    esac
}
PRELUDE

HEALTH_OK='{"status":"ok","postgres":"connected"}'
HEALTH_BAD='{"status":"ok","postgres":"disconnected"}'
CLASSIFIER_OK='{"status":"healthy","model_available":true}'
CLASSIFIER_BAD='{"status":"healthy","model_available":false}'

export STUB_HEALTH="$TMP_ROOT/health.json"
export STUB_CLASSIFIER="$TMP_ROOT/classifier.json"

# --- 小输入: 确认样例本身选得对 (旧实现在这里也是绿的, 这正是 bug 潜伏的原因) ---
write_body "$STUB_HEALTH" 0 front "$HEALTH_OK"
write_body "$STUB_CLASSIFIER" 0 front "$CLASSIFIER_OK"
assert_ok 'business-health/small-both-ok' "$BH_PRELUDE" "$BH_BODY"

write_body "$STUB_HEALTH" 0 front "$HEALTH_BAD"
assert_fails 'business-health/small-postgres-disconnected' "$BH_PRELUDE" "$BH_BODY"

write_body "$STUB_HEALTH" 0 front "$HEALTH_OK"
write_body "$STUB_CLASSIFIER" 0 front "$CLASSIFIER_BAD"
assert_fails 'business-health/small-model-unavailable' "$BH_PRELUDE" "$BH_BODY"

# --- 大输入: 修复的核心。marker 在第一行, 后面 2MB 噪音 ---
write_body "$STUB_HEALTH" $((2 * 1024 * 1024)) front "$HEALTH_OK"
write_body "$STUB_CLASSIFIER" $((2 * 1024 * 1024)) front "$CLASSIFIER_OK"
[ "$(file_size "$STUB_HEALTH")" -gt $((1024 * 1024)) ] || die "MB 级健康响应没构造出来"
assert_ok 'business-health/HUGE-marker-first-line' "$BH_PRELUDE" "$BH_BODY"

# marker 在最后一行: grep 必须读完整个输入, 用来确认大输入不会"多命中"
write_body "$STUB_HEALTH" $((2 * 1024 * 1024)) back "$HEALTH_OK"
write_body "$STUB_CLASSIFIER" $((2 * 1024 * 1024)) back "$CLASSIFIER_OK"
assert_ok 'business-health/HUGE-marker-last-line' "$BH_PRELUDE" "$BH_BODY"

# 大输入 + 无 marker: 确认修复没有把检查改宽 ("该拒的" 仍然被拒)
write_body "$STUB_HEALTH" $((2 * 1024 * 1024)) none
write_body "$STUB_CLASSIFIER" $((2 * 1024 * 1024)) front "$CLASSIFIER_OK"
assert_fails 'business-health/HUGE-no-postgres-marker' "$BH_PRELUDE" "$BH_BODY"

write_body "$STUB_HEALTH" $((2 * 1024 * 1024)) front "$HEALTH_OK"
write_body "$STUB_CLASSIFIER" $((2 * 1024 * 1024)) none
assert_fails 'business-health/HUGE-no-classifier-marker' "$BH_PRELUDE" "$BH_BODY"

# ---------------------------------------------------------------------------
# 3. RUNTIME_CAPTURE: 从 systemctl ExecStart 里提取当前生产 venv
# ---------------------------------------------------------------------------
RC_BODY="$TMP_ROOT/runtime-capture.body"
extract_heredoc RUNTIME_CAPTURE >"$RC_BODY"
[ -s "$RC_BODY" ] || die "抽不出 RUNTIME_CAPTURE 脚本体"
grep -q 'runtime_python' "$RC_BODY" || die "RUNTIME_CAPTURE 抽取结果不含预期逻辑"

SANDBOX="$TMP_ROOT/sandbox"
FAKE_REMOTE="$SANDBOX/www/wwwroot/cretas/code/backend/python"
mkdir -p "$FAKE_REMOTE/venv311/bin" "$FAKE_REMOTE/venv-current/bin"
cat >"$FAKE_REMOTE/venv311/bin/python" <<'FAKEPY'
#!/bin/sh
exit 0
FAKEPY
chmod +x "$FAKE_REMOTE/venv311/bin/python"
cp "$FAKE_REMOTE/venv311/bin/python" "$FAKE_REMOTE/venv-current/bin/python"
chmod +x "$FAKE_REMOTE/venv-current/bin/python"

RC_PRELUDE="$TMP_ROOT/runtime-capture.prelude"
cat >"$RC_PRELUDE" <<'PRELUDE'
systemctl() { cat "$STUB_EXECSTART"; }
PRELUDE

export STUB_EXECSTART="$TMP_ROOT/execstart.txt"
EXEC_LINE="{ path=$FAKE_REMOTE/venv311/bin/python ; argv[]=$FAKE_REMOTE/venv311/bin/python -m uvicorn main:app ; }"

write_body "$STUB_EXECSTART" 0 front "$EXEC_LINE"
if run_script "$RC_PRELUDE" "$RC_BODY" "$FAKE_REMOTE"; then
    [ "$LAST_OUTPUT" = "$FAKE_REMOTE/venv311" ] \
        || fail "runtime-capture/small: 期望 [$FAKE_REMOTE/venv311] 实际 [$LAST_OUTPUT]"
else
    fail "runtime-capture/small: 期望通过却失败了"$'\n'"$LAST_OUTPUT"
fi

# 大输入: ExecStart 匹配行在最前, 后面 2MB 噪音 (旧的 printf|grep|head 形状在这里
# 靠 `|| true` 兜住了退出码, 但形状本身仍是 SIGPIPE 陷阱; 这条钉住取值必须正确)
write_body "$STUB_EXECSTART" $((2 * 1024 * 1024)) front "$EXEC_LINE"
if run_script "$RC_PRELUDE" "$RC_BODY" "$FAKE_REMOTE"; then
    [ "$LAST_OUTPUT" = "$FAKE_REMOTE/venv311" ] \
        || fail "runtime-capture/HUGE: 期望 [$FAKE_REMOTE/venv311] 实际 [$LAST_OUTPUT]"
else
    fail "runtime-capture/HUGE: 期望通过却失败了"$'\n'"$LAST_OUTPUT"
fi

# 多个候选时必须取**第一个** (等价旧的 `| head -n 1`)
write_body "$STUB_EXECSTART" 0 front "$EXEC_LINE"
printf '%s\n' "path=$FAKE_REMOTE/venv999/bin/python" >>"$STUB_EXECSTART"
if run_script "$RC_PRELUDE" "$RC_BODY" "$FAKE_REMOTE"; then
    [ "$LAST_OUTPUT" = "$FAKE_REMOTE/venv311" ] \
        || fail "runtime-capture/first-wins: 期望第一个候选, 实际 [$LAST_OUTPUT]"
else
    fail "runtime-capture/first-wins: 期望通过却失败了"$'\n'"$LAST_OUTPUT"
fi

# 大输入且完全没有 venv 路径: 必须报错退出, 不能因为"没读完"而误判
write_body "$STUB_EXECSTART" $((2 * 1024 * 1024)) none
assert_fails 'runtime-capture/HUGE-no-venv-path' "$RC_PRELUDE" "$RC_BODY" "$FAKE_REMOTE"

# ---------------------------------------------------------------------------
# 4. RUNTIME_INSTALL 的 unit 契约校验循环
# ---------------------------------------------------------------------------
RI_BODY="$TMP_ROOT/runtime-install-loop.body"
{
    echo 'set -euo pipefail'
    extract_heredoc RUNTIME_INSTALL | awk '/^for unit in cretas-python/ { inb = 1 } inb { print }'
} >"$RI_BODY"
grep -q 'venv-current/bin/python' "$RI_BODY" || die "抽不出 RUNTIME_INSTALL 的 unit 契约循环"
grep -q 'did not load the venv-current runtime contract' "$RI_BODY" \
    || die "RUNTIME_INSTALL 循环抽取不完整"

RI_PRELUDE="$TMP_ROOT/runtime-install.prelude"
cat >"$RI_PRELUDE" <<'PRELUDE'
systemctl() {
    local unit=$2
    if [ -f "$STUB_UNIT_DIR/$unit" ]; then cat "$STUB_UNIT_DIR/$unit"; else echo ""; fi
}
PRELUDE

export STUB_UNIT_DIR="$TMP_ROOT/units"
mkdir -p "$STUB_UNIT_DIR"
VENV_CURRENT_EXEC='{ path=/www/wwwroot/cretas/code/backend/python/venv-current/bin/python ; }'

for unit in cretas-python cretas-gold-etl-refresh cretas-corpus-refresh; do
    write_body "$STUB_UNIT_DIR/$unit" 0 front "$VENV_CURRENT_EXEC"
done
assert_ok 'runtime-install-loop/small-all-ok' "$RI_PRELUDE" "$RI_BODY"

for unit in cretas-python cretas-gold-etl-refresh cretas-corpus-refresh; do
    write_body "$STUB_UNIT_DIR/$unit" $((2 * 1024 * 1024)) front "$VENV_CURRENT_EXEC"
done
assert_ok 'runtime-install-loop/HUGE-all-ok' "$RI_PRELUDE" "$RI_BODY"

# 最后一个 unit 没有契约 → 必须拒 (确认没被改宽)
write_body "$STUB_UNIT_DIR/cretas-corpus-refresh" $((2 * 1024 * 1024)) none
assert_fails 'runtime-install-loop/HUGE-one-unit-missing-contract' "$RI_PRELUDE" "$RI_BODY"

# ---------------------------------------------------------------------------
# 5. RUNTIME_VERIFY: 发布最后一道闸 (误判即触发生产回滚)
# ---------------------------------------------------------------------------
RV_RAW="$TMP_ROOT/runtime-verify.raw"
extract_heredoc RUNTIME_VERIFY >"$RV_RAW"
[ -s "$RV_RAW" ] || die "抽不出 RUNTIME_VERIFY 脚本体"

# 唯一的改写: 两个绝对路径前缀挪进沙箱。匹配逻辑一个字不动。
RV_BODY="$TMP_ROOT/runtime-verify.body"
sed -e "s|/proc/|$SANDBOX/proc/|g" -e "s|/www/wwwroot/cretas/|$SANDBOX/www/wwwroot/cretas/|g" \
    "$RV_RAW" >"$RV_BODY"
grep -q "$SANDBOX/proc/" "$RV_BODY" || die "/proc 路径改写没生效, 沙箱化失败"
grep -q "$SANDBOX/www/wwwroot/cretas/" "$RV_BODY" || die "/www 路径改写没生效, 沙箱化失败"
# 改写之外必须完全一致 (钉住"只改了路径")
diff <(sed -e "s|$SANDBOX||g" "$RV_BODY") "$RV_RAW" >/dev/null \
    || die "RUNTIME_VERIFY 沙箱化改动超出了路径前缀"

mkdir -p "$SANDBOX/proc/4242" "$SANDBOX/www/wwwroot/cretas/code/scripts/cron"
for cron in restaurant-ai-eval.sh refresh-demo-rest.sh; do
    printf '%s\n' 'source /www/wwwroot/cretas/code/backend/python/venv-current/bin/activate' \
        >"$SANDBOX/www/wwwroot/cretas/code/scripts/cron/$cron"
done

RV_PRELUDE="$TMP_ROOT/runtime-verify.prelude"
cat >"$RV_PRELUDE" <<'PRELUDE'
# readlink 被打桩以避开 Windows/Git-Bash 的 symlink 支持差异 —— 它不是本次改动
# 涉及的逻辑, 打桩不影响被测的匹配语义。
readlink() { printf '%s\n' "$STUB_READLINK"; }
curl() {
    local url=""
    for url in "$@"; do :; done
    case "$url" in
        */api/classifier/health) cat "$STUB_CLASSIFIER" ;;
        */health) cat "$STUB_HEALTH" ;;
        *) echo "unexpected curl url: $url" >&2; return 22 ;;
    esac
}
systemctl() {
    case "$*" in
        *is-active*) return 0 ;;
        *NRestarts*) echo 0 ;;
        *MainPID*) echo 4242 ;;
        *ExecStart*)
            local unit=$2
            if [ -f "$STUB_UNIT_DIR/$unit" ]; then cat "$STUB_UNIT_DIR/$unit"; else echo ""; fi
            ;;
        *) return 1 ;;
    esac
}
PRELUDE

export STUB_READLINK="$FAKE_REMOTE/venv311"
STUB_CMDLINE="$SANDBOX/proc/4242/cmdline"

# cmdline 是 NUL 分隔的, 用 tr 还原成空格; 这里直接写 NUL 分隔的大内容。
write_cmdline() {
    local min_bytes=$1 where=$2
    write_body "$STUB_CMDLINE.tmp" "$min_bytes" "$where" \
        "$FAKE_REMOTE/venv-current/bin/python"
    tr '\n' '\0' <"$STUB_CMDLINE.tmp" >"$STUB_CMDLINE"
    rm -f "$STUB_CMDLINE.tmp"
}

reset_runtime_verify_stubs() {
    write_body "$STUB_HEALTH" 0 front "$HEALTH_OK"
    write_body "$STUB_CLASSIFIER" 0 front "$CLASSIFIER_OK"
    write_cmdline 0 front
    for unit in cretas-python cretas-gold-etl-refresh cretas-corpus-refresh; do
        write_body "$STUB_UNIT_DIR/$unit" 0 front "$VENV_CURRENT_EXEC"
    done
}

rv_args=("$FAKE_REMOTE" "$FAKE_REMOTE/venv311" "$FAKE_REMOTE/venv311")

reset_runtime_verify_stubs
assert_ok 'runtime-verify/small-all-ok' "$RV_PRELUDE" "$RV_BODY" "${rv_args[@]}"

# --- 大输入全绿: 这是本次修复真正要保住的行为 ---
write_body "$STUB_HEALTH" $((2 * 1024 * 1024)) front "$HEALTH_OK"
write_body "$STUB_CLASSIFIER" $((2 * 1024 * 1024)) front "$CLASSIFIER_OK"
write_cmdline $((2 * 1024 * 1024)) front
for unit in cretas-python cretas-gold-etl-refresh cretas-corpus-refresh; do
    write_body "$STUB_UNIT_DIR/$unit" $((2 * 1024 * 1024)) front "$VENV_CURRENT_EXEC"
done
assert_ok 'runtime-verify/HUGE-all-ok' "$RV_PRELUDE" "$RV_BODY" "${rv_args[@]}"

# --- 逐项否定: 每个检查在大输入下仍然拒绝不合格的输入 ---
reset_runtime_verify_stubs
write_body "$STUB_HEALTH" $((2 * 1024 * 1024)) none
assert_fails 'runtime-verify/HUGE-postgres-marker-missing' "$RV_PRELUDE" "$RV_BODY" "${rv_args[@]}"

reset_runtime_verify_stubs
write_body "$STUB_CLASSIFIER" $((2 * 1024 * 1024)) none
assert_fails 'runtime-verify/HUGE-classifier-marker-missing' "$RV_PRELUDE" "$RV_BODY" "${rv_args[@]}"

reset_runtime_verify_stubs
write_cmdline $((2 * 1024 * 1024)) none
assert_fails 'runtime-verify/HUGE-main-pid-not-on-venv-current' "$RV_PRELUDE" "$RV_BODY" "${rv_args[@]}"

reset_runtime_verify_stubs
write_body "$STUB_UNIT_DIR/cretas-corpus-refresh" $((2 * 1024 * 1024)) none
assert_fails 'runtime-verify/HUGE-unit-contract-missing' "$RV_PRELUDE" "$RV_BODY" "${rv_args[@]}"

# 运行时选择器指向别的 venv → 必须拒
# (显式赋值再还原; 函数调用的前缀赋值在 bash 里是否残留取决于 posix 模式, 不依赖它)
reset_runtime_verify_stubs
export STUB_READLINK="$FAKE_REMOTE/venv999"
assert_fails 'runtime-verify/wrong-runtime-target' "$RV_PRELUDE" "$RV_BODY" "${rv_args[@]}"
export STUB_READLINK="$FAKE_REMOTE/venv311"
assert_ok 'runtime-verify/readlink-restored' "$RV_PRELUDE" "$RV_BODY" "${rv_args[@]}"

# ---------------------------------------------------------------------------
# 6. run_rsync: 失败时错误**头部**必须可见
# ---------------------------------------------------------------------------
RSYNC_LIB="$TMP_ROOT/rsync-helpers.sh"
extract_marked_block smartbi-rsync-helpers >"$RSYNC_LIB"
[ -s "$RSYNC_LIB" ] || die "抽不出 smartbi-rsync-helpers 块 (标记被删了?)"
grep -q '^# <<< smartbi-rsync-helpers$' "$RSYNC_LIB" || die "smartbi-rsync-helpers 块没有结束标记"

RSYNC_HEAD_ERROR='rsync: connection unexpectedly closed (0 bytes received so far) [sender]'
RSYNC_TAIL_NOISE='rsync error: unexplained error (code 255) at io.c(228) [sender=3.2.7]'

rsync_case() {
    # rsync_case <exit_code> <noise_lines>  → 打印 run_rsync 的合并输出, 回传其退出码
    local exit_code=$1 noise=$2 status=0
    local script="$TMP_ROOT/rsync-case.sh"
    {
        echo 'set -eo pipefail'
        cat "$RSYNC_LIB"
        cat <<PRELUDE
rsync() {
    echo '$RSYNC_HEAD_ERROR'
    i=0
    while [ "\$i" -lt $noise ]; do echo "sending incremental file list entry \$i"; i=\$((i + 1)); done
    echo '$RSYNC_TAIL_NOISE'
    return $exit_code
}
run_rsync "单测传输" -az /src/ host:/dst/
PRELUDE
    } >"$script"
    LAST_OUTPUT=$(bash "$script" 2>&1) || status=$?
    rm -f "$script"
    return "$status"
}

# 失败 + 大量噪音: 头部错误必须出现在输出里 (旧的 `| tail -5` 视角看不到它)
if rsync_case 255 200; then
    fail 'run_rsync/failure-propagates: rsync 失败却返回 0'
fi
case "$LAST_OUTPUT" in
    *"$RSYNC_HEAD_ERROR"*) : ;;
    *) fail 'run_rsync/head-visible: 失败输出里看不到 rsync 的头部错误' ;;
esac
# 反证: 旧的 tail -5 视角确实会砍掉头部错误 —— 否则这条测试没有意义
old_tail_view=$(printf '%s\n' "$LAST_OUTPUT" | tail -5 || true)
case "$old_tail_view" in
    *"$RSYNC_HEAD_ERROR"*) fail 'run_rsync/mutation: tail -5 竟然也能看到头部错误, 噪音行数不够' ;;
esac
# 尾部也要保留 (两端都给, 中间省略)
case "$LAST_OUTPUT" in
    *"$RSYNC_TAIL_NOISE"*) : ;;
    *) fail 'run_rsync/tail-kept: 失败输出里丢了尾部' ;;
esac

# 退出码原样透传
rsync_case 23 3 && fail 'run_rsync/exit-code: 期望非 0' || rsync_status=$?
[ "${rsync_status:-0}" -eq 23 ] || fail "run_rsync/exit-code: 期望 23 实际 ${rsync_status:-0}"

# 成功路径不得报错
rsync_ok_script="$TMP_ROOT/rsync-ok.sh"
{
    echo 'set -eo pipefail'
    cat "$RSYNC_LIB"
    echo 'rsync() { echo "sent 12 bytes"; return 0; }'
    echo 'run_rsync "单测传输" -az /src/ host:/dst/'
} >"$rsync_ok_script"
bash "$rsync_ok_script" >/dev/null 2>&1 || fail 'run_rsync/success: 成功路径不该失败'

# ---------------------------------------------------------------------------
# 7. Mutation: 把旧的 `printf | grep -Fq` 形状原样跑一遍, 证明大输入用例确实是陷阱。
#    如果这一条不再"给出错误答案", 说明输入构造得不够大, 整份测试就失去回归价值。
# ---------------------------------------------------------------------------
mutate_to_old_shape() {
    # `grep -Fq -- 'X' <<<"$var"` → `printf '%s' "$var" | grep -Fq 'X'`
    sed -E "s@^([[:space:]]*)grep -Fq -- (.+) <<<\"(\\\$[A-Za-z_][A-Za-z0-9_]*)\"@\1printf '%s' \"\3\" | grep -Fq \2@"
}

BH_MUTATED="$TMP_ROOT/business-health.mutated"
mutate_to_old_shape <"$BH_BODY" >"$BH_MUTATED"
mutated_count=$(grep -c "^[[:space:]]*printf '%s' .*| grep -Fq" "$BH_MUTATED" || true)
[ "$mutated_count" -eq 2 ] \
    || die "mutation 没把 BUSINESS_HEALTH 的 2 处改回旧形状 (实际 $mutated_count), 变异断言失效"

# 小输入: 旧形状照样是绿的 —— 这解释了 bug 为什么能长期潜伏
write_body "$STUB_HEALTH" 0 front "$HEALTH_OK"
write_body "$STUB_CLASSIFIER" 0 front "$CLASSIFIER_OK"
run_script "$BH_PRELUDE" "$BH_MUTATED" \
    || fail 'mutation/small: 旧形状在小输入下也失败了, 与"64KB 以下侥幸正确"的判断不符'

# 大输入: 旧形状必须翻车 (SIGPIPE 有竞态, 重试几次只要出现一次错误答案即可)
write_body "$STUB_HEALTH" $((2 * 1024 * 1024)) front "$HEALTH_OK"
write_body "$STUB_CLASSIFIER" $((2 * 1024 * 1024)) front "$CLASSIFIER_OK"
old_shape_wrong=false
for _ in 1 2 3 4 5; do
    if ! run_script "$BH_PRELUDE" "$BH_MUTATED"; then
        old_shape_wrong=true
        break
    fi
done
[ "$old_shape_wrong" = true ] \
    || fail 'mutation/HUGE: 旧的 `printf | grep -Fq` 形状在 MB 级输入上竟然答对了 — 输入不够大, 用例失去回归价值'

# 同一份大输入, 修好的实现必须是绿的
run_script "$BH_PRELUDE" "$BH_BODY" \
    || fail 'mutation/HUGE-fixed: 修复后的实现在 MB 级输入上没通过'

# RUNTIME_VERIFY 同样做一遍 (它有 4 处同形状的匹配)
RV_MUTATED="$TMP_ROOT/runtime-verify.mutated"
mutate_to_old_shape <"$RV_BODY" >"$RV_MUTATED"
rv_mutated_count=$(grep -c "^[[:space:]]*printf '%s' .*| grep -Fq" "$RV_MUTATED" || true)
[ "$rv_mutated_count" -eq 4 ] \
    || die "mutation 没把 RUNTIME_VERIFY 的 4 处改回旧形状 (实际 $rv_mutated_count)"

reset_runtime_verify_stubs
run_script "$RV_PRELUDE" "$RV_MUTATED" "${rv_args[@]}" \
    || fail 'mutation/runtime-verify-small: 旧形状在小输入下也失败了'

write_body "$STUB_HEALTH" $((2 * 1024 * 1024)) front "$HEALTH_OK"
write_body "$STUB_CLASSIFIER" $((2 * 1024 * 1024)) front "$CLASSIFIER_OK"
write_cmdline $((2 * 1024 * 1024)) front
for unit in cretas-python cretas-gold-etl-refresh cretas-corpus-refresh; do
    write_body "$STUB_UNIT_DIR/$unit" $((2 * 1024 * 1024)) front "$VENV_CURRENT_EXEC"
done
rv_old_wrong=false
for _ in 1 2 3 4 5; do
    if ! run_script "$RV_PRELUDE" "$RV_MUTATED" "${rv_args[@]}"; then
        rv_old_wrong=true
        break
    fi
done
[ "$rv_old_wrong" = true ] \
    || fail 'mutation/runtime-verify-HUGE: 旧形状在 MB 级输入上竟然答对了'

run_script "$RV_PRELUDE" "$RV_BODY" "${rv_args[@]}" \
    || fail 'mutation/runtime-verify-HUGE-fixed: 修复后的实现在 MB 级输入上没通过'

# ---------------------------------------------------------------------------
if [ "$FAILURES" -ne 0 ]; then
    echo "FAILED: $FAILURES 个断言不通过" >&2
    exit 1
fi
echo 'PASS: deploy-smartbi-python.sh 的健康/契约匹配无管道且在 MB 级输入下语义不变; rsync 失败头部可见'
