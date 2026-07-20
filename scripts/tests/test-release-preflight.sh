#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
PREFLIGHT="$ROOT_DIR/scripts/deploy/release-preflight.sh"
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

assert_contains() {
    local actual="$1"
    local expected="$2"
    [[ "$actual" == *"$expected"* ]] || fail "missing [$expected]"
}

create_fixture() {
    local name="$1"
    local repo="$TMP_ROOT/$name"
    local remote="$TMP_ROOT/$name-remote.git"

    git init --bare --quiet "$remote"
    git init --quiet -b main "$repo"
    git -C "$repo" config user.name "Preflight Test"
    git -C "$repo" config user.email "preflight@example.test"
    mkdir -p "$repo/scripts" "$repo/.github/workflows" \
        "$repo/backend/java/cretas-api/src/main/resources/db/flyway"
    printf '%s\n' '#!/usr/bin/env bash' 'echo ok' > "$repo/scripts/ok.sh"
    printf '%s\n' 'name: fixture' 'on: [push]' 'jobs: {}' > "$repo/.github/workflows/test.yml"
    printf '%s\n' '-- fixture migration' > "$repo/backend/java/cretas-api/src/main/resources/db/flyway/V1__fixture.sql"
    git -C "$repo" add .
    git -C "$repo" commit --quiet -m "fixture"
    git -C "$repo" remote add origin "$remote"
    git -C "$repo" push --quiet -u origin main
    printf '%s\n' "$repo"
}

success_repo=$(create_fixture success)
success_output=$(bash "$PREFLIGHT" --repo-root "$success_repo" --skip-fetch 2>&1) || fail "success fixture failed"
assert_contains "$success_output" "[PASS] git release truth ("
assert_contains "$success_output" "[PASS] Flyway static checks ("
assert_contains "$success_output" "[PASS] shell syntax ("
assert_contains "$success_output" "[PASS] diff check ("
assert_contains "$success_output" "release preflight PASSED (total:"

# A committed duplicate is clean and exactly on origin/main, so the Flyway gate
# itself must be the first failure and later gates must not run.
failure_repo=$(create_fixture failure)
printf '%s\n' '-- duplicate fixture migration' > \
    "$failure_repo/backend/java/cretas-api/src/main/resources/db/flyway/V1__duplicate.sql"
git -C "$failure_repo" add .
git -C "$failure_repo" commit --quiet -m "duplicate migration"
git -C "$failure_repo" push --quiet
set +e
failure_output=$(bash "$PREFLIGHT" --repo-root "$failure_repo" --skip-fetch 2>&1)
failure_rc=$?
set -e
[ "$failure_rc" -ne 0 ] || fail "duplicate Flyway fixture unexpectedly passed"
assert_contains "$failure_output" "Flyway version collision detected"
assert_contains "$failure_output" "[FAIL] Flyway static checks ("
assert_contains "$failure_output" "release preflight FAILED (total:"
if [[ "$failure_output" == *"--> shell syntax"* ]]; then
    fail "preflight did not fail fast after Flyway error"
fi

# Git clean/exact-main requirements are configurable but strict by default.
printf '%s\n' 'local diagnostic edit' >> "$success_repo/README.local"
set +e
dirty_output=$(bash "$PREFLIGHT" --repo-root "$success_repo" --skip-fetch 2>&1)
dirty_rc=$?
set -e
[ "$dirty_rc" -ne 0 ] || fail "dirty worktree unexpectedly passed strict mode"
assert_contains "$dirty_output" "worktree must be clean"

diagnostic_output=$(bash "$PREFLIGHT" --repo-root "$success_repo" --skip-fetch --allow-dirty 2>&1) \
    || fail "--allow-dirty diagnostic mode failed"
assert_contains "$diagnostic_output" "release preflight PASSED (total:"

# Windows CRLF is valid EOL, not trailing whitespace. Real spaces/tabs before
# EOL must still fail the same diff gate.
crlf_repo=$(create_fixture crlf)
mkdir -p "$crlf_repo/web-admin/src"
printf 'first\r\nsecond\r\n' > "$crlf_repo/web-admin/src/crlf.ts"
git -C "$crlf_repo" add web-admin/src/crlf.ts
git -C "$crlf_repo" commit --quiet -m "add CRLF source"
git -C "$crlf_repo" push --quiet
crlf_output=$(bash "$PREFLIGHT" --repo-root "$crlf_repo" --skip-fetch 2>&1) \
    || fail "CRLF fixture was rejected as trailing whitespace"
assert_contains "$crlf_output" "release preflight PASSED (total:"

trailing_repo=$(create_fixture trailing)
mkdir -p "$trailing_repo/web-admin/src"
printf 'bad trailing space \n' > "$trailing_repo/web-admin/src/trailing.ts"
git -C "$trailing_repo" add web-admin/src/trailing.ts
git -C "$trailing_repo" commit --quiet -m "add trailing whitespace"
git -C "$trailing_repo" push --quiet
set +e
trailing_output=$(bash "$PREFLIGHT" --repo-root "$trailing_repo" --skip-fetch 2>&1)
trailing_rc=$?
set -e
[ "$trailing_rc" -ne 0 ] || fail "real trailing whitespace unexpectedly passed"
assert_contains "$trailing_output" "HEAD commit diff check failed"

echo "PASS: release preflight success, failure, CRLF, whitespace, configuration, and timing summaries"
