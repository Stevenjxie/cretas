#!/usr/bin/env bash
# stage-backend-artifact.sh 把 jar 传到服务器 SHA-256 缓存。rsync 走 SSH 时 delta
# 算法默认开启, 但它需要【目标端已存在同名文件】当基准 —— 而这里的目标名含
# $jar_sha (每次构建必变) 和 $$ (PID 必变), 成功后还立刻 `mv` 走。基准 100% 不存在,
# 每次 stage 都在全量传 168MB。
#
# Spring Boot fat jar 的内嵌依赖 jar 是 STORED 未压缩存放的, 两次构建之间大部分逐
# 字节相同。2026-07-29 在服务器上对两次连续发布的 jar 实测:
#     Literal 21,396,082 / Matched 154,884,240 / speedup 8.91×
# 也就是有基准时只需要传 ~20MB。deploy-backend.sh 早就用同一个缓存目录种基准了
# (见 scripts/tests/test-deploy-upload-delta.sh), 这个脚本没用上。
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
STAGE_SCRIPT="$ROOT_DIR/scripts/deploy/stage-backend-artifact.sh"
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

[ -f "$STAGE_SCRIPT" ] || fail "stage-backend-artifact.sh is gone"
bash -n "$STAGE_SCRIPT" || fail "stage-backend-artifact.sh does not parse"

# ------------------------------------------------------- wiring contracts
# `|| true` on every grep capture: under `set -e -o pipefail` a non-matching grep
# would abort this script with no output at all, turning a real regression into a
# silent exit instead of a named failure.
grep -Fq 'seed_rsync_delta_basis()' "$STAGE_SCRIPT" \
    || fail "delta basis seeding helper is gone"

seed_call_line=$(grep -Fn 'seed_rsync_delta_basis "$remote_tmp"' "$STAGE_SCRIPT" | cut -d: -f1 || true)
[ -n "$seed_call_line" ] || fail "seeding is never invoked"
[ "$(printf '%s\n' "$seed_call_line" | wc -l | tr -d '[:space:]')" = "1" ] \
    || fail "seeding is invoked more than once"

# 主通道 rsync: -z 与 deploy-backend.sh 主通道一致, --stats 是 delta 是否兑现的唯一直证。
rsync_line=$(grep -Fn 'rsync -az --stats --timeout=90' "$STAGE_SCRIPT" | cut -d: -f1 || true)
[ -n "$rsync_line" ] || fail "upload rsync lost -z / --stats / its timeout"

# 种基准必须发生在依赖它的那条 rsync 之前 —— 种在后面等于没种。
[ "$seed_call_line" -lt "$rsync_line" ] \
    || fail "basis is seeded after the rsync that needs it (line $seed_call_line vs $rsync_line)"

# 缓存目录必须先 mkdir 再种基准, 否则首次 stage 的 cp 落到不存在的目录。
mkdir_line=$(grep -Fn "mkdir -p '\$REMOTE_CACHE_DIR'" "$STAGE_SCRIPT" | cut -d: -f1 || true)
[ -n "$mkdir_line" ] || fail "remote cache dir is no longer created before upload"
[ "$mkdir_line" -lt "$seed_call_line" ] \
    || fail "cache dir is created after the seed that writes into it"

# --stats 的产出必须真的被读出来, 否则等于没加。
report_call_line=$(grep -Fn 'report_rsync_delta "$stats_log"' "$STAGE_SCRIPT" | cut -d: -f1 || true)
[ -n "$report_call_line" ] || fail "--stats output is captured but never reported"
[ "$report_call_line" -gt "$rsync_line" ] \
    || fail "delta stats are reported before the rsync that produces them"

# ------------------------------------------------ seeding must never be fatal
# 缓存为空 / ssh 不通 / jar 不可读, 都必须静默退回全量传输, 不能让 stage 失败。
seed_body=$(awk '/^seed_rsync_delta_basis\(\) \{/,/^\}/' "$STAGE_SCRIPT")
[ -n "$seed_body" ] || fail "could not extract seed_rsync_delta_basis body"
# 锚在 ssh 调用自己的收尾引号上, 而不是函数体里随便一处 `|| true`: 内层的
# `cp ... 2>/dev/null || true` 会满足松散匹配, 让【删掉外层容错】的改动蒙混过关
# (这是变异测试实际抓到的失效断言形态)。
grep -Fq '" 2>/dev/null || true' <<<"$seed_body" \
    || fail "the ssh seeding call can abort the stage instead of degrading to a full transfer"
grep -Fq 'exit 0' <<<"$seed_body" \
    || fail "seeding does not short-circuit on an empty artifact cache"
# 基准必须取【最近一次】的缓存 jar, 取错了 delta 命中率会塌。
grep -Fq 'ls -t' <<<"$seed_body" || fail "seed no longer picks the most recent cached jar"
grep -Fq 'head -1' <<<"$seed_body" || fail "seed no longer narrows to a single basis jar"

# ------------------------------------------- integrity guarantees must survive
# delta 只影响传输效率, 不允许弱化任何一条既有校验或原子发布。
grep -Fq 'unzip -tqq' "$STAGE_SCRIPT" || fail "remote zip integrity check was dropped"
grep -Fq "chmod 0444" "$STAGE_SCRIPT" || fail "staged artifact is no longer made read-only"
grep -Fq "mv -f '\$remote_tmp' '\$remote_path'" "$STAGE_SCRIPT" \
    || fail "atomic publish into the SHA-256 cache was dropped"
grep -Fq "actual=\\\$(sha256sum '\$remote_tmp'" "$STAGE_SCRIPT" \
    || fail "remote SHA-256 verification was dropped"
# 种过基准后 rsync 失败会在缓存目录留下整份 168MB 残留 —— 必须清掉。
grep -Fq "rm -f '\$remote_tmp'" "$STAGE_SCRIPT" \
    || fail "a failed seeded upload leaks a full-size basis file in the cache dir"

# ------------------------------------------------- behavioural: seed semantics
# 复刻 helper 的远端 body: 取最新缓存 jar / 空缓存静默 no-op / 缓存目录不存在也不报错。
cache="$TMP_ROOT/cache"
mkdir -p "$cache"
target="$TMP_ROOT/.deadbeef.1234"

seed_local() {
    local cache_dir=$1 dest=$2 newest
    newest=$(ls -t "$cache_dir"/*.jar 2>/dev/null | head -1) || true
    [ -n "$newest" ] || return 0
    cp -f "$newest" "$dest" 2>/dev/null || true
}

seed_local "$cache" "$target" || fail "empty cache made seeding fail"
[ ! -f "$target" ] || fail "empty cache still produced a basis file"

printf 'older jar\n' > "$cache/aaa.jar"
sleep 1
printf 'newest jar payload\n' > "$cache/zzz.jar"
# 上一轮 stage 留下的点开头临时文件不得被当成基准 (它不以 .jar 结尾)。
printf 'half-uploaded garbage\n' > "$cache/.cafebabe.999"
seed_local "$cache" "$target" || fail "seeding failed with a populated cache"
[ -f "$target" ] || fail "basis file was not created"
cmp -s "$target" "$cache/zzz.jar" \
    || fail "seed used a stale cache entry instead of the newest jar"

rm -f "$target"
seed_local "$TMP_ROOT/nope" "$target" || fail "missing cache dir made seeding fail"
[ ! -f "$target" ] || fail "missing cache dir still produced a basis file"

# --------------------------------------------- behavioural: --stats parsing
# 直接把脚本里的 report_rsync_delta 抠出来跑, 断言解析的是真实实现而不是副本。
eval "$(awk '/^report_rsync_delta\(\) \{/,/^\}/' "$STAGE_SCRIPT")"
declare -F report_rsync_delta >/dev/null || fail "could not load report_rsync_delta from the script"

stats_with_commas="$TMP_ROOT/stats-commas.log"
cat > "$stats_with_commas" <<'EOF'
Number of files: 1 (reg: 1)
Number of regular files transferred: 1
Total file size: 176,280,322 bytes
Total transferred file size: 176,280,322 bytes
Literal data: 21,396,082 bytes
Matched data: 154,884,240 bytes
File list size: 0

sent 21,442,113 bytes  received 2,321,169 bytes  1,077,876.45 bytes/sec
total size is 176,280,322  speedup is 8.91
EOF
out=$(report_rsync_delta "$stats_with_commas")
grep -Fq 'literal=21396082' <<<"$out" || fail "literal bytes not parsed/normalised: $out"
grep -Fq 'matched=154884240' <<<"$out" || fail "matched bytes not parsed/normalised: $out"
grep -Fq 'speedup=8.91' <<<"$out" || fail "speedup not parsed: $out"

# LC_ALL=C 的 rsync 不打千分位, 同样要能解析。
stats_plain="$TMP_ROOT/stats-plain.log"
cat > "$stats_plain" <<'EOF'
Literal data: 21396082 bytes
Matched data: 154884240 bytes
total size is 176280322  speedup is 8.91
EOF
out=$(report_rsync_delta "$stats_plain")
grep -Fq 'literal=21396082' <<<"$out" || fail "unseparated stats output not parsed: $out"

# 没有 stats 内容 / 文件不存在: 静默返回, 不能报错也不能打半行垃圾。
empty_log="$TMP_ROOT/empty.log"
: > "$empty_log"
out=$(report_rsync_delta "$empty_log") || fail "empty stats log made reporting fail"
[ -z "$out" ] || fail "empty stats log still printed a delta line: $out"
# 2>&1: 少了 `[ -f ]` 前置守卫时行为几乎一样 (解析为空 → 提前 return), 唯一可观测差别
# 是 sed 会往 stderr 喷 "can't read ...", 把噪音混进 stage 日志。不捕获 stderr 的话
# 这条断言杀不掉那个变异 (变异测试实测漏网)。
out=$(report_rsync_delta "$TMP_ROOT/does-not-exist.log" 2>&1) || fail "missing stats log made reporting fail"
[ -z "$out" ] || fail "missing stats log still produced output: $out"

echo "PASS: stage-backend-artifact rsync delta basis seeding + --stats observability"
