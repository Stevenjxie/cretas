#!/usr/bin/env bash
set -euo pipefail

# release-cretas.sh 的闸匹配单测。
#
# 背景 (被修的 bug): 每个闸原先都是
#     if printf '%s\n' "$text" | grep -q PATTERN; then ...
# 在 release-cretas.sh 顶部的 `set -o pipefail` 下这是**静默错误**的:
# grep -q 一命中就退出并关掉读端 → printf 死于 SIGPIPE (141) → pipefail 把整条
# 流水线判为失败 → if 走 else 分支 → **命中被报告成没命中**。
# 输入小于 64KB 管道缓冲时生产者能抢在 grep 退出前写完, 于是小样本侥幸正确;
# 而喂给 Repository-query 闸的 backend 全量 diff 常有几 MB, 必错。
#
# 这份测试的核心就是「大输入」: 每个闸都用 >64KB / MB 级输入跑一遍。旧实现在这些
# 用例上会给出相反的答案 (文末的 mutation 断言直接把旧形状跑一遍来证明这一点)。

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
SOURCE_SCRIPT="$ROOT_DIR/scripts/deploy/release-cretas.sh"
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

# ---------------------------------------------------------------------------
# 1. 从生产脚本里原样抽出匹配层, 单测跑的是**真实的函数和真实的模式**, 不是副本。
# ---------------------------------------------------------------------------
LIB="$TMP_ROOT/release-match-helpers.sh"
sed -n '/^# >>> release-match-helpers/,/^# <<< release-match-helpers/p' "$SOURCE_SCRIPT" >"$LIB"
[ -s "$LIB" ] || die "无法从 $SOURCE_SCRIPT 抽出 release-match-helpers 块 (标记被删了?)"
grep -q '^# <<< release-match-helpers' "$LIB" \
    || die "release-match-helpers 块没有结束标记, 抽取结果不完整"
# shellcheck source=/dev/null
. "$LIB"

for symbol in matches_any_line matches_any_line_ci select_matching_lines; do
    declare -F "$symbol" >/dev/null || die "抽出的块里缺少函数 $symbol"
done
for symbol in JAVA_PATH_PATTERN WEB_PATH_PATTERN RISK_MIGRATION_PATTERN \
    RISK_ENTITY_PATTERN RISK_REPOSITORY_PATTERN RISK_SECURITY_PATTERN \
    RISK_API_CONTRACT_PATTERN RISK_CONFIG_PATTERN RISK_WEB_CONTRACT_PATTERN \
    RISK_QUERY_DIFF_PATTERN; do
    [ -n "${!symbol:-}" ] || die "抽出的块里缺少模式常量 $symbol"
done

# ---------------------------------------------------------------------------
# 2. 回归哨兵: 生产脚本里不允许再出现 `... | grep` 这个形状 (注释除外)。
# ---------------------------------------------------------------------------
offenders=$(grep -n '| *grep' "$SOURCE_SCRIPT" | grep -v '^[0-9]\+:[[:space:]]*#' || true)
[ -z "$offenders" ] || fail "release-cretas.sh 里仍有管道进 grep 的代码:"$'\n'"$offenders"

# ---------------------------------------------------------------------------
# 3. 断言工具
# ---------------------------------------------------------------------------
assert_match() {
    local label=$1 pattern=$2 text=$3
    matches_any_line "$pattern" "$text" || fail "$label: 应命中却没命中"
}

assert_no_match() {
    local label=$1 pattern=$2 text=$3
    ! matches_any_line "$pattern" "$text" || fail "$label: 不应命中却命中了"
}

assert_match_ci() {
    local label=$1 pattern=$2 text=$3
    matches_any_line_ci "$pattern" "$text" || fail "$label: 应命中却没命中"
}

assert_no_match_ci() {
    local label=$1 pattern=$2 text=$3
    ! matches_any_line_ci "$pattern" "$text" || fail "$label: 不应命中却命中了"
}

assert_eq() {
    local label=$1 expected=$2 actual=$3
    [ "$expected" = "$actual" ] || fail "$label: 期望 [$expected] 实际 [$actual]"
}

# 生成 >= $1 字节的中性填充文本。中性 = 不命中任何一个闸的模式。
# 用倍增而不是逐行 append, 否则构造 MB 级字符串会退化成 O(n^2)。
PAD_UNIT='docs/filler/neutral-padding-line-that-trips-no-release-gate-whatsoever.txt'
make_padding() {
    local min_bytes=$1 out=$PAD_UNIT
    while [ "${#out}" -lt "$min_bytes" ]; do out="$out"$'\n'"$out"; done
    printf '%s' "$out"
}

PIPE_BUF=65536
SMALL_PAD=$(make_padding 512)
BIG_PAD=$(make_padding $((PIPE_BUF * 4)))          # ~256KB, 远超管道缓冲
HUGE_PAD=$(make_padding $((2 * 1024 * 1024)))      # ~2MB, 逼近真实 backend diff 体积

[ "${#BIG_PAD}" -gt "$PIPE_BUF" ] || die "BIG_PAD 没超过管道缓冲, 大输入用例无效"
[ "${#HUGE_PAD}" -gt $((1024 * 1024)) ] || die "HUGE_PAD 太小, MB 级用例无效"

# 填充本身必须对每个闸都是"不命中", 否则后面的大输入用例会假阳性。
assert_no_match 'padding/java-path' "$JAVA_PATH_PATTERN" "$BIG_PAD"
assert_no_match 'padding/web-path' "$WEB_PATH_PATTERN" "$BIG_PAD"
for p in "$RISK_MIGRATION_PATTERN" "$RISK_ENTITY_PATTERN" "$RISK_REPOSITORY_PATTERN" \
    "$RISK_SECURITY_PATTERN" "$RISK_API_CONTRACT_PATTERN" "$RISK_CONFIG_PATTERN" \
    "$RISK_WEB_CONTRACT_PATTERN" "$RISK_QUERY_DIFF_PATTERN"; do
    assert_no_match_ci "padding/[$p]" "$p" "$BIG_PAD"
done

# 每个闸: (命中样例, 不命中样例)。命中样例放在**第一行**, 后面接大填充 —— 这正是
# 旧实现最容易翻车的形状 (grep 立刻命中退出, printf 还剩几 MB 没写完)。
check_gate() {
    local label=$1 pattern=$2 hit=$3 miss=$4 ci=$5
    local m=assert_match nm=assert_no_match
    if [ "$ci" = ci ]; then m=assert_match_ci; nm=assert_no_match_ci; fi

    # 小输入 (旧实现在这里也是对的, 用于确认样例本身选得对)
    "$m" "$label/small-hit" "$pattern" "$hit"
    "$nm" "$label/small-miss" "$pattern" "$miss"

    # 大输入 —— 修复的核心。命中行在最前, 后面 256KB 填充。
    "$m" "$label/big-hit-first-line" "$pattern" "$hit"$'\n'"$BIG_PAD"
    # 命中行在最后 (grep 读完全部输入才命中): 这条在旧实现下也对, 用来确认
    # 大输入本身不会让匹配"多命中"。
    "$m" "$label/big-hit-last-line" "$pattern" "$BIG_PAD"$'\n'"$hit"
    # 大输入 + 无命中: 确认修复没有把闸改宽。
    "$nm" "$label/big-miss" "$pattern" "$BIG_PAD"$'\n'"$miss"
}

# ---------------------------------------------------------------------------
# 4. 逐闸语义等价 (每个闸给一条会命中、一条不会命中的样例)
# ---------------------------------------------------------------------------
check_gate 'java-path' "$JAVA_PATH_PATTERN" \
    'backend/java/cretas-api/src/main/java/com/cretas/aims/service/Thing.java' \
    'backend/python/app/main.py' cs
# 行首锚定: 路径里出现该前缀但不在行首, 必须不命中。
assert_no_match 'java-path/not-anchored' "$JAVA_PATH_PATTERN" \
    'docs/backend/java/cretas-api/notes.md'

check_gate 'web-path' "$WEB_PATH_PATTERN" \
    'web-admin/src/app.ts' \
    'frontend/CretasFoodTrace/App.tsx' cs
assert_no_match 'web-path/not-anchored' "$WEB_PATH_PATTERN" 'docs/web-admin/readme.md'

check_gate 'migration' "$RISK_MIGRATION_PATTERN" \
    'backend/java/cretas-api/src/main/resources/db/migration/V2__x.sql' \
    'backend/java/cretas-api/src/main/java/com/cretas/aims/service/Thing.java' ci
assert_match_ci 'migration/no-db-segment' "$RISK_MIGRATION_PATTERN" \
    'backend/java/cretas-api/src/main/resources/migration/V3__y.sql'
assert_match_ci 'migration/flyway-word' "$RISK_MIGRATION_PATTERN" \
    'backend/java/cretas-api/src/main/resources/flyway.conf'

check_gate 'entity' "$RISK_ENTITY_PATTERN" \
    'backend/java/cretas-api/src/main/java/com/cretas/aims/entity/Thing.java' \
    'backend/java/cretas-api/src/main/java/com/cretas/aims/service/Thing.java' ci
# `Entity\.java$` 是**逐行**行尾锚定。这条同时是"为什么不用 [[ =~ ]] 重写"的证据:
# bash 的 =~ 在多行字符串上把 $ 当整串结尾, 非最后一行的命中会被漏掉。
entity_mid_line='backend/java/cretas-api/src/main/java/com/cretas/aims/model/ThingEntity.java'$'\n''docs/readme.md'
assert_match_ci 'entity/suffix-on-non-final-line' "$RISK_ENTITY_PATTERN" "$entity_mid_line"
if [[ $entity_mid_line =~ $RISK_ENTITY_PATTERN ]]; then
    fail 'entity: bash [[ =~ ]] 竟然按行锚定了 $ — 本注释所依据的前提需要重新核对'
fi
assert_no_match_ci 'entity/suffix-not-at-eol' "$RISK_ENTITY_PATTERN" \
    'backend/java/cretas-api/src/main/java/com/cretas/aims/model/ThingEntity.java.bak'

check_gate 'repository' "$RISK_REPOSITORY_PATTERN" \
    'backend/java/cretas-api/src/main/java/com/cretas/aims/repository/ThingRepository.java' \
    'backend/java/cretas-api/src/main/java/com/cretas/aims/service/Thing.java' ci
assert_match_ci 'repository/suffix-only' "$RISK_REPOSITORY_PATTERN" \
    'backend/java/cretas-api/src/main/java/com/cretas/aims/data/ThingRepository.java'

check_gate 'security' "$RISK_SECURITY_PATTERN" \
    'backend/java/cretas-api/src/main/java/com/cretas/aims/security/AuthFilter.java' \
    'backend/java/cretas-api/src/main/java/com/cretas/aims/service/Thing.java' ci
assert_match_ci 'security/auth-segment' "$RISK_SECURITY_PATTERN" \
    'backend/java/cretas-api/src/main/java/com/cretas/aims/auth/LoginService.java'
assert_match_ci 'security/jwt-word' "$RISK_SECURITY_PATTERN" \
    'backend/java/cretas-api/src/main/java/com/cretas/aims/util/JwtUtil.java'

check_gate 'api-contract' "$RISK_API_CONTRACT_PATTERN" \
    'backend/java/cretas-api/src/main/java/com/cretas/aims/controller/ThingController.java' \
    'backend/java/cretas-api/src/main/java/com/cretas/aims/service/Thing.java' ci
assert_match_ci 'api-contract/dto' "$RISK_API_CONTRACT_PATTERN" \
    'backend/java/cretas-api/src/main/java/com/cretas/aims/dto/ThingDto.java'
assert_match_ci 'api-contract/request-suffix' "$RISK_API_CONTRACT_PATTERN" \
    'backend/java/cretas-api/src/main/java/com/cretas/aims/model/CreateThingRequest.java'
# 注意闸只认 `/api/` 这个完整路径段, `cretas-api/` 不算 —— 否则所有后端文件都会命中。
assert_no_match_ci 'api-contract/cretas-api-is-not-an-api-segment' "$RISK_API_CONTRACT_PATTERN" \
    'backend/java/cretas-api/src/main/java/com/cretas/aims/service/Thing.java'

check_gate 'config' "$RISK_CONFIG_PATTERN" \
    'backend/java/cretas-api/src/main/resources/application-prod.yml' \
    'backend/java/cretas-api/src/main/java/com/cretas/aims/service/Thing.java' ci
assert_match_ci 'config/config-segment' "$RISK_CONFIG_PATTERN" \
    'backend/java/cretas-api/src/main/java/com/cretas/aims/config/WebConfig.java'
assert_match_ci 'config/config-filename' "$RISK_CONFIG_PATTERN" 'web-admin/vite.config.ts'
assert_match_ci 'config/dotenv' "$RISK_CONFIG_PATTERN" 'backend/python/.env.prod'
assert_match_ci 'config/dotenv-at-root' "$RISK_CONFIG_PATTERN" '.env'

check_gate 'web-contract' "$RISK_WEB_CONTRACT_PATTERN" \
    'web-admin/src/api/client.ts' \
    'web-admin/src/app.ts' ci
assert_match_ci 'web-contract/services-api' "$RISK_WEB_CONTRACT_PATTERN" \
    'web-admin/src/services/api/thing.ts'
assert_match_ci 'web-contract/contracts-plural' "$RISK_WEB_CONTRACT_PATTERN" \
    'web-admin/src/shared/contracts/thing.ts'
# 行首锚定必须保住: 别的项目下的同名目录不该拒并行。
assert_no_match_ci 'web-contract/not-anchored' "$RISK_WEB_CONTRACT_PATTERN" \
    'frontend/CretasFoodTrace/src/api/client.ts'

check_gate 'query-diff' "$RISK_QUERY_DIFF_PATTERN" \
    '+    @Query("select t from Thing t")' \
    ' context line mentioning @Query without a +/- marker' ci
assert_match_ci 'query-diff/removed-line' "$RISK_QUERY_DIFF_PATTERN" \
    '-    @Query("select t from Thing t")'
assert_match_ci 'query-diff/jpql' "$RISK_QUERY_DIFF_PATTERN" '+ String JPQL = "...";'
assert_no_match_ci 'query-diff/unrelated-added-line' "$RISK_QUERY_DIFF_PATTERN" \
    '+    private final ThingService service;'

# 大小写不敏感的闸: 大写变体必须照样命中 (旧实现用的是 grep -Eqi)。
assert_match_ci 'ci/migration-upper' "$RISK_MIGRATION_PATTERN" \
    'backend/java/cretas-api/src/main/resources/DB/MIGRATION/V2__X.SQL'
assert_match_ci 'ci/flyway-upper' "$RISK_MIGRATION_PATTERN" 'backend/FLYWAY.CONF'
assert_match_ci 'ci/entity-upper' "$RISK_ENTITY_PATTERN" 'backend/AIMS/ENTITY/Thing.java'
assert_match_ci 'ci/repository-upper' "$RISK_REPOSITORY_PATTERN" 'backend/AIMS/REPOSITORY/T.java'
assert_match_ci 'ci/security-lower' "$RISK_SECURITY_PATTERN" 'backend/aims/util/jwtutil.java'
assert_match_ci 'ci/api-contract-upper' "$RISK_API_CONTRACT_PATTERN" 'backend/AIMS/CONTROLLER/T.java'
assert_match_ci 'ci/config-upper' "$RISK_CONFIG_PATTERN" 'backend/resources/APPLICATION-PROD.YML'
assert_match_ci 'ci/web-contract-upper' "$RISK_WEB_CONTRACT_PATTERN" 'WEB-ADMIN/src/API/client.ts'
assert_match_ci 'ci/query-diff-lower' "$RISK_QUERY_DIFF_PATTERN" '+ @query("select 1")'
# 大小写变体在大输入下同样要命中。
assert_match_ci 'ci/entity-upper-big' "$RISK_ENTITY_PATTERN" \
    'backend/AIMS/ENTITY/Thing.java'$'\n'"$BIG_PAD"
assert_match_ci 'ci/query-diff-lower-huge' "$RISK_QUERY_DIFF_PATTERN" \
    '+ @query("select 1")'$'\n'"$HUGE_PAD"

# ---------------------------------------------------------------------------
# 5. select_matching_lines: 过滤语义 + 空输入
# ---------------------------------------------------------------------------
changed_sample='backend/java/cretas-api/src/main/java/com/cretas/aims/entity/Thing.java
docs/notes.md
backend/python/app/main.py
web-admin/src/app.ts
backend/java/cretas-api/pom.xml'
assert_eq 'select/filters-backend-only' \
    'backend/java/cretas-api/src/main/java/com/cretas/aims/entity/Thing.java
backend/java/cretas-api/pom.xml' \
    "$(select_matching_lines "$JAVA_PATH_PATTERN" "$changed_sample")"
assert_eq 'select/no-match-is-empty-and-succeeds' '' \
    "$(select_matching_lines "$JAVA_PATH_PATTERN" 'docs/notes.md')"
assert_eq 'select/empty-input' '' "$(select_matching_lines "$JAVA_PATH_PATTERN" '')"
# 大输入下过滤结果不能被截断 (旧的 `| grep` 在这里没有 -q, 但仍然值得钉住)。
big_changed='backend/java/cretas-api/A.java'$'\n'"$BIG_PAD"$'\n''backend/java/cretas-api/Z.java'
assert_eq 'select/big-input-keeps-both-hits' \
    'backend/java/cretas-api/A.java
backend/java/cretas-api/Z.java' \
    "$(select_matching_lines "$JAVA_PATH_PATTERN" "$big_changed")"

# 空 CHANGED_FILES (无变更发布) 必须判为"没有 Java/Web 改动", 不能报错。
assert_no_match 'empty/java' "$JAVA_PATH_PATTERN" ''
assert_no_match 'empty/web' "$WEB_PATH_PATTERN" ''
assert_no_match_ci 'empty/query-diff' "$RISK_QUERY_DIFF_PATTERN" ''

# ---------------------------------------------------------------------------
# 6. Mutation 断言: 把旧形状原样跑一遍, 证明上面的大输入用例**确实**是回归陷阱。
#    如果这一条不再"给出错误答案", 说明大输入构造得不够大, 整份测试就失去意义。
# ---------------------------------------------------------------------------
old_pipeline_shape() {
    local pattern=$1 text=$2
    (
        set -euo pipefail
        printf '%s\n' "$text" | grep -Eq -- "$pattern"
    )
}

huge_hit='+    @Query("select t from Thing t")'$'\n'"$HUGE_PAD"
matches_any_line_ci "$RISK_QUERY_DIFF_PATTERN" "$huge_hit" \
    || fail 'mutation: 修复后的实现在 MB 级输入上没命中'

old_shape_wrong=false
for _ in 1 2 3 4 5; do
    if ! old_pipeline_shape "$RISK_QUERY_DIFF_PATTERN" "$huge_hit"; then
        old_shape_wrong=true
        break
    fi
done
[ "$old_shape_wrong" = true ] \
    || fail 'mutation: 旧的 `printf | grep -q` 形状在这份大输入上竟然答对了 —— 输入不够大, 用例失去回归价值'

# 反过来: 小输入下旧形状是对的, 这解释了为什么这个 bug 能长期潜伏。
small_hit='+    @Query("select t from Thing t")'$'\n'"$SMALL_PAD"
old_pipeline_shape "$RISK_QUERY_DIFF_PATTERN" "$small_hit" \
    || fail 'mutation: 旧形状在小输入下也失败了, 与"64KB 以下侥幸正确"的判断不符'

if [ "$FAILURES" -ne 0 ]; then
    echo "FAILED: $FAILURES 个断言不通过" >&2
    exit 1
fi
echo 'PASS: release gate matching is pipeline-free and semantically unchanged at >64KB and MB-scale inputs'
