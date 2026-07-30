#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)

# >>> release-match-helpers (extracted verbatim by scripts/tests/test-release-cretas-gate-matching.sh)
# 发布闸的「不经管道」行匹配。
#
# 这些闸以前一律是 `printf '%s\n' "$text" | grep -q PATTERN` 的形状, 在本文件顶部的
# `set -o pipefail` 下**静默出错**: `grep -q` 一命中就退出并关闭读端, `printf` 被
# SIGPIPE 打死 (退出码 141), pipefail 于是把整条流水线判为失败, `if` 走 else 分支 ——
# **命中被报告成没命中, 结论是反的**。输入小于 64KB 管道缓冲时生产者能在 grep 退出前
# 写完, 所以侥幸正确; 喂给 Repository-query 闸的 backend 全量 diff 常有几 MB, 必错。
#
# 失效方向全是「该拒的放行」, 不是报错: JAVA_CHANGED 变 false → 后端改了却跳过部署;
# PARALLEL_REJECTION 变空 → 契约改了却放行并行部署。所以必须彻底去掉管道。
#
# 修法保留 grep 的 ERE 语义 —— 尤其是 ^ / $ 的**逐行**锚定, bash 自己的 [[ =~ ]] 在
# 多行字符串上只能锚定整串, 无法等价 —— 但改用 here-string 喂入: 没有管道, 也就没有
# 会被打死的生产者。bash 把 here-string 交给 grep 之前内容已整个写完 (小于管道缓冲
# 走管道, 否则落临时文件), 任何体积都不会 SIGPIPE。
#
# 传入空串时 here-string 与旧的 `printf '%s\n' ""` 一样产生单个空行, 语义不变。

# 任一行匹配 ERE 则返回 0 (大小写敏感)。
matches_any_line() {
    local pattern=$1 text=$2
    grep -Eq -- "$pattern" <<<"$text"
}

# 任一行匹配 ERE 则返回 0 (大小写不敏感, 等价旧的 grep -Eqi)。
matches_any_line_ci() {
    local pattern=$1 text=$2
    grep -Eqi -- "$pattern" <<<"$text"
}

# 打印匹配 ERE 的行; 无匹配时输出空并返回 0 (等价旧的 `grep ... || true`)。
select_matching_lines() {
    local pattern=$1 text=$2
    grep -E -- "$pattern" <<<"$text" || true
}

# 闸的模式集中在此, 便于单测直接引用真实模式而不是抄一份副本。
# 前两个原先是 BRE (`grep -q`), 模式里只有 ^ 和字面量, BRE/ERE 解释完全一致。
JAVA_PATH_PATTERN='^backend/java/cretas-api/'
WEB_PATH_PATTERN='^web-admin/'
RISK_MIGRATION_PATTERN='/(db/)?migration/|flyway'
RISK_ENTITY_PATTERN='/entity/|Entity\.java$'
RISK_REPOSITORY_PATTERN='/repository/|Repository\.java$'
RISK_SECURITY_PATTERN='/security/|/(auth|authentication|authorization)/|Security|Authentication|Authorization|Jwt'
RISK_API_CONTRACT_PATTERN='/controller/|/dto/|/request/|/response/|/api/|(Request|Response)\.java$'
RISK_CONFIG_PATTERN='/config/|(^|/)[^/]*config\.[^/]+$|application[^/]*\.(yml|yaml|properties)$|(^|/)\.env([^/]*)?$'
RISK_WEB_CONTRACT_PATTERN='^web-admin/.*/(api|types|contracts?)/|^web-admin/.*/services/api/'
RISK_QUERY_DIFF_PATTERN='^[+-].*(@Query|JPQL|HQL)'
# <<< release-match-helpers

# One release at a time per machine. Beyond the deploy window that the component
# scripts already guard, this protects the SHARED artifact cache
# (~/.cache/cretas/{java-deploy,web-admin-deploy}/current): two concurrent
# releases from different worktrees write the same manifest and JAR, so the
# second one silently deploys artifacts built from the first one's tree.
# Distinct from the component lock names so the children can still acquire theirs.
if [ -f "$PROJECT_ROOT/scripts/lib/deploy-common.sh" ]; then
    source "$PROJECT_ROOT/scripts/lib/deploy-common.sh"
    acquire_deploy_lock "cretas-release" || exit 1
else
    echo "ERROR: missing $PROJECT_ROOT/scripts/lib/deploy-common.sh" >&2
    exit 1
fi

# 保留原始参数: origin/main 在发布过程中前进时, 用同样的参数重新 exec 自己。
# 见 recover_from_main_drift —— HEAD 前进后 CHANGED_FILES/JAVA_CHANGED/WEB_CHANGED/
# 部署选择全都要重算, 重新走一遍入口比就地打补丁可靠得多。
RELEASE_ORIGINAL_ARGS=("$@")
DRIFT_ATTEMPT=${CRETAS_RELEASE_DRIFT_ATTEMPT:-0}
DRIFT_RETRY_BUDGET=${CRETAS_RELEASE_DRIFT_RETRIES:-2}

BASE_SHA=
TESTS=
PHASE=all
PROD_CONFIRM=
PARALLEL_CONFIRM=
ORDER=backend-first
ORDER_EXPLICIT=false
STAGE_BACKEND_CONFIRM=
# 默认【开】(2026-07-31 Steve 拍板)。
#
# 为什么翻过来: 默认关的时候, 整条 CI 制品链路(Java 制品 + 预热 + Web dist 取回)对日常发布
# 【形同不存在】—— 因为标准发布命令不带这个 flag。实测同为「java+web 都改」的两次真实
# prod 发布: 带开关 234s, 不带 405s。
#
# 默认开的代价是【探测那几秒】: 取不到 CI 制品就自动回退本地构建, Java 探测约 2s、
# Web 查制品约 4s, 出现在一次 400s 量级的发布里可以忽略。而且回退是【有声】的
# (CI_ARTIFACT_UNAVAILABLE reason=... / WEB_CI_ARTIFACT=fallback), 不会把"其实重编了一遍"
# 混成"用了 CI 制品"。
#
# 关掉: `--no-prefer-ci-artifact` 或 `CRETAS_RELEASE_PREFER_CI_ARTIFACT=0`。
if [ "${CRETAS_RELEASE_PREFER_CI_ARTIFACT:-1}" = "1" ]; then
    PREFER_CI_ARTIFACT=true
else
    PREFER_CI_ARTIFACT=false
fi
FALLBACK_MAIN_GUARD_SECONDS=${CRETAS_RELEASE_FALLBACK_MAIN_GUARD_SECONDS:-8}

usage() {
    cat <<'EOF'
Usage:
  scripts/deploy/release-cretas.sh \
    --base-sha <registered-base-sha> \
    --tests '<MavenTestSelector>' \
    --confirm-prod YES-PROD \
    [--phase build|deploy|all] \
    [--order backend-first|web-first] \
    [--stage-backend YES-STAGE] \
    [--no-prefer-ci-artifact] \
    [--parallel-if-independent YES-INDEPENDENT-SERVICES]

--prefer-ci-artifact (【默认开启】; 关掉用 --no-prefer-ci-artifact 或
CRETAS_RELEASE_PREFER_CI_ARTIFACT=0): build 阶段先复用一份 CI 已构建、provenance 已验证的
制品, 顶替本地 clean package。制品字节走 GitHub → 东京 → OSS → ECS, 一次都不经过本机, 直接
落进 deploy-backend.sh 的服务器端 sha256 缓存。任何一环不满足(制品不存在 / 测试选择器不同 /
签名验不过)都会明确说明并照旧本地构建 —— 不会静默降级。

The normal Cretas release entry. It detects Java/Web changes relative to the
registered Base SHA, builds each trusted artifact at most once, and delegates
deployment to the existing component scripts. Deployment requires a clean
HEAD exactly equal to origin/main. Use --phase build in a clean reviewed
candidate worktree, then --phase deploy after merge when needed.
When production deployment is expected, --phase build may add
--stage-backend YES-STAGE to pre-warm the immutable server-side JAR cache
without installing, restarting, or switching production.
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --base-sha) BASE_SHA=${2:-}; shift 2 ;;
        --tests) TESTS=${2:-}; shift 2 ;;
        --phase) PHASE=${2:-}; shift 2 ;;
        --confirm-prod) PROD_CONFIRM=${2:-}; shift 2 ;;
        --parallel-if-independent) PARALLEL_CONFIRM=${2:-}; shift 2 ;;
        --order) ORDER=${2:-}; ORDER_EXPLICIT=true; shift 2 ;;
        --stage-backend) STAGE_BACKEND_CONFIRM=${2:-}; shift 2 ;;
        --prefer-ci-artifact) PREFER_CI_ARTIFACT=true; shift ;;
        --no-prefer-ci-artifact) PREFER_CI_ARTIFACT=false; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "ERROR: unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

# Web 侧的取回是在 release-web-manifest.sh 的 web_release_build 里预热的(三条通向 web 构建的
# 路径都落到那个函数, 插在调用方必漏)。它只认环境变量, 所以 --prefer-ci-artifact 这个【命令行】
# 形式必须在这里补一次导出, 否则人手传 flag 时 Java 生效而 Web 不生效 —— 又是一个"只在某条
# 路径生效"的半吊子开关。
# 两个方向都显式导出。只导出 1 的话, `--no-prefer-ci-artifact` 配上环境里已有的
# CRETAS_RELEASE_PREFER_CI_ARTIFACT=1, 会变成 Java 侧关掉而 Web 侧照样开 —— 又一个
# "只在一半路径生效"的半吊子开关。决定只在这里做一次, 下游一律读这个导出值。
if [ "$PREFER_CI_ARTIFACT" = "true" ]; then
    export CRETAS_RELEASE_PREFER_CI_ARTIFACT=1
else
    export CRETAS_RELEASE_PREFER_CI_ARTIFACT=0
fi

[ -n "$BASE_SHA" ] || { echo "ERROR: --base-sha is required" >&2; exit 2; }
case "$PHASE" in build|deploy|all) ;; *) echo "ERROR: --phase must be build, deploy, or all" >&2; exit 2 ;; esac
case "$ORDER" in backend-first|web-first) ;; *) echo "ERROR: --order must be backend-first or web-first" >&2; exit 2 ;; esac
case "$PARALLEL_CONFIRM" in ""|YES-INDEPENDENT-SERVICES) ;; *) echo "ERROR: --parallel-if-independent requires YES-INDEPENDENT-SERVICES" >&2; exit 2 ;; esac
case "$STAGE_BACKEND_CONFIRM" in ""|YES-STAGE) ;; *) echo "ERROR: --stage-backend requires YES-STAGE" >&2; exit 2 ;; esac
[[ "$FALLBACK_MAIN_GUARD_SECONDS" =~ ^[0-9]+$ ]] \
    || { echo "ERROR: CRETAS_RELEASE_FALLBACK_MAIN_GUARD_SECONDS must be a non-negative integer" >&2; exit 2; }
if [ -n "$STAGE_BACKEND_CONFIRM" ] && [ "$PHASE" != build ]; then
    echo "ERROR: --stage-backend is only valid with --phase build" >&2
    exit 2
fi
if [ "$PHASE" != build ] && [ "$PROD_CONFIRM" != YES-PROD ]; then
    echo "ERROR: production release requires --confirm-prod YES-PROD" >&2
    exit 2
fi

git -C "$PROJECT_ROOT" cat-file -e "$BASE_SHA^{commit}" 2>/dev/null \
    || { echo "ERROR: Base SHA cannot be resolved: $BASE_SHA" >&2; exit 1; }
git -C "$PROJECT_ROOT" merge-base --is-ancestor "$BASE_SHA" HEAD \
    || { echo "ERROR: registered Base SHA is not an ancestor of candidate HEAD" >&2; exit 1; }
[ -z "$(git -C "$PROJECT_ROOT" status --porcelain --untracked-files=normal)" ] \
    || { echo "ERROR: unified release requires a clean worktree" >&2; exit 1; }

HEAD_SHA=$(git -C "$PROJECT_ROOT" rev-parse HEAD)
if [ "$PHASE" != build ]; then
    git -C "$PROJECT_ROOT" fetch --quiet origin main
    ORIGIN_MAIN_SHA=$(git -C "$PROJECT_ROOT" rev-parse origin/main)
    [ "$HEAD_SHA" = "$ORIGIN_MAIN_SHA" ] || {
        echo "ERROR: deployment requires clean exact origin/main (HEAD=$HEAD_SHA origin/main=$ORIGIN_MAIN_SHA)" >&2
        exit 1
    }
fi

CHANGED_FILES=$(git -C "$PROJECT_ROOT" diff --name-only "$BASE_SHA" HEAD --)
if matches_any_line "$JAVA_PATH_PATTERN" "$CHANGED_FILES"; then JAVA_CHANGED=true; else JAVA_CHANGED=false; fi
if matches_any_line "$WEB_PATH_PATTERN" "$CHANGED_FILES"; then WEB_CHANGED=true; else WEB_CHANGED=false; fi

if [ "$JAVA_CHANGED" = true ] && [ "$WEB_CHANGED" = true ]; then
    COMPONENTS=both
elif [ "$JAVA_CHANGED" = true ]; then
    COMPONENTS=java
elif [ "$WEB_CHANGED" = true ]; then
    COMPONENTS=web
else
    COMPONENTS=none
fi

REPORT_ROOT=${CRETAS_RELEASE_REPORT_DIR:-$HOME/.cache/cretas/release-reports}
REPORT_PATH=${CRETAS_RELEASE_REPORT_PATH:-$REPORT_ROOT/cretas-$(date +%s)-$$.json}
RUN_LOG_DIR=${CRETAS_RELEASE_LOG_DIR:-$REPORT_ROOT/logs-$(date +%s)-$$}
mkdir -p "$RUN_LOG_DIR" "$(dirname "$REPORT_PATH")"
STARTED_AT=$(date +%s)

BUILD_MODE=none
DEPLOY_MODE=none
MODE_REASON=
JAVA_BUILD_STATUS=not-selected
WEB_BUILD_STATUS=not-selected
JAVA_DEPLOY_STATUS=not-selected
WEB_DEPLOY_STATUS=not-selected
JAVA_DEPLOY_OUTCOME=not-selected
WEB_DEPLOY_OUTCOME=not-selected
JAVA_BUILD_SECONDS=0
WEB_BUILD_SECONDS=0
BUILD_SECONDS=0
JAVA_DEPLOY_SECONDS=0
WEB_DEPLOY_SECONDS=0
DEPLOY_SECONDS=0
VERIFY_SECONDS=0
FINAL_STATUS=failed
JAVA_BUILD_COUNT=0
WEB_BUILD_COUNT=0
JAVA_STAGE_STATUS=not-requested
JAVA_STAGE_SECONDS=0
# CI 制品优先: 默认关闭。开启后 build 阶段先试着用一份 provenance 已验证的 CI 制品顶替
# 本地 clean package(实测本地 ~125s: javac 110s + 打包 15s)。取不到就照旧本地构建。
CI_ARTIFACT_STATUS=disabled
CI_ARTIFACT_SECONDS=0
CI_ARTIFACT_DESCRIPTOR=
MAIN_GUARD_STATUS=not-needed
MAIN_GUARD_SECONDS=0
FALLBACK_GUARD_COMPLETED=false
# 两边的回退构建是不是并行做的。只影响【工时怎么记】: 串行要相加, 并行不能相加。
# FALLBACK_PARALLEL_SECONDS 先给 0 —— 脚本是 set -u, 引用未赋值变量会直接炸。
FALLBACK_BUILD_PARALLEL=false
FALLBACK_PARALLEL_SECONDS=0
BACKEND_UPSTREAM=
BACKEND_SLOT=
BACKEND_PORT=
BACKEND_SERVICE=
BACKEND_HEALTH=
WEB_HTTP=
WEB_HASH_LOCAL=
WEB_HASH_SERVER=
WEB_HASH_GATEWAY_HTTP=
WEB_HASH_PUBLIC_HTTPS=

json_escape() {
    printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g; :a; N; $!ba; s/\n/\\n/g'
}

manifest_field() {
    local path=$1 key=$2
    [ -f "$path" ] || return 0
    awk -F= -v key="$key" '$1 == key {sub(/^[^=]*=/, ""); sub(/\r$/, ""); print; exit}' "$path"
}

json_report_field() {
    local path=$1 key=$2
    [ -f "$path" ] || return 0
    sed -n 's/.*"'"$key"'"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$path" | head -1
}

record_fallback_build() {
    local component=$1
    case "$BUILD_MODE:$component" in
        none:java) BUILD_MODE=java-fallback ;;
        none:web) BUILD_MODE=web-fallback ;;
        web-fallback:java|java-fallback:web) BUILD_MODE=java+web-fallback ;;
    esac
}

duration_run() {
    local seconds_var=$1 log_file=$2
    shift 2
    local started rc
    started=$(date +%s)
    set +e
    "$@" >"$log_file" 2>&1
    rc=$?
    set -e
    cat "$log_file"
    printf -v "$seconds_var" '%s' "$(( $(date +%s) - started ))"
    return "$rc"
}

write_report() {
    local exit_code=${1:-1} finished total java_manifest web_manifest
    local java_build_commit java_tree java_sha java_size java_maven_seconds
    local web_build_commit web_tree web_sha web_index_sha
    local tmp
    finished=$(date +%s)
    total=$((finished - STARTED_AT))
    java_manifest=${CRETAS_JAVA_RELEASE_MANIFEST:-$HOME/.cache/cretas/java-deploy/current/release-jar.manifest}
    web_manifest=${CRETAS_WEB_RELEASE_MANIFEST:-$HOME/.cache/cretas/web-admin-deploy/current/release-web.manifest}
    java_build_commit=$(manifest_field "$java_manifest" build_commit)
    java_tree=$(manifest_field "$java_manifest" backend_tree)
    java_sha=$(manifest_field "$java_manifest" jar_sha256)
    java_size=$(manifest_field "$java_manifest" jar_size_bytes)
    java_maven_seconds=$(manifest_field "$java_manifest" maven_wall_seconds)
    web_build_commit=$(manifest_field "$web_manifest" build_commit)
    web_tree=$(manifest_field "$web_manifest" web_tree)
    web_sha=$(manifest_field "$web_manifest" archive_sha256)
    web_index_sha=$(manifest_field "$web_manifest" index_sha256)
    tmp="$REPORT_PATH.tmp.$$"
    {
        printf '{\n'
        printf '  "format": "cretas-unified-release-report-v1",\n'
        printf '  "status": "%s",\n' "$(json_escape "$FINAL_STATUS")"
        printf '  "exit_code": %s,\n' "$exit_code"
        printf '  "base_sha": "%s",\n' "$(json_escape "$BASE_SHA")"
        printf '  "head_sha": "%s",\n' "$(json_escape "$HEAD_SHA")"
        printf '  "phase": "%s",\n' "$PHASE"
        printf '  "changes": {"java": %s, "web": %s},\n' "$JAVA_CHANGED" "$WEB_CHANGED"
        printf '  "selection": "%s",\n' "$COMPONENTS"
        printf '  "build_mode": "%s",\n' "$BUILD_MODE"
        printf '  "deploy_mode": "%s",\n' "$DEPLOY_MODE"
        printf '  "mode_reason": "%s",\n' "$(json_escape "$MODE_REASON")"
        printf '  "timings_seconds": {"build_total": %s, "java_build": %s, "web_build": %s, "deploy_total": %s, "java_deploy": %s, "web_deploy": %s, "verify": %s, "total": %s},\n' \
            "$BUILD_SECONDS" "$JAVA_BUILD_SECONDS" "$WEB_BUILD_SECONDS" "$DEPLOY_SECONDS" "$JAVA_DEPLOY_SECONDS" "$WEB_DEPLOY_SECONDS" "$VERIFY_SECONDS" "$total"
        printf '  "components": {\n'
        printf '    "java": {"build": "%s", "deploy": "%s", "outcome": "%s", "build_count": %s},\n' "$JAVA_BUILD_STATUS" "$JAVA_DEPLOY_STATUS" "$JAVA_DEPLOY_OUTCOME" "$JAVA_BUILD_COUNT"
        printf '    "web": {"build": "%s", "deploy": "%s", "outcome": "%s", "build_count": %s}\n' "$WEB_BUILD_STATUS" "$WEB_DEPLOY_STATUS" "$WEB_DEPLOY_OUTCOME" "$WEB_BUILD_COUNT"
        printf '  },\n'
        printf '  "staging": {"java": "%s", "seconds": %s},\n' "$JAVA_STAGE_STATUS" "$JAVA_STAGE_SECONDS"
        # 「这次到底用没用 CI 制品」必须落在台账里。unavailable:<reason> 会带上原因, 否则
        # 一次静默回退与一次真复用在报告里长得一模一样。
        printf '  "ci_artifact": {"status": "%s", "seconds": %s, "descriptor": "%s"},\n' \
            "$(json_escape "$CI_ARTIFACT_STATUS")" "$CI_ARTIFACT_SECONDS" \
            "$(json_escape "$CI_ARTIFACT_DESCRIPTOR")"
        printf '  "main_guard": {"status": "%s", "seconds": %s, "drift_recoveries": %s},\n' \
            "$MAIN_GUARD_STATUS" "$MAIN_GUARD_SECONDS" "$DRIFT_ATTEMPT"
        printf '  "java_manifest": {"build_commit": "%s", "tree": "%s", "sha256": "%s", "size_bytes": "%s", "maven_wall_seconds": "%s"},\n' \
            "$(json_escape "$java_build_commit")" "$(json_escape "$java_tree")" "$(json_escape "$java_sha")" "$(json_escape "$java_size")" "$(json_escape "$java_maven_seconds")"
        printf '  "web_manifest": {"build_commit": "%s", "tree": "%s", "sha256": "%s", "index_sha256": "%s"},\n' \
            "$(json_escape "$web_build_commit")" "$(json_escape "$web_tree")" "$(json_escape "$web_sha")" "$(json_escape "$web_index_sha")"
        printf '  "production": {"upstream": "%s", "slot": "%s", "port": "%s", "active_service": "%s", "backend_health": "%s", "web_http": "%s"},\n' \
            "$(json_escape "$BACKEND_UPSTREAM")" "$(json_escape "$BACKEND_SLOT")" "$(json_escape "$BACKEND_PORT")" "$(json_escape "$BACKEND_SERVICE")" "$(json_escape "$BACKEND_HEALTH")" "$(json_escape "$WEB_HTTP")"
        printf '  "web_four_way_hashes": {"local": "%s", "server": "%s", "gateway_http": "%s", "public_https": "%s"},\n' \
            "$(json_escape "$WEB_HASH_LOCAL")" "$(json_escape "$WEB_HASH_SERVER")" \
            "$(json_escape "$WEB_HASH_GATEWAY_HTTP")" "$(json_escape "$WEB_HASH_PUBLIC_HTTPS")"
        printf '  "logs": "%s"\n' "$(json_escape "$RUN_LOG_DIR")"
        printf '}\n'
    } >"$tmp"
    mv -f "$tmp" "$REPORT_PATH"
    printf 'RELEASE_REPORT=%s\n' "$REPORT_PATH"
    printf 'RELEASE_TOTAL_WALL_SECONDS=%s\n' "$total"
}

on_exit() {
    local rc=$?
    if [ "$rc" -ne 0 ]; then FINAL_STATUS=failed; fi
    write_report "$rc" || true
}
trap on_exit EXIT

printf 'DETECTED_JAVA_CHANGED=%s\n' "$JAVA_CHANGED"
printf 'DETECTED_WEB_CHANGED=%s\n' "$WEB_CHANGED"
printf 'RELEASE_SELECTION=%s\n' "$COMPONENTS"

# 试着用一份 CI 已构建、provenance 已验证的制品顶替本地 Maven。成功返 0 并留下描述符。
#
# 单独一个函数, 因为有两条构建路径都要用它: COMPONENTS=java 走 build_java, 而
# COMPONENTS=both 走并行的 release-cretas-artifacts.sh —— 后者是常态(改动通常同时碰 Java 与
# Web)。第一版只挂在 build_java 上, 于是典型发布里这个功能【一次都不会触发】, 报告里
# ci_artifact.status 恒为 disabled。实测撞到过, 正是"机制存在但从未被用"那个模式。
#
# 不递增 JAVA_BUILD_COUNT: 那个计数的语义是"至多一次 Maven 生命周期"。CI 路径一次 Maven 都
# 没跑, 提前把预算花掉会让后面真需要回退时被自己的闸挡住。
try_ci_artifact() {
    [ "$PREFER_CI_ARTIFACT" = "true" ] || return 1
    [ -n "$TESTS" ] || return 1

    if duration_run CI_ARTIFACT_SECONDS "$RUN_LOG_DIR/java-ci-artifact.log" \
        "$SCRIPT_DIR/release-ci-artifact.sh" --tests "$TESTS"; then
        CI_ARTIFACT_DESCRIPTOR=$(sed -n 's/^CI_ARTIFACT_DESCRIPTOR=//p' \
            "$RUN_LOG_DIR/java-ci-artifact.log" | tail -1)
        if [ -n "$CI_ARTIFACT_DESCRIPTOR" ] && [ -f "$CI_ARTIFACT_DESCRIPTOR" ]; then
            CI_ARTIFACT_STATUS=used
            echo "CI_ARTIFACT=used descriptor=$CI_ARTIFACT_DESCRIPTOR seconds=$CI_ARTIFACT_SECONDS"
            return 0
        fi
        # 脚本退出 0 却没留下可用描述符 —— 当失败处理, 不当"大概行吧"。
        CI_ARTIFACT_STATUS=unusable-descriptor
        CI_ARTIFACT_DESCRIPTOR=
    else
        CI_ARTIFACT_STATUS=$(sed -n 's/^CI_ARTIFACT_UNAVAILABLE reason=/unavailable:/p' \
            "$RUN_LOG_DIR/java-ci-artifact.log" | tail -1)
        [ -n "$CI_ARTIFACT_STATUS" ] || CI_ARTIFACT_STATUS=unavailable:unknown
        CI_ARTIFACT_DESCRIPTOR=
    fi
    # 回退是安全的(就是原来的行为), 但必须吵。静默回退会让"用了 CI 制品"和"其实重编了一遍"
    # 变成同一条日志。
    echo "CI_ARTIFACT=$CI_ARTIFACT_STATUS — 回退本地构建" >&2
    return 1
}

# 取 CI 制品 与 构建 Web 并行跑。成功返 0(此时 Java 侧由描述符承担), 失败返 1(Web 已建好)。
#
# 后台子 shell 里的变量赋值传不回父进程, 所以两边各写一个 kv 状态文件, 父进程 wait 后回读。
# 两个后台任务都【不】直接输出 —— duration_run 会 cat 日志, 两个同时 cat 会交错成乱码;
# 改为各写各的日志, 父进程按顺序输出。
run_ci_fetch_parallel_web() {
    local fetch_state="$RUN_LOG_DIR/ci-fetch.state"
    local web_state="$RUN_LOG_DIR/web-build.state"
    local fetch_pid web_pid fetch_rc web_rc
    rm -f "$fetch_state" "$web_state"

    # 父进程先把两个计数占掉: 子 shell 里的递增回不来, 而这两个"至多一次"的预算必须真实反映
    # 已经发生的事(Web 确实构建了; Java 侧要么用制品要么走 build_java 自己的闸)。
    WEB_BUILD_COUNT=1

    (
        if try_ci_artifact > "$RUN_LOG_DIR/java-ci-artifact-outer.log" 2>&1; then
            printf 'status=%s\nseconds=%s\ndescriptor=%s\n' \
                "$CI_ARTIFACT_STATUS" "${CI_ARTIFACT_SECONDS:-0}" "$CI_ARTIFACT_DESCRIPTOR" > "$fetch_state"
        else
            printf 'status=%s\nseconds=%s\ndescriptor=\n' \
                "${CI_ARTIFACT_STATUS:-unavailable:unknown}" "${CI_ARTIFACT_SECONDS:-0}" > "$fetch_state"
        fi
    ) &
    fetch_pid=$!
    (
        if "$SCRIPT_DIR/release-web-manifest.sh" build > "$RUN_LOG_DIR/web-build.log" 2>&1; then
            printf 'status=success\n' > "$web_state"
        else
            printf 'status=failed\n' > "$web_state"
        fi
    ) &
    web_pid=$!

    local web_started=$SECONDS
    wait "$fetch_pid"; fetch_rc=$?
    wait "$web_pid";   web_rc=$?
    WEB_BUILD_SECONDS=$(( SECONDS - web_started ))

    [ -s "$RUN_LOG_DIR/java-ci-artifact-outer.log" ] && cat "$RUN_LOG_DIR/java-ci-artifact-outer.log"
    [ -s "$RUN_LOG_DIR/web-build.log" ] && cat "$RUN_LOG_DIR/web-build.log"

    # 回读 fetch 状态。状态文件缺失一律当失败 —— 不猜。
    CI_ARTIFACT_STATUS=$(sed -n 's/^status=//p' "$fetch_state" 2>/dev/null | tail -1)
    CI_ARTIFACT_SECONDS=$(sed -n 's/^seconds=//p' "$fetch_state" 2>/dev/null | tail -1)
    CI_ARTIFACT_DESCRIPTOR=$(sed -n 's/^descriptor=//p' "$fetch_state" 2>/dev/null | tail -1)
    [ -n "$CI_ARTIFACT_STATUS" ] || CI_ARTIFACT_STATUS=unavailable:fetch-state-missing
    [[ "${CI_ARTIFACT_SECONDS:-}" =~ ^[0-9]+$ ]] || CI_ARTIFACT_SECONDS=0

    if [ "$(sed -n 's/^status=//p' "$web_state" 2>/dev/null | tail -1)" = success ]; then
        WEB_BUILD_STATUS=success
    else
        WEB_BUILD_STATUS=failed
        echo "ERROR: Web 构建失败 (并行取制品期间)" >&2
        return 1
    fi
    "$SCRIPT_DIR/release-web-manifest.sh" validate || { WEB_BUILD_STATUS=failed; return 1; }

    if ((fetch_rc == 0)) && [ "$CI_ARTIFACT_STATUS" = used ] \
        && [ -n "$CI_ARTIFACT_DESCRIPTOR" ] && [ -f "$CI_ARTIFACT_DESCRIPTOR" ]; then
        JAVA_BUILD_STATUS=success-ci-artifact
        echo "CI_ARTIFACT=used descriptor=$CI_ARTIFACT_DESCRIPTOR seconds=$CI_ARTIFACT_SECONDS (与 Web 构建并行)"
        return 0
    fi
    CI_ARTIFACT_DESCRIPTOR=
    return 1
}

build_java() {
    if [ "$JAVA_BUILD_COUNT" -ge 1 ]; then
        echo "ERROR: Java release build fallback already consumed; refusing a second Maven lifecycle" >&2
        return 1
    fi
    [ -n "$TESTS" ] || { echo "ERROR: Java build requires --tests '<MavenTestSelector>'" >&2; return 2; }
    "$SCRIPT_DIR/release-java-preflight.sh" --repo-root "$PROJECT_ROOT" --tests "$TESTS"

    if try_ci_artifact; then
        JAVA_BUILD_STATUS=success-ci-artifact
        return 0
    fi

    JAVA_BUILD_COUNT=$((JAVA_BUILD_COUNT + 1))
    if duration_run JAVA_BUILD_SECONDS "$RUN_LOG_DIR/java-build.log" \
        "$SCRIPT_DIR/release-jar-manifest.sh" build --tests "$TESTS"; then
        JAVA_BUILD_STATUS=success
    else
        JAVA_BUILD_STATUS=failed
        return 1
    fi
}

build_web() {
    if [ "$WEB_BUILD_COUNT" -ge 1 ]; then
        echo "ERROR: Web release build fallback already consumed; refusing a second build" >&2
        return 1
    fi
    WEB_BUILD_COUNT=$((WEB_BUILD_COUNT + 1))
    if duration_run WEB_BUILD_SECONDS "$RUN_LOG_DIR/web-build.log" \
        "$SCRIPT_DIR/release-web-manifest.sh" build; then
        WEB_BUILD_STATUS=success
    else
        WEB_BUILD_STATUS=failed
        return 1
    fi
}

stage_backend_artifact() {
    if [ "$STAGE_BACKEND_CONFIRM" != YES-STAGE ]; then
        # AGENTS.md §11 要求「预期合并后立即部署时」用 --stage-backend 把 JAR 预传到
        # 服务器不可变缓存, 让部署阶段命中缓存、跳过网络上传。但它是显式 opt-in,
        # 实际调用里长期没人传 —— 2026-07-29 连查 6 次发布回执, staging 全是
        # not-requested, 每次都在部署窗口里现传 JAR。这里给一句可见提示, 让遗漏
        # 在构建结束时就被看到, 而不是等回执事后复盘才发现。
        if [ "$JAVA_CHANGED" = true ]; then
            echo "HINT: Java 制品未预热到服务器缓存。若合并后即部署, 重跑本命令并加"
            echo "      --stage-backend YES-STAGE, 可把 JAR 上传移出部署窗口 (AGENTS.md §11)。"
        fi
        return 0
    fi
    if [ "$JAVA_CHANGED" != true ]; then
        JAVA_STAGE_STATUS=not-needed
        return 0
    fi
    if duration_run JAVA_STAGE_SECONDS "$RUN_LOG_DIR/java-stage.log" \
        "$SCRIPT_DIR/stage-backend-artifact.sh" --confirm-stage YES-STAGE; then
        JAVA_STAGE_STATUS=success
    else
        JAVA_STAGE_STATUS=failed
        return 1
    fi
}

run_build_phase() {
    local started
    started=$(date +%s)
    case "$COMPONENTS" in
        both)
            [ -n "$TESTS" ] || { echo "ERROR: Java build requires --tests '<MavenTestSelector>'" >&2; return 2; }
            # Java + Web 都要构建时, 把【取 CI 制品】与【构建 Web】真并行, 总时长从
            # max(Java, Web) 降到 max(取制品, Web) —— 取制品实测 55~69s, 完全落在 Web 的耗时内。
            #
            # ⚠️ 串行版本(先取制品再构建 Web)刻意【没有】采用: 实测收益方向不定 ——
            #   Java 160s / Web 150s → 并行 163s vs 串行 188s (慢 25s)
            #   Java 142s / Web  80s → 并行 144s vs 串行约 135s (快 9s)
            # Web 在 80s↔150s 之间摆动, 一个方向不定的改动不该进发布路径。
            #
            # 先用 --probe-only 做一次 ~2s 的廉价探测。这一步很关键: 探测不过就直接走
            # parallel-artifacts, 一秒不浪费; 只有探测过了才值得承诺"只构建 Web"这种安排。
            # 探测【只】保证存在 backend_tree 匹配的候选 —— 选择器覆盖与 attestation 要等制品
            # 真的送到才能判, 所以下面必须为「晚失败」留好回退路径。
            if [ "$PREFER_CI_ARTIFACT" = "true" ] \
                && "$SCRIPT_DIR/release-ci-artifact.sh" --tests "$TESTS" --probe-only \
                    > "$RUN_LOG_DIR/java-ci-probe.log" 2>&1; then
                cat "$RUN_LOG_DIR/java-ci-probe.log"
                "$SCRIPT_DIR/release-java-preflight.sh" --repo-root "$PROJECT_ROOT" --tests "$TESTS"
                if run_ci_fetch_parallel_web; then
                    BUILD_MODE=ci-artifact-parallel-web
                    BUILD_SECONDS=$(( $(date +%s) - started ))
                    return 0
                fi
                # 晚失败: Web 已经建好了, 但没拿到可信制品 —— 只能补建 Java, 这次是串行的。
                # 明确说出代价, 不让它看起来像正常路径。
                echo "CI_ARTIFACT=$CI_ARTIFACT_STATUS — Web 已构建, 现串行补建 Java(本次比 parallel-artifacts 慢)" >&2
                BUILD_MODE=ci-artifact-late-failure-java-fallback
                build_java || return 1
                BUILD_SECONDS=$(( $(date +%s) - started ))
                return 0
            fi
            if [ "$PREFER_CI_ARTIFACT" = "true" ]; then
                CI_ARTIFACT_STATUS=$(sed -n 's/^CI_ARTIFACT_UNAVAILABLE reason=/unavailable:/p' \
                    "$RUN_LOG_DIR/java-ci-probe.log" 2>/dev/null | tail -1)
                [ -n "$CI_ARTIFACT_STATUS" ] || CI_ARTIFACT_STATUS=unavailable:probe-failed
                echo "CI_ARTIFACT=$CI_ARTIFACT_STATUS (探测未通过, 直接走本地并行构建)" >&2
            fi
            BUILD_MODE=parallel-artifacts
            JAVA_BUILD_COUNT=1
            WEB_BUILD_COUNT=1
            if duration_run BUILD_SECONDS "$RUN_LOG_DIR/artifacts-build.log" \
                "$SCRIPT_DIR/release-cretas-artifacts.sh" --tests "$TESTS"; then
                JAVA_BUILD_STATUS=success; WEB_BUILD_STATUS=success
                JAVA_BUILD_SECONDS=$(sed -n 's/^JAVA_BUILD_WALL_SECONDS=//p' "$RUN_LOG_DIR/artifacts-build.log" | tail -1)
                WEB_BUILD_SECONDS=$(sed -n 's/^WEB_BUILD_WALL_SECONDS=//p' "$RUN_LOG_DIR/artifacts-build.log" | tail -1)
                JAVA_BUILD_SECONDS=${JAVA_BUILD_SECONDS:-$BUILD_SECONDS}
                WEB_BUILD_SECONDS=${WEB_BUILD_SECONDS:-$BUILD_SECONDS}
            else
                JAVA_BUILD_STATUS=failed; WEB_BUILD_STATUS=failed
                return 1
            fi
            ;;
        java) BUILD_MODE=java-only; build_java; BUILD_SECONDS=$(( $(date +%s) - started )) ;;
        web) BUILD_MODE=web-only; build_web; BUILD_SECONDS=$(( $(date +%s) - started )) ;;
        none)
            BUILD_MODE=no-op
            JAVA_BUILD_STATUS=not-needed
            WEB_BUILD_STATUS=not-needed
            BUILD_SECONDS=0
            ;;
    esac
    [ "$BUILD_SECONDS" -gt 0 ] || BUILD_SECONDS=$(( $(date +%s) - started ))
}

PARALLEL_REJECTION=
detect_parallel_risk() {
    local backend_changed diff_text
    backend_changed=$(select_matching_lines "$JAVA_PATH_PATTERN" "$CHANGED_FILES")
    if [ "$ORDER_EXPLICIT" = true ]; then PARALLEL_REJECTION="explicit deployment order requested"; return; fi
    if matches_any_line_ci "$RISK_MIGRATION_PATTERN" "$backend_changed"; then PARALLEL_REJECTION="Flyway migration files changed"; return; fi
    if matches_any_line_ci "$RISK_ENTITY_PATTERN" "$backend_changed"; then PARALLEL_REJECTION="Entity files changed"; return; fi
    if matches_any_line_ci "$RISK_REPOSITORY_PATTERN" "$backend_changed"; then PARALLEL_REJECTION="Repository files changed"; return; fi
    if matches_any_line_ci "$RISK_SECURITY_PATTERN" "$backend_changed"; then PARALLEL_REJECTION="security or authentication files changed"; return; fi
    if matches_any_line_ci "$RISK_API_CONTRACT_PATTERN" "$backend_changed"; then PARALLEL_REJECTION="API contract files changed"; return; fi
    if matches_any_line_ci "$RISK_CONFIG_PATTERN" "$CHANGED_FILES"; then PARALLEL_REJECTION="configuration or environment contract files changed"; return; fi
    if matches_any_line_ci "$RISK_WEB_CONTRACT_PATTERN" "$CHANGED_FILES"; then PARALLEL_REJECTION="shared Web API contract files changed"; return; fi
    diff_text=$(git -C "$PROJECT_ROOT" diff -U0 "$BASE_SHA" HEAD -- backend/java/cretas-api 2>/dev/null || true)
    if matches_any_line_ci "$RISK_QUERY_DIFF_PATTERN" "$diff_text"; then PARALLEL_REJECTION="Repository query contract changed"; return; fi
}

# origin/main 在发布期间前进时, 自动前进到新 main 并重新执行, 而不是硬失败。
#
# 安全前提 (任一不满足就走原来的硬失败路径, 绝不放宽):
#   1. 只在需要 exact-main 的阶段做 —— build 阶段本来就允许在 feature 分支上跑。
#   2. worktree 必须干净 —— 有未提交改动就说明状态不是脚本能安全推进的。
#   3. 当前 HEAD 必须是新 origin/main 的祖先 —— 否则说明【本次要发布的提交没有进
#      新 main】, 前进过去会静默丢掉本次发布的内容, 这种情况必须让人来处理。
#   4. 重试次数有界 (CRETAS_RELEASE_DRIFT_RETRIES, 默认 2), 防止和高频推送方互相追。
#
# 安全性与手工重跑等价: 前进后重新执行整个入口, 会重新校验 manifest —— tree 没变
# 就复用缓存 JAR, 变了就重建。任何情况下都不会用陈旧制品部署, 这正是 main_guard
# 真正在守的东西。区别只是把「一次失败 + 人工重来」变成「一次数秒的静默抖动」。
recover_from_main_drift() {
    local label=$1 origin_sha=$2 dirty=$3

    [ "$PHASE" != build ] || return 1
    [ "$DRIFT_ATTEMPT" -lt "$DRIFT_RETRY_BUDGET" ] 2>/dev/null || {
        echo "ERROR: origin/main 已连续前进 $DRIFT_ATTEMPT 次, 超出自动重试预算; 需要人工介入" >&2
        return 1
    }
    [ -z "$dirty" ] || return 1

    # 本次发布的提交必须已经在新 main 里, 否则前进过去等于把它们丢掉。
    git -C "$PROJECT_ROOT" merge-base --is-ancestor "$HEAD_SHA" "$origin_sha" 2>/dev/null || {
        echo "ERROR: origin/main 前进到 $origin_sha, 但本次发布的 HEAD=$HEAD_SHA 不是它的祖先" >&2
        echo "       本次要发布的提交不在新 main 中, 自动前进会静默丢掉它们; 需要人工确认" >&2
        return 1
    }

    echo ""
    echo "⚠️  origin/main 在 $label 期间前进: $HEAD_SHA → $origin_sha"
    echo "   本次发布的提交已确认在新 main 中; 自动前进并重新执行 (第 $((DRIFT_ATTEMPT + 1))/$DRIFT_RETRY_BUDGET 次)"
    echo "   制品会按新 main 的 tree 重新校验: tree 未变则复用缓存 JAR, 变了则重建"
    git -C "$PROJECT_ROOT" checkout --detach --quiet "$origin_sha" || {
        echo "ERROR: 无法前进到 $origin_sha" >&2
        return 1
    }
    echo ""

    # exec 保留已持有的 fd (含 cretas-release 锁), 不会出现放锁再抢锁的窗口。
    CRETAS_RELEASE_DRIFT_ATTEMPT=$((DRIFT_ATTEMPT + 1)) \
        exec bash "$0" "${RELEASE_ORIGINAL_ARGS[@]}"
}

ensure_exact_main_after_artifacts() {
    local label=${1:-artifact validation}
    local started origin_sha dirty

    started=$(date +%s)
    git -C "$PROJECT_ROOT" fetch --quiet origin main
    origin_sha=$(git -C "$PROJECT_ROOT" rev-parse origin/main)
    dirty=$(git -C "$PROJECT_ROOT" status --porcelain --untracked-files=normal)
    MAIN_GUARD_SECONDS=$((MAIN_GUARD_SECONDS + $(date +%s) - started))
    if [ "$HEAD_SHA" != "$origin_sha" ]; then
        # 并发 session 在本次发布期间推了 main。硬失败会让操作者手工确认再重跑一遍,
        # 而重跑的构建往往几乎免费 (backend tree 没变时命中 JAR 复用, 实测 167s→2s),
        # 所以那次人工往返换来的信息量很低、摩擦很高。这里改为在严格前提下自动前进
        # 到新 main 并重新执行整个入口。
        if recover_from_main_drift "$label" "$origin_sha" "$dirty"; then
            : # 不会返回 —— recover 成功会 exec 掉当前进程
        fi
        MAIN_GUARD_STATUS=failed
        echo "ERROR: origin/main moved during $label; refusing stale artifacts before any child deployment (HEAD=$HEAD_SHA origin/main=$origin_sha)" >&2
        return 1
    fi
    if [ -n "$dirty" ]; then
        MAIN_GUARD_STATUS=failed
        echo "ERROR: release worktree changed during $label; refusing deployment" >&2
        return 1
    fi
    MAIN_GUARD_STATUS=passed
}

guard_exact_main_before_fallback() {
    local elapsed=0 step

    [ "$PHASE" != build ] || return 0
    if [ "$FALLBACK_GUARD_COMPLETED" = true ]; then
        ensure_exact_main_after_artifacts "additional fallback pre-build check"
        return
    fi
    if [ "$FALLBACK_MAIN_GUARD_SECONDS" -eq 0 ]; then
        ensure_exact_main_after_artifacts "fallback pre-build check"
        FALLBACK_GUARD_COMPLETED=true
        return
    fi
    echo "INFO: trusted manifest miss; holding an ${FALLBACK_MAIN_GUARD_SECONDS}s exact-main freshness guard before the expensive fallback build"
    while [ "$elapsed" -lt "$FALLBACK_MAIN_GUARD_SECONDS" ]; do
        step=2
        [ $((elapsed + step)) -le "$FALLBACK_MAIN_GUARD_SECONDS" ] \
            || step=$((FALLBACK_MAIN_GUARD_SECONDS - elapsed))
        sleep "$step"
        elapsed=$((elapsed + step))
        ensure_exact_main_after_artifacts "fallback freshness guard" || return 1
    done
    FALLBACK_GUARD_COMPLETED=true
}

# 「Java 侧不需要回退构建」时返回 0。
# 拆成 ready / fallback 两半, 是为了让 both 能【先问两边、再决定要不要并行】——
# 原先 both 是 `validate_or_build_java_once; validate_or_build_web_once`, 两边都要真构建时
# 时间是相加的(实测 Java 171s + Web 63s = 234s), 而 --phase all 那条早就并行了。
java_artifact_ready() {
    # --phase all 时 build 阶段可能已经用 CI 制品拿到了描述符。那份描述符就是本次发布的
    # 制品凭据: 没有本地 manifest 可验(它压根不存在), 也不该再跑一遍跨境链路。
    if [ "$CI_ARTIFACT_STATUS" = used ] && [ -n "$CI_ARTIFACT_DESCRIPTOR" ] \
        && [ -f "$CI_ARTIFACT_DESCRIPTOR" ]; then
        echo "Java 制品: 复用本阶段已验证的 CI 制品 ($CI_ARTIFACT_DESCRIPTOR)"
        return 0
    fi
    # 本地 manifest 优先于 CI 制品: 本地已经构建好的话复用成本是 0, 而 CI 路径还要走一趟
    # 跨境传输。只有本地不可用时才往下走。
    if "$SCRIPT_DIR/release-jar-manifest.sh" validate >"$RUN_LOG_DIR/java-manifest-validate.log" 2>&1; then
        cat "$RUN_LOG_DIR/java-manifest-validate.log"
        [ "$JAVA_BUILD_STATUS" = not-selected ] && JAVA_BUILD_STATUS=reused
        return 0
    fi
    cat "$RUN_LOG_DIR/java-manifest-validate.log" >&2
    return 1
}

java_fallback_build() {
    guard_exact_main_before_fallback
    echo "WARN: Java manifest invalid; using the one permitted build fallback" >&2
    build_java
    record_fallback_build java
    # CI 制品路径没有本地 manifest/jar 可验。它的凭据是描述符, 而描述符的 backend_tree 与
    # build_commit 会在 deploy-backend.sh 里按与 release_manifest_validate 相同的两跳规则
    # 再验一次 —— 校验没被绕过, 只是换了个执行点。
    if [ "$CI_ARTIFACT_STATUS" = used ]; then
        return 0
    fi
    "$SCRIPT_DIR/release-jar-manifest.sh" validate
}

web_artifact_ready() {
    if "$SCRIPT_DIR/release-web-manifest.sh" validate >"$RUN_LOG_DIR/web-manifest-validate.log" 2>&1; then
        cat "$RUN_LOG_DIR/web-manifest-validate.log"
        [ "$WEB_BUILD_STATUS" = not-selected ] && WEB_BUILD_STATUS=reused
        return 0
    fi
    cat "$RUN_LOG_DIR/web-manifest-validate.log" >&2
    return 1
}

web_fallback_build() {
    guard_exact_main_before_fallback
    echo "WARN: Web manifest invalid; using the one permitted build fallback" >&2
    build_web
    record_fallback_build web
    "$SCRIPT_DIR/release-web-manifest.sh" validate
}

# 单组件入口保持原语义(ready 不成立就回退构建), 供 java) / web) 两个 case 用。
validate_or_build_java_once() { java_artifact_ready || java_fallback_build; }
validate_or_build_web_once() { web_artifact_ready || web_fallback_build; }

# both: 先问两边, 只有【两边都要真构建】时才值得并行。
validate_or_build_both() {
    local java_ready=false web_ready=false

    if java_artifact_ready; then java_ready=true; fi
    if web_artifact_ready; then web_ready=true; fi

    if [ "$java_ready" = true ] && [ "$web_ready" = true ]; then
        return 0
    fi
    # 只有一边要建 —— 并行没有意义, 也不该把另一边拖进并行构建器。
    if [ "$java_ready" = true ]; then
        web_fallback_build
        return 0
    fi
    if [ "$web_ready" = true ]; then
        java_fallback_build
        return 0
    fi

    # 两边都要建。Java 仍然【先试 CI 制品】—— 命中的话就只剩 Web 要建, 并行同样没意义,
    # 而且能省掉一整趟本地 Maven。
    guard_exact_main_before_fallback
    echo "WARN: Java 与 Web manifest 均不可用; 使用各自唯一一次回退构建" >&2

    # 与 --phase all 走【完全同一套编排】(#2032), 这才是这次改动的本意: 两条 phase 的
    # 性能特征拉齐, 而不是 deploy 这条永远慢一截。
    #   先 ~2s 廉价探测 → 探测过才承诺「取制品 ∥ 建 Web」(≈max(61,63))
    #                  → 探测不过直接本地并行构建 (≈max(171,63)), 一秒不浪费
    # 探测【只】保证候选存在(选择器覆盖与 attestation 要等制品送到才能判), 所以下面必须
    # 留晚失败回退 —— 这一条与 --phase all 那边的理由逐字相同。
    local fallback_started=$(date +%s)
    if [ "$PREFER_CI_ARTIFACT" = "true" ] && [ -n "$TESTS" ] \
        && "$SCRIPT_DIR/release-ci-artifact.sh" --tests "$TESTS" --probe-only \
            >"$RUN_LOG_DIR/java-ci-probe-deploy.log" 2>&1; then
        cat "$RUN_LOG_DIR/java-ci-probe-deploy.log"
        if run_ci_fetch_parallel_web; then
            record_fallback_build java
            record_fallback_build web
            FALLBACK_BUILD_PARALLEL=true
            FALLBACK_PARALLEL_SECONDS=$(( $(date +%s) - fallback_started ))
            return 0
        fi
        # 晚失败: Web 已经建好了, 但没拿到可信制品 —— 只剩 Java 一件事, 此时并行没有意义。
        # 明确说出代价, 别让它看起来像正常路径。
        echo "CI_ARTIFACT=$CI_ARTIFACT_STATUS — Web 已构建, 现串行补建 Java(本次比并行取制品慢)" >&2
        record_fallback_build web
        java_fallback_build
        FALLBACK_BUILD_PARALLEL=true
        FALLBACK_PARALLEL_SECONDS=$(( $(date +%s) - fallback_started ))
        return 0
    fi
    if [ "$PREFER_CI_ARTIFACT" = "true" ]; then
        # 🔴 必须【写回 CI_ARTIFACT_STATUS】, 不能只打印。
        # 它的初值是 disabled, 只打印不赋值的话回执里会写 "ci_artifact": "disabled" ——
        # 而实际是"开着、探测了、没命中"。这两件事该采取的行动完全不同(前者去开开关,
        # 后者去等 CI 或跑预热), 回执分不出来就等于把这条链的真实状态藏了。
        # 2026-07-31 一次真实发布就是这么误报的; --phase all 那边一直是对的, 照它写。
        CI_ARTIFACT_STATUS=$(sed -n 's/^CI_ARTIFACT_UNAVAILABLE reason=/unavailable:/p' \
            "$RUN_LOG_DIR/java-ci-probe-deploy.log" 2>/dev/null | tail -1)
        [ -n "$CI_ARTIFACT_STATUS" ] || CI_ARTIFACT_STATUS=unavailable:probe-failed
        echo "CI_ARTIFACT=$CI_ARTIFACT_STATUS (探测未通过, 直接本地并行构建)" >&2
    fi
    build_both_fallback_parallel
}

# 两边都得本地构建时, 复用构建阶段那个并行构建器。
#
# ⚠️ 为什么不是把两个 *_fallback_build 丢进后台子 shell: 它们要往父进程回写
# BUILD_MODE / *_BUILD_STATUS / *_BUILD_SECONDS / *_BUILD_COUNT 等一堆状态, 而后台子 shell
# 传不回来 —— 那条路要靠 kv 状态文件 marshalling, 是这个仓库栽过的地方。
# release-cretas-artifacts.sh 是【子进程】, 输出从日志解析, 状态全程留在父进程里。
build_both_fallback_parallel() {
    if [ "$JAVA_BUILD_COUNT" -ge 1 ] || [ "$WEB_BUILD_COUNT" -ge 1 ]; then
        echo "ERROR: build fallback already consumed; refusing another build lifecycle" >&2
        return 1
    fi
    [ -n "$TESTS" ] || { echo "ERROR: parallel fallback build requires --tests '<MavenTestSelector>'" >&2; return 2; }
    "$SCRIPT_DIR/release-java-preflight.sh" --repo-root "$PROJECT_ROOT" --tests "$TESTS"

    JAVA_BUILD_COUNT=1
    WEB_BUILD_COUNT=1
    if duration_run FALLBACK_PARALLEL_SECONDS "$RUN_LOG_DIR/artifacts-fallback-build.log" \
        "$SCRIPT_DIR/release-cretas-artifacts.sh" --tests "$TESTS"; then
        JAVA_BUILD_STATUS=success; WEB_BUILD_STATUS=success
        JAVA_BUILD_SECONDS=$(sed -n 's/^JAVA_BUILD_WALL_SECONDS=//p' "$RUN_LOG_DIR/artifacts-fallback-build.log" | tail -1)
        WEB_BUILD_SECONDS=$(sed -n 's/^WEB_BUILD_WALL_SECONDS=//p' "$RUN_LOG_DIR/artifacts-fallback-build.log" | tail -1)
        JAVA_BUILD_SECONDS=${JAVA_BUILD_SECONDS:-$FALLBACK_PARALLEL_SECONDS}
        WEB_BUILD_SECONDS=${WEB_BUILD_SECONDS:-$FALLBACK_PARALLEL_SECONDS}
    else
        JAVA_BUILD_STATUS=failed; WEB_BUILD_STATUS=failed
        return 1
    fi
    FALLBACK_BUILD_PARALLEL=true
    record_fallback_build java
    record_fallback_build web
    # 两边都要按各自那套再验一次 —— 与串行路径的收尾完全相同, 并行只改了"谁先谁后"。
    "$SCRIPT_DIR/release-jar-manifest.sh" validate
    "$SCRIPT_DIR/release-web-manifest.sh" validate
}

deploy_java() {
    local child_report="$RUN_LOG_DIR/java-deploy-report.json"
    if duration_run JAVA_DEPLOY_SECONDS "$RUN_LOG_DIR/java-deploy.log" \
        env CRETAS_REQUIRE_TRUSTED_ARTIFACT=1 \
        CRETAS_REMOTE_ARTIFACT_DESCRIPTOR="$CI_ARTIFACT_DESCRIPTOR" \
        CRETAS_DEPLOY_REPORT_PATH="$child_report" \
        "$SCRIPT_DIR/deploy-backend.sh" --env prod; then
        JAVA_DEPLOY_STATUS=success
        JAVA_DEPLOY_OUTCOME=$(json_report_field "$child_report" outcome)
        case "$JAVA_DEPLOY_OUTCOME" in
            no-op|deployed) ;;
            *) JAVA_DEPLOY_STATUS=failed; JAVA_DEPLOY_OUTCOME=unknown; echo "ERROR: Java child returned success without a valid no-op/deployed outcome receipt" >&2; return 1 ;;
        esac
    else
        JAVA_DEPLOY_STATUS=failed
        JAVA_DEPLOY_OUTCOME=$(json_report_field "$child_report" outcome)
        JAVA_DEPLOY_OUTCOME=${JAVA_DEPLOY_OUTCOME:-unknown}
        return 1
    fi
}

deploy_web() {
    local child_report="$RUN_LOG_DIR/web-deploy-report.json"
    if duration_run WEB_DEPLOY_SECONDS "$RUN_LOG_DIR/web-deploy.log" \
        env CRETAS_REQUIRE_TRUSTED_ARTIFACT=1 \
        CRETAS_WEB_DEPLOY_REPORT_PATH="$child_report" \
        "$SCRIPT_DIR/deploy-web-admin.sh" --env prod --confirm-prod YES-PROD; then
        WEB_DEPLOY_STATUS=success
        WEB_DEPLOY_OUTCOME=$(json_report_field "$child_report" outcome)
        WEB_HASH_LOCAL=$(json_report_field "$child_report" local)
        WEB_HASH_SERVER=$(json_report_field "$child_report" server)
        WEB_HASH_GATEWAY_HTTP=$(json_report_field "$child_report" gateway_http)
        WEB_HASH_PUBLIC_HTTPS=$(json_report_field "$child_report" public_https)
        case "$WEB_DEPLOY_OUTCOME" in
            no-op|deployed) ;;
            *) WEB_DEPLOY_STATUS=failed; WEB_DEPLOY_OUTCOME=unknown; echo "ERROR: Web child returned success without a valid no-op/deployed outcome receipt" >&2; return 1 ;;
        esac
    else
        WEB_DEPLOY_STATUS=failed
        WEB_DEPLOY_OUTCOME=$(json_report_field "$child_report" outcome)
        WEB_DEPLOY_OUTCOME=${WEB_DEPLOY_OUTCOME:-unknown}
        return 1
    fi
}

run_sequential_deploy() {
    DEPLOY_MODE=sequential-$ORDER
    if [ "$ORDER" = backend-first ]; then
        deploy_java || { WEB_DEPLOY_STATUS=skipped-after-java-failure; return 1; }
        deploy_web
    else
        deploy_web || { JAVA_DEPLOY_STATUS=skipped-after-web-failure; return 1; }
        deploy_java
    fi
}

run_parallel_deploy() {
    local rc java_rc web_rc
    DEPLOY_MODE=parallel
    if duration_run DEPLOY_SECONDS "$RUN_LOG_DIR/parallel-deploy.log" \
        env CRETAS_REQUIRE_TRUSTED_ARTIFACT=1 \
        CRETAS_REMOTE_ARTIFACT_DESCRIPTOR="$CI_ARTIFACT_DESCRIPTOR" \
        CRETAS_DEPLOY_REPORT_PATH="$RUN_LOG_DIR/java-deploy-report.json" \
        CRETAS_WEB_DEPLOY_REPORT_PATH="$RUN_LOG_DIR/web-deploy-report.json" \
        "$SCRIPT_DIR/deploy-cretas-parallel.sh" \
        --confirm-prod YES-PROD \
        --confirm-independent-services YES-INDEPENDENT-SERVICES; then
        rc=0
    else
        rc=$?
    fi
    if [ "$rc" -eq 0 ]; then
        JAVA_DEPLOY_STATUS=success; WEB_DEPLOY_STATUS=success
        JAVA_DEPLOY_OUTCOME=$(json_report_field "$RUN_LOG_DIR/java-deploy-report.json" outcome)
        WEB_DEPLOY_OUTCOME=$(json_report_field "$RUN_LOG_DIR/web-deploy-report.json" outcome)
        WEB_HASH_LOCAL=$(json_report_field "$RUN_LOG_DIR/web-deploy-report.json" local)
        WEB_HASH_SERVER=$(json_report_field "$RUN_LOG_DIR/web-deploy-report.json" server)
        WEB_HASH_GATEWAY_HTTP=$(json_report_field "$RUN_LOG_DIR/web-deploy-report.json" gateway_http)
        WEB_HASH_PUBLIC_HTTPS=$(json_report_field "$RUN_LOG_DIR/web-deploy-report.json" public_https)
        case "$JAVA_DEPLOY_OUTCOME:$WEB_DEPLOY_OUTCOME" in
            no-op:no-op|no-op:deployed|deployed:no-op|deployed:deployed) ;;
            *) JAVA_DEPLOY_STATUS=failed; WEB_DEPLOY_STATUS=failed; echo "ERROR: parallel children returned success without valid outcome receipts" >&2; return 1 ;;
        esac
        JAVA_DEPLOY_SECONDS=$(sed -n 's/^JAVA_DEPLOY_WALL_SECONDS=//p' "$RUN_LOG_DIR/parallel-deploy.log" | tail -1)
        WEB_DEPLOY_SECONDS=$(sed -n 's/^WEB_DEPLOY_WALL_SECONDS=//p' "$RUN_LOG_DIR/parallel-deploy.log" | tail -1)
        JAVA_DEPLOY_SECONDS=${JAVA_DEPLOY_SECONDS:-$DEPLOY_SECONDS}
        WEB_DEPLOY_SECONDS=${WEB_DEPLOY_SECONDS:-$DEPLOY_SECONDS}
        return 0
    fi
    java_rc=$(sed -n 's/^JAVA_DEPLOY_RC=//p' "$RUN_LOG_DIR/parallel-deploy.log" | tail -1)
    web_rc=$(sed -n 's/^WEB_DEPLOY_RC=//p' "$RUN_LOG_DIR/parallel-deploy.log" | tail -1)
    if [ -z "$java_rc" ] || [ -z "$web_rc" ]; then
        java_rc=$(sed -nE 's/.*java=([0-9]+) web=([0-9]+).*/\1/p' "$RUN_LOG_DIR/parallel-deploy.log" | tail -1)
        web_rc=$(sed -nE 's/.*java=([0-9]+) web=([0-9]+).*/\2/p' "$RUN_LOG_DIR/parallel-deploy.log" | tail -1)
    fi
    [ "${java_rc:-1}" -eq 0 ] && JAVA_DEPLOY_STATUS=success || JAVA_DEPLOY_STATUS=failed
    [ "${web_rc:-1}" -eq 0 ] && WEB_DEPLOY_STATUS=success || WEB_DEPLOY_STATUS=failed
    JAVA_DEPLOY_OUTCOME=$(json_report_field "$RUN_LOG_DIR/java-deploy-report.json" outcome)
    WEB_DEPLOY_OUTCOME=$(json_report_field "$RUN_LOG_DIR/web-deploy-report.json" outcome)
    JAVA_DEPLOY_OUTCOME=${JAVA_DEPLOY_OUTCOME:-unknown}
    WEB_DEPLOY_OUTCOME=${WEB_DEPLOY_OUTCOME:-unknown}
    return 1
}

capture_verification() {
    local target=$1 log=$2
    local started
    started=$(date +%s)
    "$SCRIPT_DIR/verify-release.sh" --target "$target" --env prod >"$log" 2>&1
    cat "$log"
    VERIFY_SECONDS=$(( $(date +%s) - started ))
    BACKEND_UPSTREAM=$(sed -n 's/^BACKEND_UPSTREAM=//p' "$log" | tail -1)
    BACKEND_SLOT=$(sed -n 's/^BACKEND_SLOT=//p' "$log" | tail -1)
    BACKEND_PORT=$(sed -n 's/^BACKEND_PORT=//p' "$log" | tail -1)
    BACKEND_SERVICE=$(sed -n 's/^BACKEND_SERVICE=//p' "$log" | tail -1)
    BACKEND_HEALTH=$(sed -n 's/^BACKEND_HEALTH=//p' "$log" | tail -1)
    WEB_HTTP=$(sed -n 's/^WEB_HTTP=//p' "$log" | tail -1)
}

run_deploy_phase() {
    local started selected=$COMPONENTS
    started=$(date +%s)
    [ "$selected" != none ] || selected=both

    case "$selected" in
        java) validate_or_build_java_once ;;
        web) validate_or_build_web_once ;;
        both) validate_or_build_both ;;
    esac
    ensure_exact_main_after_artifacts "artifact validation/fallback build"
    case "$BUILD_MODE" in
        # 🔴 并行做的两边【不能】相加, 否则回执里的 build_total 会凭空翻倍 ——
        # 那正是先前 --phase deploy 的 234s(=171+63) 与 --phase all 的 ~171s 差别的来源,
        # 现在两边都并行了, 记账也得跟着改, 不然"优化生效了"在回执上看不出来。
        *fallback)
            if [ "$FALLBACK_BUILD_PARALLEL" = true ]; then
                BUILD_SECONDS=$FALLBACK_PARALLEL_SECONDS
            else
                BUILD_SECONDS=$((JAVA_BUILD_SECONDS + WEB_BUILD_SECONDS))
            fi
            ;;
    esac

    if [ "$selected" = both ]; then
        detect_parallel_risk
        if [ -n "$PARALLEL_CONFIRM" ] && [ -z "$PARALLEL_REJECTION" ]; then
            MODE_REASON="explicit independent-services confirmation; no automatic high-risk match"
            run_parallel_deploy
        else
            if [ -n "$PARALLEL_REJECTION" ] && [ -n "$PARALLEL_CONFIRM" ]; then
                printf 'PARALLEL_REJECTED: %s; falling back to sequential deployment\n' "$PARALLEL_REJECTION"
                MODE_REASON="parallel rejected: $PARALLEL_REJECTION"
            elif [ -n "$PARALLEL_REJECTION" ]; then
                MODE_REASON="sequential: $PARALLEL_REJECTION"
            else
                MODE_REASON="default safe sequential deployment; API compatibility is never inferred from Git diff"
            fi
            run_sequential_deploy
        fi
        capture_verification all "$RUN_LOG_DIR/verify.log"
    elif [ "$selected" = java ]; then
        DEPLOY_MODE=java-only
        MODE_REASON="only Java changed"
        deploy_java
        capture_verification backend "$RUN_LOG_DIR/verify.log"
        WEB_DEPLOY_STATUS=not-needed
    else
        DEPLOY_MODE=web-only
        MODE_REASON="only Web changed"
        deploy_web
        capture_verification web-admin "$RUN_LOG_DIR/verify.log"
        JAVA_DEPLOY_STATUS=not-needed
    fi
    DEPLOY_SECONDS=$(( $(date +%s) - started ))
}

if [ "$PHASE" = build ] || [ "$PHASE" = all ]; then
    run_build_phase
    stage_backend_artifact
fi
if [ "$PHASE" = deploy ] || [ "$PHASE" = all ]; then
    run_deploy_phase
fi

if [ "$PHASE" = build ]; then
    if [ "$COMPONENTS" = none ]; then FINAL_STATUS=no-op; else FINAL_STATUS=built; fi
elif [ "$JAVA_DEPLOY_OUTCOME" = deployed ] || [ "$WEB_DEPLOY_OUTCOME" = deployed ]; then
    FINAL_STATUS=deployed
elif [ "$JAVA_DEPLOY_OUTCOME" = no-op ] || [ "$WEB_DEPLOY_OUTCOME" = no-op ]; then
    FINAL_STATUS=no-op
else
    FINAL_STATUS=failed
fi
printf 'RELEASE_BUILD_MODE=%s\n' "$BUILD_MODE"
printf 'RELEASE_DEPLOY_MODE=%s\n' "$DEPLOY_MODE"
printf 'RELEASE_MODE_REASON=%s\n' "$MODE_REASON"
printf 'RELEASE_JAVA_STATUS=build:%s,deploy:%s\n' "$JAVA_BUILD_STATUS" "$JAVA_DEPLOY_STATUS"
printf 'RELEASE_WEB_STATUS=build:%s,deploy:%s\n' "$WEB_BUILD_STATUS" "$WEB_DEPLOY_STATUS"
printf 'RELEASE_JAVA_OUTCOME=%s\n' "$JAVA_DEPLOY_OUTCOME"
printf 'RELEASE_WEB_OUTCOME=%s\n' "$WEB_DEPLOY_OUTCOME"
printf 'RELEASE_FINAL_STATUS=%s\n' "$FINAL_STATUS"
