#!/usr/bin/env bash
# web dist 制品取回链路的结构闸。
#
# 这条链有三个特别容易悄悄失效的地方, 每一个在本仓库都有前科:
#   1. 开关只在某条路径生效 (#2031: --prefer-ci-artifact 当时只在 java-only 路径起作用,
#      功能形同不存在)。通向 web 构建的路径有三条, 所以预热必须插在它们共同落到的那个函数里。
#   2. 预热排在 ensure_dependencies 之后 —— npm ci 已经付掉了, 省下的只剩 vite build。
#   3. 东京侧两份 stager 的 URL 白名单各自演化 (安全边界漂移)。
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
FETCH="$ROOT_DIR/scripts/deploy/release-web-ci-artifact.sh"
WEB_MANIFEST="$ROOT_DIR/scripts/deploy/release-web-manifest.sh"
RELEASE="$ROOT_DIR/scripts/deploy/release-cretas.sh"
JAR_STAGER="$ROOT_DIR/scripts/deploy/lightsail/github-artifact-stage"
WEB_STAGER="$ROOT_DIR/scripts/deploy/lightsail/github-web-artifact-stage"
INVENTORY="$ROOT_DIR/scripts/deploy/server-script-inventory.conf"

pass_count=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { pass_count=$((pass_count + 1)); echo "  ok - $*"; }

for f in "$FETCH" "$WEB_STAGER"; do
    [ -f "$f" ] || fail "缺文件: $f"
    bash -n "$f" || fail "语法错误: $f"
done
ok "两个新脚本存在且可解析"

# ---- 1. 预热必须插在 web_release_build 里, 且排在两件事之前 ----
# 取 web_release_build 函数体的行号区间, 只在区间内找 —— 否则会匹配到别处的同名调用。
fn_start=$(grep -n '^web_release_build()' "$WEB_MANIFEST" | cut -d: -f1)
[ -n "$fn_start" ] || fail "找不到 web_release_build()"
fn_end=$(awk -v s="$fn_start" 'NR>s && /^}/ {print NR; exit}' "$WEB_MANIFEST")
[ -n "$fn_end" ] || fail "找不到 web_release_build() 的结尾"

# ⚠️ 必须跳过注释行。这几个名字在同一段注释里都被提到过(注释正是解释为什么要这个顺序的),
# 匹配到注释就会拿到一个与真实调用无关的行号 —— 第一版就这么把自己的注释当成了调用,
# 报出一个不存在的顺序错误。同类: 静态断言永远要先把注释剔掉。
line_in_fn() {
    awk -v s="$fn_start" -v e="$fn_end" -v pat="$1" '
        NR>=s && NR<=e {
            line = $0
            sub(/^[[:space:]]+/, "", line)
            if (substr(line, 1, 1) == "#") next
            if (index($0, pat)) { print NR; exit }
        }' "$WEB_MANIFEST"
}

warm_line=$(line_in_fn 'release-web-ci-artifact.sh')
reuse_line=$(line_in_fn 'web_release_build_reusable')
deps_line=$(line_in_fn 'web_release_ensure_dependencies')

[ -n "$warm_line" ] || fail "web_release_build 里没有调用 release-web-ci-artifact.sh —— 取回没有调用方"
ok "预热在 web_release_build 内被调用 (行 $warm_line)"

[ -n "$reuse_line" ] && [ "$warm_line" -lt "$reuse_line" ] \
    || fail "预热(行 $warm_line)必须排在 web_release_build_reusable(行 ${reuse_line:-?})之前"
ok "预热排在复用判断之前 ($warm_line < $reuse_line)"

[ -n "$deps_line" ] && [ "$warm_line" -lt "$deps_line" ] \
    || fail "预热(行 $warm_line)必须排在 web_release_ensure_dependencies(行 ${deps_line:-?})之前, 否则 npm ci 已经付掉了"
ok "预热排在 ensure_dependencies 之前 ($warm_line < $deps_line)"

# ---- 2. 命令行 flag 必须导出成环境变量, 否则 Java 生效而 Web 不生效 ----
grep -q 'export CRETAS_RELEASE_PREFER_CI_ARTIFACT=1' "$RELEASE" \
    || fail "release-cretas.sh 没有把 --prefer-ci-artifact 导出成环境变量 —— Web 侧读不到"
ok "--prefer-ci-artifact 会被导出给 Web 侧"

# ---- 3. 东京侧两份 stager 的 URL 白名单必须逐字节一致 ----
# 两个脚本刻意分开(不动已在生产承重的 JAR 那份), 代价就是这段安全边界会重复。
# 重复本身可接受, 悄悄分叉不可接受。
extract_allowlist() {
    awk '/^import ipaddress$/{on=1} on{print} /raise SystemExit\(1\)$/{if(on)n++} on&&n>=3{exit}' "$1"
}
jar_list=$(extract_allowlist "$JAR_STAGER")
web_list=$(extract_allowlist "$WEB_STAGER")
[ -n "$jar_list" ] || fail "从 github-artifact-stage 取不到白名单(形态变了?)"
[ "$jar_list" = "$web_list" ] \
    || { diff <(printf '%s\n' "$jar_list") <(printf '%s\n' "$web_list") >&2 || true
         fail "两份 stager 的 URL 白名单已分叉"; }
ok "两份 stager 的 URL 白名单逐字节一致"

# ---- 4. 新服务器脚本必须登记进清单 ----
grep -q '^tokyo|/usr/local/sbin/github-web-artifact-stage|scripts/deploy/lightsail/github-web-artifact-stage|' "$INVENTORY" \
    || fail "github-web-artifact-stage 没登记进 server-script-inventory.conf, 漂移检查看不到它"
ok "已登记进服务器脚本清单"

# ---- 5. locale 陷阱: 取回脚本会被 LC_ALL=C 的统一入口调用 ----
offenders=$(grep -nE '(^|[^[:alnum:]_-])grep([[:space:]]+-[[:alnum:]]*P)' "$FETCH" \
    | grep -vE '^[0-9]+:[[:space:]]*#' || true)
[ -z "$offenders" ] || { printf '%s\n' "$offenders" >&2; fail "取回脚本用了 grep -P, LC_ALL=C 下会失效"; }
ok "取回脚本无 grep -P"

# ---- 6. 失败必须说出原因, 不能静默回退 ----
grep -q 'WEB_CI_ARTIFACT_UNAVAILABLE reason=' "$FETCH" \
    || fail "取回脚本失败时不打 reason —— 静默回退会让这条链"从没生效"长期无人发现"
grep -q 'WEB_CI_ARTIFACT=fallback' "$WEB_MANIFEST" \
    || fail "web_release_build 回退到本地构建时没有可见标记"
ok "失败路径可见 (reason + fallback 标记)"

# ---- 7. 制品必须按内容树认, 且 build_commit 本地可解析 ----
# 后者是那个"build 报复用成功 2s 返回, 部署期 validate exit 1 且一个字都不打"的根因。
grep -q 'manifest_commit_unresolvable_locally' "$FETCH" \
    || fail "取回脚本没有校验 build_commit 本地可解析"
grep -q 'manifest_commit_tree_mismatch' "$FETCH" \
    || fail "取回脚本没有校验 build_commit 的 web 树等于当前树"
ok "build_commit 可解析性与树一致性都有断言"

echo "PASS: ${pass_count} 项断言全部通过"
