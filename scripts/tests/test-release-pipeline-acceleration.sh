#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
CI_WORKFLOW="$ROOT_DIR/.github/workflows/ci.yml"
E2E_WORKFLOW="$ROOT_DIR/.github/workflows/e2e-pr.yml"
MIGRATION="$ROOT_DIR/backend/java/cretas-api/src/main/resources/db/flyway/V20261028_68_1__bom_items_standard_quantity_compat.sql"
WAIT_FOR_HEALTH="$ROOT_DIR/tests/v1-e2e/scripts/wait-for-health.sh"
AGENTS_FILE="$ROOT_DIR/AGENTS.md"
DEPLOY_SKILL="$ROOT_DIR/.agents/skills/deploy-backend/SKILL.md"
L1_SMOKE="$ROOT_DIR/tests/v1-e2e/web/l1-smoke.spec.ts"
RELEASE_PREFLIGHT="$ROOT_DIR/scripts/deploy/release-preflight.sh"
RELEASE_ORCHESTRATOR="$ROOT_DIR/scripts/deploy/release-cretas.sh"
WEB_MANIFEST="$ROOT_DIR/scripts/deploy/release-web-manifest.sh"

assert_contains() {
    local file=$1
    local expected=$2
    grep -Fq -- "$expected" "$file" || {
        echo "FAIL: missing '$expected' in $file" >&2
        exit 1
    }
}

line_number() {
    local file=$1
    local expected=$2
    grep -Fnm1 -- "$expected" "$file" | cut -d: -f1
}

# CI is manual-only now, so a second operator-triggered run must not silently
# cancel an in-flight release audit.
assert_contains "$CI_WORKFLOW" 'workflow_dispatch:'
assert_contains "$CI_WORKFLOW" 'cancel-in-progress: false'

package_line=$(line_number "$CI_WORKFLOW" 'name: Package exact-commit deploy artifact')
artifact_upload_line=$(line_number "$CI_WORKFLOW" 'name: Upload exact-commit deploy artifact')
full_verify_line=$(line_number "$CI_WORKFLOW" 'name: Maven compile and test')
if ! (( package_line < artifact_upload_line && artifact_upload_line < full_verify_line )); then
    echo "FAIL: exact-main JAR must upload before the long full Maven verify" >&2
    exit 1
fi
# 这条原先钉的是单行 `run: mvn -B package -Dmaven.test.skip=true -pl .`。
# #2013 之后打包步骤改成多行 `run: |` 且分两支: 给了选择器就【先跑测试再打包】(测试失败
# 就没有制品, 而不是产出一份没人把关过的制品), 没给才退回 skip。旧断言钉的是一个被刻意
# 废弃的行为, 所以它一直红着 —— 换成当前那个更强的契约。
#
# 不去 grep 注释文本(那太脆), 而是断言两支都在、且【带测试的那支是主支】。
tested_package_line=$(line_number "$CI_WORKFLOW" 'mvn -B package -Dtest="$TARGET_TESTS"')
skip_package_line=$(line_number "$CI_WORKFLOW" 'mvn -B package -Dmaven.test.skip=true -pl .')
if ! (( tested_package_line < skip_package_line )); then
    echo "FAIL: 带测试的打包必须是主支, -Dmaven.test.skip 只能是没给选择器时的兜底" >&2
    exit 1
fi

backend_line=$(line_number "$E2E_WORKFLOW" 'name: Start Java backend (background)')
web_deps_line=$(line_number "$E2E_WORKFLOW" 'name: Install web-admin deps')
browser_line=$(line_number "$E2E_WORKFLOW" 'name: Install playwright browsers (chromium only)')
health_line=$(line_number "$E2E_WORKFLOW" 'name: Wait for backend health (up to 4 min)')
if ! (( backend_line < web_deps_line && backend_line < browser_line && browser_line < health_line )); then
    echo "FAIL: Java startup must precede Node installs, and health wait must follow them" >&2
    exit 1
fi

assert_contains "$MIGRATION" 'ADD COLUMN IF NOT EXISTS standard_quantity DECIMAL(15, 4);'
assert_contains "$E2E_WORKFLOW" '240 "$backend_pid"'
assert_contains "$WAIT_FOR_HEALTH" 'kill -0 "$WATCH_PID"'
assert_contains "$AGENTS_FILE" '最终只读代码审查应安排在最后一次目标构建/测试之前'
assert_contains "$DEPLOY_SKILL" 'feature-head=$(git rev-parse HEAD)'
assert_contains "$DEPLOY_SKILL" 'feature-head == <pr-head>'
assert_contains "$DEPLOY_SKILL" 'git merge-base --is-ancestor <merge-commit> origin/main'
assert_contains "$DEPLOY_SKILL" 'git diff --quiet "$feature-head" <merge-commit>'
assert_contains "$DEPLOY_SKILL" 'git switch --detach origin/main'
assert_contains "$DEPLOY_SKILL" './scripts/deploy/release-preflight.sh'
assert_contains "$DEPLOY_SKILL" 'Never trigger or wait for an Artifact during a release.'
assert_contains "$DEPLOY_SKILL" '--stage-backend YES-STAGE'
assert_contains "$DEPLOY_SKILL" 'Candidate archives are also keyed by the exact `web-admin` Git tree.'
assert_contains "$AGENTS_FILE" '--stage-backend YES-STAGE'
assert_contains "$AGENTS_FILE" '按 `web-admin` Git tree 保留可恢复缓存'
assert_contains "$RELEASE_ORCHESTRATOR" '--stage-backend YES-STAGE'
assert_contains "$RELEASE_ORCHESTRATOR" 'ensure_exact_main_after_artifacts "artifact validation/fallback build"'
assert_contains "$WEB_MANIFEST" 'web_release_validate_cached'
assert_contains "$RELEASE_PREFLIGHT" 'Fast, read-only release gates.'
assert_contains "$L1_SMOKE" '/\b401\b|Unauthorized|500 Internal|NoResourceFoundException/i'

# A dead backend must fail immediately instead of consuming the full timeout.
set +e
bash "$WAIT_FOR_HEALTH" http://127.0.0.1:1/health 10 999999 >/dev/null 2>&1
dead_pid_rc=$?
set -e
if [ "$dead_pid_rc" -eq 0 ]; then
    echo "FAIL: wait-for-health accepted a dead watched PID" >&2
    exit 1
fi

echo "PASS: release pipeline acceleration contracts are present"
