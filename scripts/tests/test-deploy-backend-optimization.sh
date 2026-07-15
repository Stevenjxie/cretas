#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
DEPLOY_SCRIPT="$ROOT_DIR/scripts/deploy/deploy-backend.sh"
CI_WORKFLOW="$ROOT_DIR/.github/workflows/ci.yml"

assert_contains() {
    local file=$1
    local expected=$2
    if ! grep -Fq -- "$expected" "$file"; then
        echo "FAIL: missing '$expected' in $file" >&2
        exit 1
    fi
}

assert_not_contains() {
    local file=$1
    local unexpected=$2
    if grep -Fq -- "$unexpected" "$file"; then
        echo "FAIL: unexpected '$unexpected' in $file" >&2
        exit 1
    fi
}

# CI artifact must be tied to the exact commit and carry both integrity manifests.
assert_contains "$CI_WORKFLOW" 'name: cretas-java-${{ github.sha }}'
assert_contains "$CI_WORKFLOW" 'printf '\''%s\n'\'' "$GITHUB_SHA" > "$JAR.commit"'
assert_contains "$CI_WORKFLOW" 'sha256sum "$JAR" > "$JAR.sha256"'
assert_contains "$CI_WORKFLOW" 'compression-level: 0'
assert_contains "$DEPLOY_SCRIPT" 'ARTIFACT_NAME="cretas-java-$HEAD_SHA"'
assert_contains "$DEPLOY_SCRIPT" '.workflow_run.head_branch == \"main\"'
assert_contains "$DEPLOY_SCRIPT" '.workflow_run.head_sha == \"$HEAD_SHA\"'
assert_contains "$DEPLOY_SCRIPT" 'sha256sum -c "$JAR_NAME.sha256"'
assert_contains "$DEPLOY_SCRIPT" '无可用的精确 SHA CI 制品，回退本地 clean package'

# Repeated startup crashes must fail before the full 150-second timeout and show logs.
assert_contains "$DEPLOY_SCRIPT" 'STARTUP_RESTART_LIMIT="${STARTUP_RESTART_LIMIT:-2}"'
assert_contains "$DEPLOY_SCRIPT" "''|*[!0-9]*|0)"
assert_contains "$DEPLOY_SCRIPT" 'RESTART_LIMIT:'
assert_contains "$DEPLOY_SCRIPT" 'set +e'
assert_contains "$DEPLOY_SCRIPT" 'journalctl -u $IDLE_SERVICE -n 80 --no-pager -o short-iso'

# The proven delayed-crash protection remains unchanged.
assert_contains "$DEPLOY_SCRIPT" 'for ROUND in 1 2 3 4 5; do'
assert_contains "$DEPLOY_SCRIPT" 'sleep 6'

# Merely having gh authenticated must not fabricate a release URL.
assert_contains "$DEPLOY_SCRIPT" 'gh release view "$VERSION" --repo "$REPO"'
assert_not_contains "$DEPLOY_SCRIPT" '[ "$HAS_GH" = "true" ] && echo "  Release:'

# Flyway preflight scans the source once and reuses a sorted manifest. In
# particular, do not spawn one basename process per migration on Git Bash.
assert_contains "$DEPLOY_SCRIPT" 'SRC_FLY_PATHS=$(find "$FLYWAY_SRC_DIR" -type f -name '\''V*.sql'\'' -print'
assert_contains "$DEPLOY_SCRIPT" 'SRC_FLY_SORTED=$(printf '\''%s\n'\'' "$SRC_FLY_PATHS" | sed '\''s#^.*/##'\'' | LC_ALL=C sort)'
assert_contains "$DEPLOY_SCRIPT" 'if [ -n "$SRC_FLY_PATHS" ]; then'
assert_contains "$DEPLOY_SCRIPT" 'DUPS=$(printf '\''%s\n'\'' "$SRC_FLY_SORTED"'
assert_not_contains "$DEPLOY_SCRIPT" "-exec basename {}"

# Upload losers must be stopped by exact recorded PID trees. Raw Git Bash `$!`
# values are MSYS PIDs, so taskkill /PID is unsafe without PID conversion.
assert_contains "$DEPLOY_SCRIPT" 'terminate_process_tree() {'
assert_contains "$DEPLOY_SCRIPT" 'child_pids=$(ps -e 2>/dev/null | awk -v parent="$pid"'
assert_contains "$DEPLOY_SCRIPT" 'terminate_upload_tasks'
assert_not_contains "$DEPLOY_SCRIPT" 'taskkill.exe //PID "$pid"'
assert_not_contains "$DEPLOY_SCRIPT" 'pkill -9 -f "aws.*$JAR_NAME"'
assert_not_contains "$DEPLOY_SCRIPT" 'jobs -p 2>/dev/null | xargs'

# Behavioral probe: source only the PID-tree helper, create a private wrapper
# and child, then prove both are gone. No network or deployment is involved.
PROCESS_TREE_HELPER=$(awk '
    /^terminate_process_tree\(\) \{/ {copy = 1}
    copy {print}
    copy && /^}$/ {exit}
' "$DEPLOY_SCRIPT")
eval "$PROCESS_TREE_HELPER"
bash -c 'sleep 30 & wait' &
probe_parent=$!
sleep 1
if [[ "${OSTYPE:-}" == msys* || "${OSTYPE:-}" == cygwin* || "${OSTYPE:-}" == win32* ]]; then
    probe_child=$(ps -e 2>/dev/null | awk -v parent="$probe_parent" 'NR > 1 && $2 == parent {print $1; exit}')
else
    probe_child=$(pgrep -P "$probe_parent" 2>/dev/null | head -1 || true)
fi
[ -n "$probe_child" ] || { echo "FAIL: process-tree probe child not found" >&2; kill "$probe_parent" 2>/dev/null || true; exit 1; }
terminate_process_tree "$probe_parent"
wait "$probe_parent" 2>/dev/null || true
if kill -0 "$probe_parent" 2>/dev/null || kill -0 "$probe_child" 2>/dev/null; then
    echo "FAIL: process-tree cleanup left a probe process running" >&2
    exit 1
fi

echo "PASS: deploy optimization contracts are present"
