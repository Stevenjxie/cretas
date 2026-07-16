#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
EMBEDDING="$ROOT_DIR/scripts/deploy/deploy-embedding.sh"
MODEL="$ROOT_DIR/scripts/deploy/deploy-embedding-model.sh"
PULL_JAR="$ROOT_DIR/scripts/pull-jar.sh"
CI_WORKFLOW="$ROOT_DIR/.github/workflows/ci.yml"
KB_WORKFLOW="$ROOT_DIR/.github/workflows/kb-drift-check.yml"
E2E_WORKFLOW="$ROOT_DIR/.github/workflows/e2e-pr.yml"
POST_DEPLOY_WORKFLOW="$ROOT_DIR/.github/workflows/e2e-post-deploy.yml"
TOOL_AUDIT_WORKFLOW="$ROOT_DIR/.github/workflows/tool-isolation-audit.yml"
DAILY_WORKFLOW="$ROOT_DIR/.github/workflows/daily-integration.yml"
THRESHOLD_WORKFLOW="$ROOT_DIR/.github/workflows/threshold-parity-check.yml"

assert_contains() {
    local file=$1
    local expected=$2
    grep -Fq -- "$expected" "$file" || {
        echo "FAIL: missing '$expected' in $file" >&2
        exit 1
    }
}

assert_not_contains() {
    local file=$1
    local unexpected=$2
    if grep -Fq -- "$unexpected" "$file"; then
        echo "FAIL: unexpected '$unexpected' in $file" >&2
        exit 1
    fi
}

# Embedding JARs use SSH transport and never require a GitHub token on prod.
assert_contains "$EMBEDDING" 'REMOTE_INCOMING="$SERVER_DIR/$JAR_NAME.incoming.$VERSION"'
assert_contains "$EMBEDDING" 'EMBEDDING_DIR="$PROJECT_ROOT/backend/java/embedding-service"'
assert_contains "$EMBEDDING" 'SERVER_DIR="/www/wwwroot/cretas/embedding-service"'
assert_contains "$EMBEDDING" 'rsync -az --progress "$JAR_PATH" "$SERVER:$REMOTE_INCOMING"'
assert_contains "$EMBEDDING" 'scp "$JAR_PATH" "$SERVER:$REMOTE_INCOMING"'
assert_contains "$EMBEDDING" 'systemctl restart "$SERVICE_NAME"'
assert_not_contains "$EMBEDDING" 'ghproxy.cc'
assert_not_contains "$EMBEDDING" 'gh release create'
assert_not_contains "$EMBEDDING" 'nohup java -jar'

# Model upload keeps SSH candidates but must not attempt anonymous private assets.
assert_contains "$MODEL" 'REPO_PRIVATE=$(gh api "repos/$REPO" --jq '\''.private'\'''
assert_contains "$MODEL" 'private repo 的匿名镜像无法下载 Release asset'

# The legacy server pull path is explicitly authenticated for private releases.
assert_contains "$PULL_JAR" 'gh release download "$VERSION"'
assert_not_contains "$PULL_JAR" 'ghproxy.cc'

# GitHub Actions are an explicit independent fallback. Daily integration,
# pull-request checks and release gates run locally, so no workflow may consume
# remote runners automatically.
for workflow in \
    "$CI_WORKFLOW" \
    "$DAILY_WORKFLOW" \
    "$E2E_WORKFLOW" \
    "$POST_DEPLOY_WORKFLOW" \
    "$KB_WORKFLOW" \
    "$THRESHOLD_WORKFLOW" \
    "$TOOL_AUDIT_WORKFLOW"; do
    assert_contains "$workflow" '  workflow_dispatch:'
    assert_not_contains "$workflow" '  push:'
    assert_not_contains "$workflow" '  pull_request:'
    assert_not_contains "$workflow" '  schedule:'
done

assert_contains "$CI_WORKFLOW" 'package_java_artifact:'
assert_contains "$CI_WORKFLOW" 'full_audit:'
assert_contains "$CI_WORKFLOW" 'if: inputs.full_audit'
assert_not_contains "$CI_WORKFLOW" 'dorny/paths-filter'
assert_contains "$CI_WORKFLOW" 'retention-days: 1'
assert_not_contains "$KB_WORKFLOW" '  schedule:'
assert_not_contains "$CI_WORKFLOW" 'retention-days: 14'
assert_not_contains "$E2E_WORKFLOW" 'retention-days: 14'
assert_not_contains "$POST_DEPLOY_WORKFLOW" 'retention-days: 14'
assert_not_contains "$POST_DEPLOY_WORKFLOW" 'retention-days: 7'
assert_contains "$TOOL_AUDIT_WORKFLOW" 'retention-days: 3'

echo "PASS: private repository deployment contracts are present"
