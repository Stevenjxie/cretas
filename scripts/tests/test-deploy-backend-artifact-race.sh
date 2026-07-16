#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
DEPLOY_SCRIPT="$ROOT_DIR/scripts/deploy/deploy-backend.sh"
TMP_ROOT=$(mktemp -d)
UNRELATED_PID=""
trap 'if [ -n "$UNRELATED_PID" ]; then kill "$UNRELATED_PID" 2>/dev/null || true; fi; rm -rf "$TMP_ROOT"' EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

assert_contains() {
    local expected=$1
    grep -Fq -- "$expected" "$DEPLOY_SCRIPT" || fail "missing contract: $expected"
}

# Source only the production process cleanup and race helpers. This executes no
# Maven build, GitHub request, SSH command, or deployment setup.
PROCESS_TREE_HELPER=$(awk '
    /^terminate_process_tree\(\) \{/ {copy = 1}
    copy {print}
    copy && /^}$/ {exit}
' "$DEPLOY_SCRIPT")
eval "$PROCESS_TREE_HELPER"

RACE_HELPERS=$(awk '
    /^# BEGIN_JAR_BUILD_RACE_HELPERS$/ {copy = 1; next}
    /^# END_JAR_BUILD_RACE_HELPERS$/ {copy = 0}
    copy {print}
' "$DEPLOY_SCRIPT")
eval "$RACE_HELPERS"

UPLOAD_STATUS_DIR="$TMP_ROOT/status"
mkdir -p "$UPLOAD_STATUS_DIR"
BUILD_RACE_PIDS=()

ci_fast() { sleep 0.1; echo ci-ready; }
maven_slow() { sleep 30 & echo $! > "$TMP_ROOT/maven-loser.pid"; wait; }
run_first_success_build_race "$TMP_ROOT/ci-wins" ci ci_fast maven maven_slow
[ "$BUILD_RACE_WINNER" = ci ] || fail "CI did not win the fast-CI race"
[ "$(cat "$TMP_ROOT/ci-wins/winner")" = ci ] || fail "CI winner was not persisted atomically"
[ -s "$TMP_ROOT/maven-loser.pid" ] || fail "Maven loser child PID was not captured"
if kill -0 "$(cat "$TMP_ROOT/maven-loser.pid")" 2>/dev/null; then
    fail "CI win left the Maven loser child running"
fi

ci_slow() { sleep 30 & echo $! > "$TMP_ROOT/ci-loser.pid"; wait; }
maven_fast() { sleep 0.1; echo maven-ready; }
run_first_success_build_race "$TMP_ROOT/maven-wins" ci ci_slow maven maven_fast
[ "$BUILD_RACE_WINNER" = maven ] || fail "Maven did not win the fast-Maven race"
[ -s "$TMP_ROOT/ci-loser.pid" ] || fail "CI loser child PID was not captured"
if kill -0 "$(cat "$TMP_ROOT/ci-loser.pid")" 2>/dev/null; then
    fail "Maven win left the CI loser child running"
fi

ci_fail() { echo ci-failed >&2; return 7; }
run_first_success_build_race "$TMP_ROOT/one-fails" ci ci_fail maven maven_fast
[ "$BUILD_RACE_WINNER" = maven ] || fail "one failed contender prevented the other from winning"

maven_fail() { echo maven-failed >&2; return 8; }
if run_first_success_build_race "$TMP_ROOT/both-fail" ci ci_fail maven maven_fail >/dev/null 2>&1; then
    fail "both failed contenders produced a winner"
fi

simultaneous_left() { sleep 0.1; }
simultaneous_right() { sleep 0.1; }
run_first_success_build_race "$TMP_ROOT/simultaneous" ci simultaneous_left maven simultaneous_right
case "$BUILD_RACE_WINNER" in
    ci|maven) ;;
    *) fail "simultaneous success produced an invalid winner" ;;
esac
[ "$(wc -l < "$TMP_ROOT/simultaneous/winner" | tr -d ' ')" = 1 ] || fail "winner file is not singular"
[ -d "$TMP_ROOT/simultaneous/claim" ] || fail "atomic claim directory is missing"

# Exact recorded PID cleanup must leave unrelated processes alive.
sleep 30 &
UNRELATED_PID=$!
run_first_success_build_race "$TMP_ROOT/unrelated" ci ci_fast maven maven_slow
kill -0 "$UNRELATED_PID" 2>/dev/null || fail "race cleanup killed an unrelated process"
kill "$UNRELATED_PID" 2>/dev/null || true
wait "$UNRELATED_PID" 2>/dev/null || true
UNRELATED_PID=""

# Explicit local modes must not enter the race.
assert_contains 'if [ -n "$SKIP_BUILD" ]; then'
assert_contains 'SKIP_BUILD=1 但可信 manifest 未命中；安全回退本地 clean package'
assert_contains '本地 Maven 打包 (SKIP_CLEAN=1, 增量模式 — 不参与 CI 竞速)'
assert_contains '[ "${ENABLE_CI_ARTIFACT_REUSE:-0}" != "1" ]'
assert_contains 'manifest 未命中，立即执行本地 clean package'
assert_contains 'CI_ARTIFACT_DOWNLOAD_TIMEOUT:-180'

echo "PASS: CI/local build race selects one valid winner and terminates only recorded loser trees"
