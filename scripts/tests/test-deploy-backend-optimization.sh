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

echo "PASS: deploy optimization contracts are present"
