#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)

BASE_SHA=
TESTS=
PHASE=all
PROD_CONFIRM=
PARALLEL_CONFIRM=
ORDER=backend-first
ORDER_EXPLICIT=false

usage() {
    cat <<'EOF'
Usage:
  scripts/deploy/release-cretas.sh \
    --base-sha <registered-base-sha> \
    --tests '<MavenTestSelector>' \
    --confirm-prod YES-PROD \
    [--phase build|deploy|all] \
    [--order backend-first|web-first] \
    [--parallel-if-independent YES-INDEPENDENT-SERVICES]

The normal Cretas release entry. It detects Java/Web changes relative to the
registered Base SHA, builds each trusted artifact at most once, and delegates
deployment to the existing component scripts. Deployment requires a clean
HEAD exactly equal to origin/main. Use --phase build in a clean reviewed
candidate worktree, then --phase deploy after merge when needed.
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
        -h|--help) usage; exit 0 ;;
        *) echo "ERROR: unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

[ -n "$BASE_SHA" ] || { echo "ERROR: --base-sha is required" >&2; exit 2; }
case "$PHASE" in build|deploy|all) ;; *) echo "ERROR: --phase must be build, deploy, or all" >&2; exit 2 ;; esac
case "$ORDER" in backend-first|web-first) ;; *) echo "ERROR: --order must be backend-first or web-first" >&2; exit 2 ;; esac
case "$PARALLEL_CONFIRM" in ""|YES-INDEPENDENT-SERVICES) ;; *) echo "ERROR: --parallel-if-independent requires YES-INDEPENDENT-SERVICES" >&2; exit 2 ;; esac
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
if printf '%s\n' "$CHANGED_FILES" | grep -q '^backend/java/cretas-api/'; then JAVA_CHANGED=true; else JAVA_CHANGED=false; fi
if printf '%s\n' "$CHANGED_FILES" | grep -q '^web-admin/'; then WEB_CHANGED=true; else WEB_CHANGED=false; fi

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

build_java() {
    if [ "$JAVA_BUILD_COUNT" -ge 1 ]; then
        echo "ERROR: Java release build fallback already consumed; refusing a second Maven lifecycle" >&2
        return 1
    fi
    [ -n "$TESTS" ] || { echo "ERROR: Java build requires --tests '<MavenTestSelector>'" >&2; return 2; }
    JAVA_BUILD_COUNT=$((JAVA_BUILD_COUNT + 1))
    "$SCRIPT_DIR/release-java-preflight.sh" --repo-root "$PROJECT_ROOT" --tests "$TESTS"
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

run_build_phase() {
    local started
    started=$(date +%s)
    case "$COMPONENTS" in
        both)
            [ -n "$TESTS" ] || { echo "ERROR: Java build requires --tests '<MavenTestSelector>'" >&2; return 2; }
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
    backend_changed=$(printf '%s\n' "$CHANGED_FILES" | grep '^backend/java/cretas-api/' || true)
    if [ "$ORDER_EXPLICIT" = true ]; then PARALLEL_REJECTION="explicit deployment order requested"; return; fi
    if printf '%s\n' "$backend_changed" | grep -Eqi '/(db/)?migration/|flyway'; then PARALLEL_REJECTION="Flyway migration files changed"; return; fi
    if printf '%s\n' "$backend_changed" | grep -Eqi '/entity/|Entity\.java$'; then PARALLEL_REJECTION="Entity files changed"; return; fi
    if printf '%s\n' "$backend_changed" | grep -Eqi '/repository/|Repository\.java$'; then PARALLEL_REJECTION="Repository files changed"; return; fi
    if printf '%s\n' "$backend_changed" | grep -Eqi '/security/|/(auth|authentication|authorization)/|Security|Authentication|Authorization|Jwt'; then PARALLEL_REJECTION="security or authentication files changed"; return; fi
    if printf '%s\n' "$backend_changed" | grep -Eqi '/controller/|/dto/|/request/|/response/|/api/|(Request|Response)\.java$'; then PARALLEL_REJECTION="API contract files changed"; return; fi
    if printf '%s\n' "$CHANGED_FILES" | grep -Eqi '/config/|(^|/)[^/]*config\.[^/]+$|application[^/]*\.(yml|yaml|properties)$|(^|/)\.env([^/]*)?$'; then PARALLEL_REJECTION="configuration or environment contract files changed"; return; fi
    if printf '%s\n' "$CHANGED_FILES" | grep -Eqi '^web-admin/.*/(api|types|contracts?)/|^web-admin/.*/services/api/'; then PARALLEL_REJECTION="shared Web API contract files changed"; return; fi
    diff_text=$(git -C "$PROJECT_ROOT" diff -U0 "$BASE_SHA" HEAD -- backend/java/cretas-api 2>/dev/null || true)
    if printf '%s\n' "$diff_text" | grep -Eqi '^[+-].*(@Query|JPQL|HQL)'; then PARALLEL_REJECTION="Repository query contract changed"; return; fi
}

validate_or_build_java_once() {
    if "$SCRIPT_DIR/release-jar-manifest.sh" validate >"$RUN_LOG_DIR/java-manifest-validate.log" 2>&1; then
        cat "$RUN_LOG_DIR/java-manifest-validate.log"
        [ "$JAVA_BUILD_STATUS" = not-selected ] && JAVA_BUILD_STATUS=reused
        return 0
    fi
    cat "$RUN_LOG_DIR/java-manifest-validate.log" >&2
    echo "WARN: Java manifest invalid; using the one permitted build fallback" >&2
    build_java
    record_fallback_build java
    "$SCRIPT_DIR/release-jar-manifest.sh" validate
}

validate_or_build_web_once() {
    if "$SCRIPT_DIR/release-web-manifest.sh" validate >"$RUN_LOG_DIR/web-manifest-validate.log" 2>&1; then
        cat "$RUN_LOG_DIR/web-manifest-validate.log"
        [ "$WEB_BUILD_STATUS" = not-selected ] && WEB_BUILD_STATUS=reused
        return 0
    fi
    cat "$RUN_LOG_DIR/web-manifest-validate.log" >&2
    echo "WARN: Web manifest invalid; using the one permitted build fallback" >&2
    build_web
    record_fallback_build web
    "$SCRIPT_DIR/release-web-manifest.sh" validate
}

deploy_java() {
    local child_report="$RUN_LOG_DIR/java-deploy-report.json"
    if duration_run JAVA_DEPLOY_SECONDS "$RUN_LOG_DIR/java-deploy.log" \
        env CRETAS_REQUIRE_TRUSTED_ARTIFACT=1 \
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
        both) validate_or_build_java_once; validate_or_build_web_once ;;
    esac
    case "$BUILD_MODE" in
        *fallback) BUILD_SECONDS=$((JAVA_BUILD_SECONDS + WEB_BUILD_SECONDS)) ;;
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
