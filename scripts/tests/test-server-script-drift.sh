#!/usr/bin/env bash
# check-server-script-drift.sh 的测试。
#
# CI 到不了那两台服务器 (也不该持有它们的 key), 所以这里测的是不依赖 ssh 的部分:
# 参数纪律、退出码语义、清单完整性。真正的跨境对比要人工跑一次工具本体。
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
TOOL="$ROOT_DIR/scripts/deploy/check-server-script-drift.sh"
INSTALLER="$ROOT_DIR/scripts/deploy/install-server-scripts.sh"
LIB="$ROOT_DIR/scripts/lib/server-script-common.sh"
INVENTORY="$ROOT_DIR/scripts/deploy/server-script-inventory.conf"

fail() { echo "FAIL: $*" >&2; exit 1; }

assert_contains() {
    grep -Fq -- "$2" "$1" || fail "missing '$2' in $1"
}

assert_exit() {
    local expected=$1; shift
    local actual=0
    "$@" >/dev/null 2>&1 || actual=$?
    [ "$actual" = "$expected" ] || fail "expected exit $expected, got $actual: $*"
}

[ -x "$TOOL" ] || fail "$TOOL 不存在或不可执行"
[ -x "$INSTALLER" ] || fail "$INSTALLER 不存在或不可执行"
[ -s "$LIB" ] || fail "$LIB 不存在"
[ -s "$INVENTORY" ] || fail "$INVENTORY 不存在"
for f in "$TOOL" "$INSTALLER" "$LIB"; do bash -n "$f" || fail "语法错误: $f"; done

# ---- 0. 清单与公共逻辑必须是单一来源 ----
# 检查器与安装器各存一份就等于又造了一个漂移源, 而它们存在的理由正是消除漂移。
for f in "$TOOL" "$INSTALLER"; do
    assert_contains "$f" 'scripts/lib/server-script-common.sh'
    assert_contains "$f" 'server-script-inventory.conf'
done
# 公共函数只能在 lib 里定义一次
for fn in server_script_remote_fetch server_script_host_opts server_script_norm_sha; do
    n=$(grep -c "^$fn()" "$LIB" "$TOOL" "$INSTALLER" 2>/dev/null | awk -F: '{s+=$2} END{print s+0}')
    [ "$n" = "1" ] || fail "$fn 定义了 $n 次 (应恰好 1 次, 在 lib 里)"
done

# ---- 1. ssh 必须带 -n ----
# 主循环是 `while read ... <<< "$INVENTORY"`。不带 -n 的 ssh 会从同一个 stdin 读走
# 清单剩余全部行, 循环只跑第一条就结束, 然后打印「✅ 一致」并 exit 0。第一版实测
# 就是这样只查了 11 条里的 1 条。这条断言是那个 bug 的回归闸。
assert_contains "$LIB" "printf '%s\\n' -n -o BatchMode=yes"

# ---- 2. 「查不出来」与「查出来是坏的」必须是不同退出码 ----
assert_contains "$TOOL" 'n_unreadable > 0'
assert_contains "$TOOL" 'exit 2'
assert_contains "$TOOL" 'n_drift > 0 || n_missing_repo > 0 || n_missing_server > 0'

# ---- 3. REPO_ROOT 解析失败必须拒绝出报告 ----
# 否则每一条都会因为"仓库里找不到文件"静默变成 MISSING_IN_REPO —— 一份看起来
# 言之有据的假报告。
assert_contains "$TOOL" 'error=repo_root_unresolved'
TMP_COPY=$(mktemp -d)
cp "$TOOL" "$TMP_COPY/drift.sh"
assert_exit 2 bash "$TMP_COPY/drift.sh"
rm -rf "$TMP_COPY"

# ---- 4. 参数纪律 ----
assert_exit 2 bash "$TOOL" --nonexistent-flag
assert_exit 2 bash "$TOOL" --host          # 缺值
assert_exit 2 bash "$TOOL" --host nosuchhost

# ---- 5. 远端探测必须自带字节数核对 ----
# 「ssh 成功但 sudo 失败给了空内容」不能冒充一份干净的对比结果。
assert_contains "$LIB" 'size_mismatch_declared'
assert_contains "$LIB" "printf 'PRESENT %s"

# ---- 6. 清单完整性: 每个仓库跟踪的服务器脚本都必须在清单里登记 ----
# 这条不需要 ssh, 是这个测试里唯一能防"仓库侧新增脚本却忘了登记"的闸。
# 忘了登记 = 工具永远不检查它 = 又一个"机制存在但没人在检查脱节"。
missing_registration=""
while IFS= read -r tracked; do
    base=$(basename "$tracked")
    grep -Fq "|$tracked|" "$INVENTORY" || missing_registration="$missing_registration $base"
done < <(
    cd "$ROOT_DIR" && git ls-files 'scripts/deploy/lightsail/*' 'scripts/deploy/ecs/*'
)
[ -z "$missing_registration" ] || \
    fail "以下服务器脚本已被仓库跟踪但未登记进 check-server-script-drift.sh 清单:$missing_registration"

# ---- 7. 结构性噪音必须被忽略 ----
# 安装备份 (.bak.<ts>) 与 __pycache__ 是正常产物, 不是漂移。ECS 上现有 5 个 .bak。
assert_contains "$LIB" '*.bak.*|__pycache__|.*'

# ---- 8. 安装器: 拒绝覆盖漂移是最重要的一道闸 ----
# 2026-07-30 事故: 有人在 ECS 上加固了信任模型却从未提交, 另一个 session 用仓库版本覆盖装上去,
# 把 deployable_trust_verified 改回 true —— 等于重新装回一个漏洞。默认必须拒绝。
assert_contains "$INSTALLER" 'REFUSED_DRIFT'
assert_contains "$INSTALLER" 'if ((!ACCEPT_DRIFT)); then'
assert_contains "$INSTALLER" '服务器更严格 → 把加固取回仓库'
# 默认 dry-run: 没有 --confirm 就不许写
assert_contains "$INSTALLER" 'YES-INSTALL'
assert_contains "$INSTALLER" 'dry-run: 未写入任何东西'
# 覆盖前必须备份, 且沿用人工惯例的 .bak.<UTC时间戳>
assert_contains "$INSTALLER" 'cp -p '"'"'$remote'"'"' '"'"'$remote.bak.$TS'"'"''
# 装完必须自证落地 —— 装了却不确认, 正是这套工具要消除的那种脱节
assert_contains "$INSTALLER" 'check-server-script-drift.sh'
assert_contains "$INSTALLER" '装完但漂移检查未通过'
assert_exit 2 bash "$INSTALLER" --confirm NOPE
assert_exit 2 bash "$INSTALLER" --nonexistent-flag
assert_exit 2 bash "$INSTALLER" --only

echo "PASS: test-server-script-drift.sh"
