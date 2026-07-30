#!/usr/bin/env bash
#
# ⏱️ 这个套件本机跑 **约 470–480 秒**, 这是正常的, 不是卡住。
#
# 它被反复误记成"既有红测 / exit 124 超时"。真相是: 总时长 473s(实测, 带 -x 追踪)恰好压在
# 常用的 480s 超时线上, 于是时而通过、时而被杀, 看起来像一个不稳定的红测。**用 900s 超时
# 跑, 它是 exit 0 的。**
#
# 时间构成: 十几处顶层 run_release 再加上风险矩阵那几个循环, 一共约 50 次完整 fixture 发布,
# 每次 8~9 秒(各自 git init + push + fetch 一个临时仓库, 再跑一遍 release-cretas.sh)。
# 没有单一热点 —— 想提速得改成复用 fixture 仓库, 那是个有回归风险的重构, 而这个套件本身
# 是绿的, 不值得为提速去动它。
#
# 👉 所以: 跑它请给 **至少 600 秒**超时。看到 exit 124 先怀疑超时给少了, 别当成断言失败。
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
SOURCE_SCRIPT="$ROOT_DIR/scripts/deploy/release-cretas.sh"
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

assert_contains() {
    local path=$1 text=$2
    grep -Fq -- "$text" "$path" || fail "$path does not contain: $text"
}

assert_log_count() {
    local expected=$1 text=$2 path=$3 actual
    actual=$(grep -Fc -- "$text" "$path" 2>/dev/null || true)
    [ "$actual" -eq "$expected" ] || fail "expected $expected x '$text' in $path, got $actual"
}

setup_repo() {
    local name=$1
    printf 'CASE: %s\n' "$name"
    CASE_ROOT="$TMP_ROOT/$name"
    CASE_REPO="$CASE_ROOT/repo"
    CASE_REMOTE="$CASE_ROOT/origin.git"
    CASE_HOME="$CASE_ROOT/home"
    CASE_LOG="$CASE_ROOT/calls.log"
    CASE_REPORT="$CASE_ROOT/report.json"
    CASE_OUTPUT="$CASE_ROOT/output.log"
    mkdir -p "$CASE_REPO/scripts/deploy" "$CASE_REPO/scripts/lib" "$CASE_REPO/backend/java/cretas-api" "$CASE_REPO/web-admin" "$CASE_HOME"
    cp "$SOURCE_SCRIPT" "$CASE_REPO/scripts/deploy/release-cretas.sh"

    # The orchestrator takes the release-wide mutex from the shared lib. Record
    # the acquisition instead of taking a real machine-global flock, so the
    # fixture neither contends with a concurrent real release nor with itself.
    cat >"$CASE_REPO/scripts/lib/deploy-common.sh" <<'EOF'
#!/usr/bin/env bash
# Token deliberately avoids the substring "DEPLOY": other cases assert that it
# never appears when no component deployment should have run.
acquire_deploy_lock() { printf 'MUTEX %s\n' "${1:-}" >>"$MOCK_CALL_LOG"; return 0; }
EOF
    printf 'java baseline\n' >"$CASE_REPO/backend/java/cretas-api/baseline.txt"
    printf 'web baseline\n' >"$CASE_REPO/web-admin/baseline.txt"

    cat >"$CASE_REPO/scripts/deploy/release-java-preflight.sh" <<'EOF'
#!/usr/bin/env bash
printf 'JAVA_PREFLIGHT %s\n' "$*" >>"$MOCK_CALL_LOG"
EOF
    cat >"$CASE_REPO/scripts/deploy/release-cretas-artifacts.sh" <<'EOF'
#!/usr/bin/env bash
printf 'ARTIFACTS %s\n' "$*" >>"$MOCK_CALL_LOG"
printf 'JAVA_BUILD\n' >>"$MOCK_CALL_LOG"
printf 'WEB_BUILD\n' >>"$MOCK_CALL_LOG"
exit "${MOCK_ARTIFACTS_RC:-0}"
EOF
    cat >"$CASE_REPO/scripts/deploy/release-jar-manifest.sh" <<'EOF'
#!/usr/bin/env bash
if [ "${1:-}" = build ]; then
    printf 'JAVA_BUILD %s\n' "$*" >>"$MOCK_CALL_LOG"
    exit "${MOCK_JAVA_BUILD_RC:-0}"
fi
printf 'JAVA_VALIDATE\n' >>"$MOCK_CALL_LOG"
counter="${MOCK_COUNTER_ROOT}/java-validate"
count=0; [ ! -f "$counter" ] || count=$(cat "$counter")
count=$((count + 1)); printf '%s\n' "$count" >"$counter"
if [ "$count" -le "${MOCK_JAVA_VALIDATE_FAILS:-0}" ]; then exit 1; fi
exit 0
EOF
    cat >"$CASE_REPO/scripts/deploy/release-web-manifest.sh" <<'EOF'
#!/usr/bin/env bash
if [ "${1:-}" = build ]; then
    printf 'WEB_BUILD %s\n' "$*" >>"$MOCK_CALL_LOG"
    if [ "${MOCK_ADVANCE_MAIN_ON_WEB_BUILD:-0}" = "1" ]; then
        git --git-dir="$MOCK_REMOTE_PATH" update-ref refs/heads/main "$MOCK_DRIFT_SHA"
    fi
    exit "${MOCK_WEB_BUILD_RC:-0}"
fi
printf 'WEB_VALIDATE\n' >>"$MOCK_CALL_LOG"
counter="${MOCK_COUNTER_ROOT}/web-validate"
count=0; [ ! -f "$counter" ] || count=$(cat "$counter")
count=$((count + 1)); printf '%s\n' "$count" >"$counter"
if [ "$count" -le "${MOCK_WEB_VALIDATE_FAILS:-0}" ]; then exit 1; fi
exit 0
EOF
    cat >"$CASE_REPO/scripts/deploy/stage-backend-artifact.sh" <<'EOF'
#!/usr/bin/env bash
printf 'JAVA_STAGE %s\n' "$*" >>"$MOCK_CALL_LOG"
exit "${MOCK_JAVA_STAGE_RC:-0}"
EOF
cat >"$CASE_REPO/scripts/deploy/deploy-backend.sh" <<'EOF'
#!/usr/bin/env bash
printf 'JAVA_DEPLOY %s\n' "$*" >>"$MOCK_CALL_LOG"
printf 'JAVA_REQUIRE_TRUSTED=%s\n' "${CRETAS_REQUIRE_TRUSTED_ARTIFACT:-}" >>"$MOCK_CALL_LOG"
rc=${MOCK_JAVA_DEPLOY_RC:-0}
if [ "$rc" -eq 0 ]; then outcome=${MOCK_JAVA_OUTCOME:-deployed}; else outcome=unknown; fi
printf '{"result":"%s","outcome":"%s","exit_code":%s}\n' "$([ "$rc" -eq 0 ] && echo SUCCESS || echo FAILED)" "$outcome" "$rc" >"$CRETAS_DEPLOY_REPORT_PATH"
exit "$rc"
EOF
    cat >"$CASE_REPO/scripts/deploy/deploy-web-admin.sh" <<'EOF'
#!/usr/bin/env bash
printf 'WEB_DEPLOY %s\n' "$*" >>"$MOCK_CALL_LOG"
printf 'WEB_REQUIRE_TRUSTED=%s\n' "${CRETAS_REQUIRE_TRUSTED_ARTIFACT:-}" >>"$MOCK_CALL_LOG"
rc=${MOCK_WEB_DEPLOY_RC:-0}
if [ "$rc" -eq 0 ]; then outcome=${MOCK_WEB_OUTCOME:-deployed}; else outcome=unknown; fi
printf '{"result":"%s","outcome":"%s","exit_code":%s}\n' "$([ "$rc" -eq 0 ] && echo SUCCESS || echo FAILED)" "$outcome" "$rc" >"$CRETAS_WEB_DEPLOY_REPORT_PATH"
exit "$rc"
EOF
    cat >"$CASE_REPO/scripts/deploy/deploy-cretas-parallel.sh" <<'EOF'
#!/usr/bin/env bash
printf 'PARALLEL_DEPLOY %s\n' "$*" >>"$MOCK_CALL_LOG"
java_rc=${MOCK_JAVA_DEPLOY_RC:-0}
web_rc=${MOCK_WEB_DEPLOY_RC:-0}
java_outcome=${MOCK_JAVA_OUTCOME:-deployed}
web_outcome=${MOCK_WEB_OUTCOME:-deployed}
printf '{"result":"%s","outcome":"%s","exit_code":%s}\n' "$([ "$java_rc" -eq 0 ] && echo SUCCESS || echo FAILED)" "$java_outcome" "$java_rc" >"$CRETAS_DEPLOY_REPORT_PATH"
printf '{"result":"%s","outcome":"%s","exit_code":%s}\n' "$([ "$web_rc" -eq 0 ] && echo SUCCESS || echo FAILED)" "$web_outcome" "$web_rc" >"$CRETAS_WEB_DEPLOY_REPORT_PATH"
if [ "$java_rc" -ne 0 ] || [ "$web_rc" -ne 0 ]; then
    printf 'ERROR: parallel production release failed (java=%s web=%s elapsed=1s)\n' "$java_rc" "$web_rc" >&2
    exit 1
fi
printf 'Parallel production release completed (java=0 web=0 elapsed=1s)\n'
EOF
    cat >"$CASE_REPO/scripts/deploy/verify-release.sh" <<'EOF'
#!/usr/bin/env bash
printf 'VERIFY %s\n' "$*" >>"$MOCK_CALL_LOG"
printf 'BACKEND_SLOT=green\nBACKEND_PORT=10020\nBACKEND_UPSTREAM=47.100.235.168:10020\nBACKEND_SERVICE=cretas-backend-green\nBACKEND_HEALTH=pass\nWEB_HTTP=200\n'
EOF
    # release-ci-artifact.sh 必须打桩。
    # 🔴 它此前【没有桩】: --prefer-ci-artifact 默认关的时候没人走到它, 所以看不出来。
    # 改成默认开(#2061)之后, 每个用例都会调它 —— 而 fixture 里没有这个文件, 于是靠
    # 「命令不存在 → 非 0 → 回退本地构建」在偶然地工作。那等于 CI 制品这条路在测试里
    # 零覆盖, 而且是被一个巧合兜住的。显式打桩, 并让"有没有可用制品"变成可控输入。
    cat >"$CASE_REPO/scripts/deploy/release-ci-artifact.sh" <<'EOF'
#!/usr/bin/env bash
probe=0
for a in "$@"; do [ "$a" = --probe-only ] && probe=1; done
if [ "${MOCK_CI_ARTIFACT:-unavailable}" != available ]; then
    printf 'CI_ARTIFACT_PROBE probe=%s result=unavailable\n' "$probe" >>"$MOCK_CALL_LOG"
    echo "CI_ARTIFACT_UNAVAILABLE reason=mock_no_artifact" >&2
    exit 1
fi
if [ "$probe" = 1 ]; then
    printf 'CI_ARTIFACT_PROBE probe=1 result=ok\n' >>"$MOCK_CALL_LOG"
    echo "CI_ARTIFACT_PROBE_OK id=1 commit=mockcommit"
    exit 0
fi
printf 'CI_ARTIFACT_FETCH\n' >>"$MOCK_CALL_LOG"
descriptor="$MOCK_COUNTER_ROOT/release-jar.remote"
printf 'format=cretas-remote-artifact-v1\nbackend_tree=mock\n' >"$descriptor"
echo "CI_ARTIFACT_DESCRIPTOR=$descriptor"
echo "CI_ARTIFACT_READY commit=mockcommit jar_sha256=mock staged=stored"
EOF
    chmod +x "$CASE_REPO/scripts/deploy/"*.sh
    git -C "$CASE_REPO" init -q -b main
    git -C "$CASE_REPO" config user.email fixture@example.com
    git -C "$CASE_REPO" config user.name Fixture
    git -C "$CASE_REPO" add .
    git -C "$CASE_REPO" commit -qm baseline
    git init --bare -q "$CASE_REMOTE"
    git -C "$CASE_REPO" remote add origin "$CASE_REMOTE"
    git -C "$CASE_REPO" push -q -u origin main
    git -C "$CASE_REPO" fetch -q origin main
    BASE_SHA=$(git -C "$CASE_REPO" rev-parse HEAD)
    : >"$CASE_LOG"
}

# 生成 >= $1 字节的确定性填充文本。倍增而不是逐行 append —— 逐行构造 MB 级字符串
# 在 bash 里会退化成 O(n^2)。
make_padding() {
    local min_bytes=$1 out=${2:-'// deterministic filler line'}
    while [ "${#out}" -lt "$min_bytes" ]; do out="$out"$'\n'"$out"; done
    printf '%s' "$out"
}

# 在 $CASE_REPO 里造出 $1 个中性填充文件并提交, 用来把 `git diff --name-only` 的
# 输出撑到 64KB 管道缓冲以上。路径刻意不含任何闸关键字, 也不在 backend/web-admin
# 之下, 且字典序排在 backend/ 之后 —— 这样"命中行在最前、后面还有几十 KB 没写完"
# 正是旧的 `printf | grep -q` 会翻车的形状。
commit_padding_files() {
    local count=$1 dir='docs/filler/neutral-padding-directory-tree'
    mkdir -p "$CASE_REPO/$dir"
    seq 1 "$count" \
        | awk -v d="$dir" '{printf "%s/%04d-neutral-padding-file-name-no-gate-keywords.txt\n", d, $1}' \
        | (cd "$CASE_REPO" && xargs touch)
    git -C "$CASE_REPO" add "$dir"
    git -C "$CASE_REPO" commit -qm 'padding files'
}

commit_change() {
    local path=$1 text=${2:-change}
    mkdir -p "$(dirname "$CASE_REPO/$path")"
    printf '%s\n' "$text" >>"$CASE_REPO/$path"
    git -C "$CASE_REPO" add "$path"
    git -C "$CASE_REPO" commit -qm "change $path"
}

publish_head() {
    git -C "$CASE_REPO" push -q origin HEAD:main
    git -C "$CASE_REPO" fetch -q origin main
}

run_release() {
    (
        cd "$CASE_REPO"
        HOME="$CASE_HOME" \
        MOCK_CALL_LOG="$CASE_LOG" \
        MOCK_COUNTER_ROOT="$CASE_ROOT" \
        MOCK_REMOTE_PATH="$CASE_REMOTE" \
        MOCK_ADVANCE_MAIN_ON_WEB_BUILD="${MOCK_ADVANCE_MAIN_ON_WEB_BUILD:-0}" \
        MOCK_DRIFT_SHA="${MOCK_DRIFT_SHA:-}" \
        MOCK_JAVA_OUTCOME="${MOCK_JAVA_OUTCOME:-deployed}" \
        MOCK_WEB_OUTCOME="${MOCK_WEB_OUTCOME:-deployed}" \
        CRETAS_RELEASE_FALLBACK_MAIN_GUARD_SECONDS="${CRETAS_RELEASE_FALLBACK_MAIN_GUARD_SECONDS:-0}" \
        CRETAS_RELEASE_REPORT_PATH="$CASE_REPORT" \
        bash scripts/deploy/release-cretas.sh "$@"
    ) >"$CASE_OUTPUT" 2>&1
}

# Only Java changes: build only Java and never Web.
setup_repo java_only
commit_change backend/java/cretas-api/Service.java
run_release --phase build --base-sha "$BASE_SHA" --tests StartupTest
assert_contains "$CASE_OUTPUT" 'DETECTED_JAVA_CHANGED=true'
assert_contains "$CASE_OUTPUT" 'RELEASE_BUILD_MODE=java-only'
# The release-wide mutex must be taken before any build or deploy work: two
# concurrent releases otherwise clobber the shared artifact cache.
assert_contains "$CASE_LOG" 'MUTEX cretas-release'
assert_log_count 1 'JAVA_BUILD build --tests StartupTest' "$CASE_LOG"
assert_log_count 0 'WEB_BUILD' "$CASE_LOG"

# 同样只有 Java 改动, 但变更文件清单超过 64KB 管道缓冲。
# 这条钉住的是一个静默安全失效: 组件检测原先写作
#     printf '%s\n' "$CHANGED_FILES" | grep -q '^backend/java/cretas-api/'
# 在 `set -o pipefail` 下, grep -q 命中即退出关闭读端, printf 死于 SIGPIPE(141),
# pipefail 把命中判成失败 → JAVA_CHANGED=false → **后端改了却整个跳过 Java 部署**。
# 清单小于 64KB 时 printf 抢在 grep 退出前写完, 所以只有大清单才暴露。
setup_repo java_only_over_pipe_buffer
commit_change backend/java/cretas-api/Service.java
commit_padding_files 900
changed_bytes=$(git -C "$CASE_REPO" diff --name-only "$BASE_SHA" HEAD -- | wc -c)
[ "$changed_bytes" -gt 65536 ] \
    || fail "变更清单只有 ${changed_bytes}B, 没超过 64KB 管道缓冲, 本用例失去回归价值"
run_release --phase build --base-sha "$BASE_SHA" --tests StartupTest
assert_contains "$CASE_OUTPUT" 'DETECTED_JAVA_CHANGED=true'
assert_contains "$CASE_OUTPUT" 'RELEASE_SELECTION=java'
assert_contains "$CASE_OUTPUT" 'RELEASE_BUILD_MODE=java-only'
assert_log_count 1 'JAVA_BUILD build --tests StartupTest' "$CASE_LOG"

# A reviewed Java candidate can pre-stage the immutable JAR before merge. This
# is not a deployment and must remain exclusive to the build phase.
setup_repo java_stage
commit_change backend/java/cretas-api/Service.java
run_release --phase build --base-sha "$BASE_SHA" --tests StartupTest --stage-backend YES-STAGE
assert_log_count 1 'JAVA_STAGE --confirm-stage YES-STAGE' "$CASE_LOG"
assert_contains "$CASE_REPORT" '"staging": {"java": "success"'

setup_repo java_stage_wrong_phase
if run_release --phase deploy --base-sha "$BASE_SHA" --confirm-prod YES-PROD \
    --stage-backend YES-STAGE; then
    fail '--stage-backend was accepted outside the candidate build phase'
fi
assert_contains "$CASE_OUTPUT" '--stage-backend is only valid with --phase build'

# Only Web changes: build only Web.
setup_repo web_only
commit_change web-admin/src/app.ts
run_release --phase build --base-sha "$BASE_SHA"
assert_contains "$CASE_OUTPUT" 'DETECTED_WEB_CHANGED=true'
assert_contains "$CASE_OUTPUT" 'RELEASE_BUILD_MODE=web-only'
assert_log_count 1 'WEB_BUILD build' "$CASE_LOG"
assert_log_count 0 'JAVA_BUILD' "$CASE_LOG"

# No changes: validate both manifests and perform verified child no-ops.
setup_repo no_changes
MOCK_JAVA_OUTCOME=no-op MOCK_WEB_OUTCOME=no-op \
run_release --phase deploy --base-sha "$BASE_SHA" --confirm-prod YES-PROD \
    || { cat "$CASE_OUTPUT" >&2; fail 'no-change release failed'; }
assert_contains "$CASE_OUTPUT" 'RELEASE_SELECTION=none'
assert_contains "$CASE_OUTPUT" 'RELEASE_FINAL_STATUS=no-op'
assert_log_count 1 'JAVA_DEPLOY --env prod' "$CASE_LOG"
assert_log_count 1 'WEB_DEPLOY --env prod --confirm-prod YES-PROD' "$CASE_LOG"
assert_log_count 1 'JAVA_REQUIRE_TRUSTED=1' "$CASE_LOG"
assert_log_count 1 'WEB_REQUIRE_TRUSTED=1' "$CASE_LOG"
assert_contains "$CASE_REPORT" '"outcome": "no-op"'

# A stale Web manifest in an otherwise unchanged release consumes one Web
# fallback build and must report the real deployment, never a global no-op.
setup_repo no_changes_web_fallback
MOCK_WEB_VALIDATE_FAILS=1 MOCK_JAVA_OUTCOME=no-op MOCK_WEB_OUTCOME=deployed \
run_release --phase deploy --base-sha "$BASE_SHA" --confirm-prod YES-PROD
assert_contains "$CASE_OUTPUT" 'Web manifest invalid; using the one permitted build fallback'
assert_contains "$CASE_OUTPUT" 'RELEASE_BUILD_MODE=web-fallback'
assert_contains "$CASE_OUTPUT" 'RELEASE_FINAL_STATUS=deployed'
assert_log_count 0 'JAVA_BUILD build' "$CASE_LOG"
assert_log_count 1 'WEB_BUILD build' "$CASE_LOG"
assert_contains "$CASE_REPORT" '"build_mode": "web-fallback"'
assert_contains "$CASE_REPORT" '"web": {"build": "success", "deploy": "success", "outcome": "deployed", "build_count": 1}'

# 🔴 两边 manifest 都失效时, 两次回退构建【不能串行】。
#
# 这条以前没有任何用例: 套件里只有单边 fallback(上面那个 web-fallback), 所以
# 「--phase deploy 两边都要建时是相加」这件事一直没被盯住 —— 实测 Java 171s + Web 63s
# = 234s, 而 --phase all 那条早就并行(~171s)。同一件事因走的 phase 不同差 63s。
#
# 断言用【结构代理】而不是计时: 走没走并行构建器是可观察的, 而 fixture 里量不出真并行。
setup_repo both_fallback_parallel
commit_change backend/java/cretas-api/Service.java
commit_change web-admin/src/app.ts
publish_head
MOCK_JAVA_VALIDATE_FAILS=1 MOCK_WEB_VALIDATE_FAILS=1 \
run_release --phase deploy --base-sha "$BASE_SHA" --tests StartupTest --confirm-prod YES-PROD
assert_contains "$CASE_OUTPUT" 'RELEASE_BUILD_MODE=java+web-fallback'
assert_contains "$CASE_REPORT" '"build_mode": "java+web-fallback"'
# 必须走并行构建器, 而不是各建各的。两个桩输出可区分:
#   并行构建器 → 'ARTIFACTS ...' + 裸 'JAVA_BUILD' / 'WEB_BUILD'
#   单独构建   → 'JAVA_BUILD build ...' / 'WEB_BUILD build ...'
assert_log_count 1 'ARTIFACTS' "$CASE_LOG"
assert_log_count 0 'JAVA_BUILD build' "$CASE_LOG"
assert_log_count 0 'WEB_BUILD build' "$CASE_LOG"

# 同样两边都要建, 但这次 CI 制品可用 —— 那就该是「取制品 ∥ 建 Web」, 而不是
# 先花 61s 取完再花 63s 建 Web。同样用结构代理: 并行那条路径自己会说明。
setup_repo both_fallback_ci_parallel
commit_change backend/java/cretas-api/Service.java
commit_change web-admin/src/app.ts
publish_head
MOCK_JAVA_VALIDATE_FAILS=1 MOCK_WEB_VALIDATE_FAILS=1 MOCK_CI_ARTIFACT=available \
run_release --phase deploy --base-sha "$BASE_SHA" --tests StartupTest --confirm-prod YES-PROD
assert_contains "$CASE_OUTPUT" '与 Web 构建并行'
assert_log_count 1 'CI_ARTIFACT_FETCH' "$CASE_LOG"
# Java 走了 CI 制品就不该再跑一遍本地 Maven
assert_log_count 0 'JAVA_BUILD build' "$CASE_LOG"
assert_log_count 0 'ARTIFACTS' "$CASE_LOG"

# A changed component may still be a verified production no-op when identical
# bytes are already live; final status follows the child receipt, not the diff.
setup_repo java_changed_already_live
commit_change backend/java/cretas-api/Service.java
publish_head
MOCK_JAVA_OUTCOME=no-op run_release --phase deploy --base-sha "$BASE_SHA" \
    --tests StartupTest --confirm-prod YES-PROD
assert_contains "$CASE_OUTPUT" 'RELEASE_FINAL_STATUS=no-op'
assert_contains "$CASE_OUTPUT" 'RELEASE_JAVA_OUTCOME=no-op'

# Both changes default to safe backend-first sequential deployment.
setup_repo both_default
commit_change backend/java/cretas-api/Service.java
commit_change web-admin/src/app.ts
publish_head
run_release --phase deploy --base-sha "$BASE_SHA" --confirm-prod YES-PROD
assert_contains "$CASE_OUTPUT" 'RELEASE_DEPLOY_MODE=sequential-backend-first'
assert_contains "$CASE_OUTPUT" 'default safe sequential deployment'
java_line=$(grep -n 'JAVA_DEPLOY' "$CASE_LOG" | cut -d: -f1)
web_line=$(grep -n 'WEB_DEPLOY' "$CASE_LOG" | cut -d: -f1)
[ "$java_line" -lt "$web_line" ] || fail 'default order was not backend-first'

# Explicit compatibility permits the existing parallel wrapper.
setup_repo explicit_parallel
commit_change backend/java/cretas-api/service/Service.java
commit_change web-admin/src/app.ts
publish_head
run_release --phase deploy --base-sha "$BASE_SHA" --confirm-prod YES-PROD \
    --parallel-if-independent YES-INDEPENDENT-SERVICES
assert_contains "$CASE_OUTPUT" 'RELEASE_DEPLOY_MODE=parallel'
assert_log_count 1 'PARALLEL_DEPLOY --confirm-prod YES-PROD --confirm-independent-services YES-INDEPENDENT-SERVICES' "$CASE_LOG"

# Every risky contract family rejects parallel automatically.
for risk in \
    'migration:backend/java/cretas-api/src/main/resources/db/migration/V2__x.sql:Flyway migration files changed' \
    'entity:backend/java/cretas-api/src/main/java/com/cretas/aims/entity/Thing.java:Entity files changed' \
    'repository:backend/java/cretas-api/src/main/java/com/cretas/aims/repository/ThingRepository.java:Repository files changed' \
    'security:backend/java/cretas-api/src/main/java/com/cretas/aims/security/AuthFilter.java:security or authentication files changed' \
    'controller:backend/java/cretas-api/src/main/java/com/cretas/aims/controller/ThingController.java:API contract files changed' \
    'dto:backend/java/cretas-api/src/main/java/com/cretas/aims/dto/ThingDto.java:API contract files changed' \
    'config:backend/java/cretas-api/src/main/resources/application-prod.yml:configuration or environment contract files changed'
do
    IFS=: read -r name path reason <<<"$risk"
    setup_repo "risk-$name"
    commit_change "$path"
    commit_change web-admin/src/app.ts
    publish_head
    run_release --phase deploy --base-sha "$BASE_SHA" --confirm-prod YES-PROD \
        --parallel-if-independent YES-INDEPENDENT-SERVICES
    assert_contains "$CASE_OUTPUT" "PARALLEL_REJECTED: $reason"
    assert_contains "$CASE_OUTPUT" 'RELEASE_DEPLOY_MODE=sequential-backend-first'
    assert_log_count 0 'PARALLEL_DEPLOY' "$CASE_LOG"
done

# Added @Query/JPQL also rejects parallel even outside repository paths.
setup_repo risk_query
commit_change backend/java/cretas-api/src/main/java/com/cretas/aims/service/Search.java '@Query("select x from X x")'
commit_change web-admin/src/app.ts
publish_head
run_release --phase deploy --base-sha "$BASE_SHA" --confirm-prod YES-PROD \
    --parallel-if-independent YES-INDEPENDENT-SERVICES
assert_contains "$CASE_OUTPUT" 'PARALLEL_REJECTED: Repository query contract changed'

# 同一个 @Query 闸, 但喂给它的是 MB 级 backend diff —— 生产上的真实体积。
# 这是本闸唯一会在真实发布里走到的路径: `git diff -U0 -- backend/java/cretas-api`
# 常有几 MB, 而旧实现 `printf '%s\n' "$diff_text" | grep -Eqi ...` 在 pipefail 下
# 必然把命中判成没命中 → Repository 查询契约改了却**静默放行并行部署**。
setup_repo risk_query_large_diff
mkdir -p "$CASE_REPO/backend/java/cretas-api/src/main/java/com/cretas/aims/service"
{
    printf '@Query("select x from X x")\n'
    printf '%s\n' "$(make_padding $((2 * 1024 * 1024)))"
} >"$CASE_REPO/backend/java/cretas-api/src/main/java/com/cretas/aims/service/Search.java"
git -C "$CASE_REPO" add backend/java/cretas-api/src/main/java/com/cretas/aims/service/Search.java
git -C "$CASE_REPO" commit -qm 'large backend change carrying a @Query contract edit'
commit_change web-admin/src/app.ts
publish_head
diff_bytes=$(git -C "$CASE_REPO" diff -U0 "$BASE_SHA" HEAD -- backend/java/cretas-api | wc -c)
[ "$diff_bytes" -gt $((1024 * 1024)) ] \
    || fail "backend diff 只有 ${diff_bytes}B, 没到 MB 级, 本用例失去回归价值"
run_release --phase deploy --base-sha "$BASE_SHA" --confirm-prod YES-PROD \
    --parallel-if-independent YES-INDEPENDENT-SERVICES
assert_contains "$CASE_OUTPUT" 'PARALLEL_REJECTED: Repository query contract changed'
assert_contains "$CASE_OUTPUT" 'RELEASE_DEPLOY_MODE=sequential-backend-first'
assert_log_count 0 'PARALLEL_DEPLOY' "$CASE_LOG"

# Explicit orders are honored and always disable parallel.
setup_repo web_first
commit_change backend/java/cretas-api/service/Service.java
commit_change web-admin/src/app.ts
publish_head
run_release --phase deploy --base-sha "$BASE_SHA" --confirm-prod YES-PROD --order web-first
assert_contains "$CASE_OUTPUT" 'RELEASE_DEPLOY_MODE=sequential-web-first'
web_line=$(grep -n 'WEB_DEPLOY' "$CASE_LOG" | cut -d: -f1)
java_line=$(grep -n 'JAVA_DEPLOY' "$CASE_LOG" | cut -d: -f1)
[ "$web_line" -lt "$java_line" ] || fail 'web-first order was not honored'

setup_repo explicit_order_rejects_parallel
commit_change backend/java/cretas-api/service/Service.java
commit_change web-admin/src/app.ts
publish_head
run_release --phase deploy --base-sha "$BASE_SHA" --confirm-prod YES-PROD \
    --order backend-first --parallel-if-independent YES-INDEPENDENT-SERVICES
assert_contains "$CASE_OUTPUT" 'PARALLEL_REJECTED: explicit deployment order requested'

# Invalid Java manifest consumes exactly one fallback build, then deploys.
setup_repo invalid_manifest
commit_change backend/java/cretas-api/Service.java
publish_head
MOCK_JAVA_VALIDATE_FAILS=1 run_release --phase deploy --base-sha "$BASE_SHA" \
    --tests StartupTest --confirm-prod YES-PROD
assert_contains "$CASE_OUTPUT" 'Java manifest invalid; using the one permitted build fallback'
assert_log_count 1 'JAVA_BUILD build --tests StartupTest' "$CASE_LOG"
assert_log_count 2 'JAVA_VALIDATE' "$CASE_LOG"

# origin/main 在制品校验/回退构建期间前进, 且本次发布的提交【已在】新 main 里:
# 自动前进到新 main 并重新执行, 而不是硬失败让人重跑。安全性不变 —— 重新执行会
# 按新 main 的 tree 重新校验 manifest, 绝不会用陈旧制品部署。
setup_repo fallback_main_drift
commit_change web-admin/src/app.ts
publish_head
tree=$(git -C "$CASE_REPO" rev-parse HEAD^{tree})
# 父节点是 HEAD → 本次发布的提交在新 main 里 → 满足自动前进的祖先前提。
MOCK_DRIFT_SHA=$(printf 'fixture drift\n' | git -C "$CASE_REPO" commit-tree "$tree" -p HEAD)
git -C "$CASE_REPO" push -q origin "$MOCK_DRIFT_SHA:refs/heads/drift-fixture"
MOCK_WEB_VALIDATE_FAILS=1 MOCK_ADVANCE_MAIN_ON_WEB_BUILD=1 \
    MOCK_DRIFT_SHA="$MOCK_DRIFT_SHA" \
    run_release --phase deploy --base-sha "$BASE_SHA" --confirm-prod YES-PROD \
    || fail 'release did not recover from a drift whose new main contains the release commits'
assert_contains "$CASE_OUTPUT" 'origin/main 在 artifact validation/fallback build 期间前进'
assert_contains "$CASE_OUTPUT" '本次发布的提交已确认在新 main 中'
# 恢复不是"忽略": 必须真的部署到前进后的 main, 且回执要留下漂移次数。
[ "$(git -C "$CASE_REPO" rev-parse HEAD)" = "$MOCK_DRIFT_SHA" ] \
    || fail 'recovery did not advance the release worktree to the new origin/main'
assert_contains "$CASE_REPORT" '"drift_recoveries": 1'

# 同样是漂移, 但新 main 与本次发布的提交【分叉】(不含本次提交): 必须硬失败。
# 少了这条前提, 自动前进会静默丢掉本次要发布的内容, 部署"成功"却发了别人的东西。
setup_repo divergent_main_drift
commit_change web-admin/src/app.ts
publish_head
divergent_parent=$(git -C "$CASE_REPO" rev-parse HEAD~1)
divergent_tree=$(git -C "$CASE_REPO" rev-parse "$divergent_parent^{tree}")
MOCK_DRIFT_SHA=$(printf 'divergent drift\n' \
    | git -C "$CASE_REPO" commit-tree "$divergent_tree" -p "$divergent_parent")
git -C "$CASE_REPO" push -q origin "$MOCK_DRIFT_SHA:refs/heads/divergent-fixture"
if MOCK_WEB_VALIDATE_FAILS=1 MOCK_ADVANCE_MAIN_ON_WEB_BUILD=1 \
    MOCK_DRIFT_SHA="$MOCK_DRIFT_SHA" \
    run_release --phase deploy --base-sha "$BASE_SHA" --confirm-prod YES-PROD; then
    fail 'release auto-advanced onto a main that does not contain the release commits'
fi
assert_contains "$CASE_OUTPUT" '不是它的祖先'
assert_log_count 0 'JAVA_DEPLOY' "$CASE_LOG"
assert_log_count 0 'WEB_DEPLOY' "$CASE_LOG"
assert_contains "$CASE_REPORT" '"main_guard": {"status": "failed"'

# Dirty and stale release worktrees fail before any child deployment.
setup_repo dirty
printf 'dirty\n' >>"$CASE_REPO/web-admin/baseline.txt"
if run_release --phase build --base-sha "$BASE_SHA"; then fail 'dirty worktree was accepted'; fi
assert_contains "$CASE_OUTPUT" 'requires a clean worktree'

setup_repo stale_main
commit_change backend/java/cretas-api/Service.java
if run_release --phase deploy --base-sha "$BASE_SHA" --confirm-prod YES-PROD; then fail 'stale origin/main was accepted'; fi
assert_contains "$CASE_OUTPUT" 'requires clean exact origin/main'

# Missing production confirmation is rejected without child activity.
setup_repo missing_confirm
if run_release --phase deploy --base-sha "$BASE_SHA"; then fail 'missing production confirmation was accepted'; fi
assert_contains "$CASE_OUTPUT" 'requires --confirm-prod YES-PROD'
assert_log_count 0 'DEPLOY' "$CASE_LOG"

# Sequential child failure preserves both results (failed + explicitly skipped).
setup_repo child_failure
commit_change backend/java/cretas-api/service/Service.java
commit_change web-admin/src/app.ts
publish_head
if MOCK_JAVA_DEPLOY_RC=7 run_release --phase deploy --base-sha "$BASE_SHA" --confirm-prod YES-PROD; then
    fail 'child deployment failure did not fail the release'
fi
assert_contains "$CASE_REPORT" '"java": {"build": "reused", "deploy": "failed"'
assert_contains "$CASE_REPORT" '"web": {"build": "reused", "deploy": "skipped-after-java-failure"'
assert_contains "$CASE_REPORT" '"status": "failed"'

# One all-phase release with both components invokes the Java artifact build once.
setup_repo single_java_lifecycle
commit_change backend/java/cretas-api/service/Service.java
commit_change web-admin/src/app.ts
publish_head
run_release --phase all --base-sha "$BASE_SHA" --tests StartupTest --confirm-prod YES-PROD
assert_log_count 1 'JAVA_BUILD' "$CASE_LOG"
assert_log_count 1 'ARTIFACTS --tests StartupTest' "$CASE_LOG"
assert_contains "$CASE_OUTPUT" 'RELEASE_FINAL_STATUS=deployed'

echo 'PASS: unified Cretas release routing, safety gates, failure receipts, and single-build contract'
