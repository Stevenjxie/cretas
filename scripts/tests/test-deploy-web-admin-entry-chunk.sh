#!/usr/bin/env bash
# entry chunk 可达性验证的回归闸。
#
# 背景(2026-07-30 一次真实 prod 发布中暴露): `release-cretas.sh` 必须以 LC_ALL=C 运行,
# 否则 Java preflight 里的 [A-Z] 会按 collation 展开匹配小写、假报 "import 无法解析"。
# 而 GNU grep 在 C locale 下【直接拒绝】-P("supports only unibyte and UTF-8 locales"),
# 于是 deploy-web-admin.sh 里那句 `grep -oP` 提取 entry chunk 恒为空 —— 走统一入口的每一次
# 发布都会跳过这一项, 却只打一行看起来像偶发的 warning。
#
# 更糟的是: 当时 LOCAL_ENTRY_HASH 全脚本只有"赋值 / 判空 / 告警"三行, 【没有任何消费者】。
# 那句"跳过 post-deploy 内容验证"描述的是一项从未实现过的验证。
#
# 这两件事本机都不容易复现(取决于调用方 locale, 且失败表现为少做一项而非报错), 所以按本仓库
# 既有先例(见 release-cretas.sh 顶部 matches_any_line 的 SIGPIPE 注释), 用静态断言直接禁写法。
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
DEPLOY_SCRIPT="$ROOT_DIR/scripts/deploy/deploy-web-admin.sh"

pass_count=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { pass_count=$((pass_count + 1)); echo "  ok - $*"; }

[ -f "$DEPLOY_SCRIPT" ] || fail "找不到 $DEPLOY_SCRIPT"

# ---- 1. 静态禁令: deploy-web-admin.sh 不得使用 grep -P ----
# 只匹配 grep 自己的 -P/-oP 选项, 不误伤别的命令。
# ⚠️ 注释行必须排掉 —— 本文件和被测脚本都在注释里提到这个写法。`grep -n` 会加 `NNN:` 前缀,
# 所以剥注释的模式必须连行号一起写, 否则 `^[[:space:]]*#` 永远匹配不上(第一版就栽在这)。
offenders=$(grep -nE '(^|[^[:alnum:]_-])grep([[:space:]]+-[[:alnum:]]*P)' "$DEPLOY_SCRIPT" \
    | grep -vE '^[0-9]+:[[:space:]]*#' || true)
if [ -n "$offenders" ]; then
    printf '%s\n' "$offenders" >&2
    fail "deploy-web-admin.sh 出现 grep -P —— LC_ALL=C 下会被 GNU grep 拒绝, 提取恒为空"
fi
ok "无 grep -P (locale 陷阱)"

# ---- 2. LOCAL_ENTRY_HASH 必须有真实消费者, 不能退回死代码 ----
grep -q 'verify_entry_chunk_reachable()' "$DEPLOY_SCRIPT" \
    || fail "verify_entry_chunk_reachable 未定义"
ok "verify_entry_chunk_reachable 已定义"

# 定义之外还必须【被调用】。定义行形如 `verify_entry_chunk_reachable() {`, 调用行没有 `()`。
call_count=$(grep -cE '^[[:space:]]*(if[[:space:]]+!?[[:space:]]*)?verify_entry_chunk_reachable[[:space:]]+' \
    "$DEPLOY_SCRIPT" || true)
[ "$call_count" -ge 1 ] \
    || fail "verify_entry_chunk_reachable 定义了但从没被调用 —— 又变回死代码"
ok "verify_entry_chunk_reachable 被调用 ${call_count} 次"

# 失败必须能传出去: 该函数失败路径 return 非 0, 且脚本处于 set -e 之下。
grep -q '^set -e' "$DEPLOY_SCRIPT" || fail "deploy-web-admin.sh 缺 set -e, 闸门失败不会中止发布"
grep -q 'WEB_ENTRY_CHUNK=failed' "$DEPLOY_SCRIPT" || fail "缺 WEB_ENTRY_CHUNK=failed 状态行"
ok "失败可传播 (set -e + WEB_ENTRY_CHUNK=failed)"

# ---- 3. 行为断言: 脚本里真正用的那条表达式, 两种 locale 下都要抽得出 entry chunk ----
# 直接从脚本里取表达式, 避免测试与实现漂移。
entry_expr=$(sed -n "s/^LOCAL_ENTRY_HASH=\$(grep -oE '\(.*\)' \"\$TMP_INDEX\".*/\1/p" "$DEPLOY_SCRIPT")
[ -n "$entry_expr" ] || fail "取不到脚本里的 entry chunk 提取表达式(实现形态变了?)"

tmp_index=$(mktemp)
trap 'rm -f "$tmp_index"' EXIT
cat > "$tmp_index" <<'HTML'
<!doctype html><html><head>
<script type="module" crossorigin src="/assets/index-C7vDbcK2.js"></script>
<link rel="stylesheet" crossorigin href="/assets/index-DzbWknMj.css">
</head><body><div id="app"></div></body></html>
HTML

for loc in C en_US.UTF-8; do
    got=$(LC_ALL=$loc grep -oE "$entry_expr" "$tmp_index" 2>/dev/null | head -1 || true)
    [ "$got" = "assets/index-C7vDbcK2.js" ] \
        || fail "LC_ALL=$loc 下提取结果为 '$got', 期望 assets/index-C7vDbcK2.js"
    ok "LC_ALL=$loc 提取正确 ($got)"
done

# ---- 4. 变异检验: 证明上面的断言不是空转 ----
# 先断言"变异本身生效"再看结果 —— 本仓库反复栽在跳过这一步上。
old_form=$(LC_ALL=C grep -oP 'assets/index-[A-Za-z0-9_-]+\.js' "$tmp_index" 2>/dev/null | head -1 || true)
[ -z "$old_form" ] \
    || fail "本机 LC_ALL=C 下 grep -P 竟然可用 —— 本测试的前提在此环境不成立, 需重新评估"
ok "变异检验: 旧写法 grep -oP 在 LC_ALL=C 下确实提取为空(缺陷可复现)"

echo "PASS: ${pass_count} 项断言全部通过"
