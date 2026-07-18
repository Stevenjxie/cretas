#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
DEPLOY_SCRIPT="$ROOT_DIR/scripts/deploy/deploy-backend.sh"
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

TIMING_HELPERS=$(awk '
    /^# BEGIN_DEPLOY_TIMING_HELPERS$/ {copy = 1; next}
    /^# END_DEPLOY_TIMING_HELPERS$/ {copy = 0}
    copy {print}
' "$DEPLOY_SCRIPT")

UPLOAD_STATUS_DIR="$TMP_ROOT/status"
DEPLOY_SCRIPT_STARTED_AT=100
PROJECT_ROOT="$ROOT_DIR"
RELEASE_BACKEND_PATH="backend/java/cretas-api"
DEPLOY_REPORT_PATH="$TMP_ROOT/deploy-report.json"
DEPLOY_OUTCOME=deployed
FAKE_NOW=100
deploy_epoch() { printf '%s\n' "$FAKE_NOW"; }
eval "$TIMING_HELPERS"
# eval defines the production function; replace only its clock dependency.
deploy_epoch() { printf '%s\n' "$FAKE_NOW"; }

deploy_timing_begin preparation "准备与 Flyway 预检" 100
FAKE_NOW=109
deploy_timing_end preparation
deploy_timing_begin build "构建与 JAR 完整性"
FAKE_NOW=171
deploy_timing_end build
deploy_timing_begin idle_startup "idle Java 启动至健康"
FAKE_NOW=275

print_deploy_timing_summary 7 > "$TMP_ROOT/summary.txt"
SUMMARY=$(cat "$TMP_ROOT/summary.txt")
grep -Fq '准备与 Flyway 预检' <<< "$SUMMARY" || fail "preparation stage missing"
grep -Eq '准备与 Flyway 预检 +9s' <<< "$SUMMARY" || fail "preparation duration incorrect"
grep -Eq '构建与 JAR 完整性 +62s' <<< "$SUMMARY" || fail "build duration incorrect"
grep -Eq 'idle Java 启动至健康 +104s +\[未完成\]' <<< "$SUMMARY" || fail "open stage not reported"
grep -Eq '总耗时 +175s' <<< "$SUMMARY" || fail "total duration incorrect"
grep -Fq 'FAILED (exit=7)' <<< "$SUMMARY" || fail "failure status missing"
grep -Fq '"format": "cretas-backend-deploy-report-v1"' "$DEPLOY_REPORT_PATH" \
    || fail "structured deploy report missing"
grep -Fq '"outcome": "deployed"' "$DEPLOY_REPORT_PATH" \
    || fail "structured deploy outcome missing"
grep -Fq '"total_wall_seconds": 175' "$DEPLOY_REPORT_PATH" \
    || fail "structured deploy report total is incorrect"
grep -Fq '"idle_startup": {"seconds": 104, "completed": false}' "$DEPLOY_REPORT_PATH" \
    || fail "structured deploy report did not retain open-stage timing"
python -m json.tool "$DEPLOY_REPORT_PATH" >/dev/null \
    || fail "structured deploy report is not valid JSON"

# Summary is one-shot even if both the success path and EXIT trap request it.
print_deploy_timing_summary 0 > "$TMP_ROOT/second-summary.txt"
[ ! -s "$TMP_ROOT/second-summary.txt" ] || fail "timing summary printed twice"

for contract in \
    'deploy_timing_begin build "构建与 JAR 完整性"' \
    'deploy_timing_begin upload "上传并校验制品"' \
    'deploy_timing_begin remote_install "服务器安装 JAR"' \
    'deploy_timing_begin idle_startup "idle Java 启动至健康"' \
    'deploy_timing_begin post_switch_observation "切流后稳定观察"' \
    'deploy_timing_begin verification "最终健康与服务状态验证"'; do
    grep -Fq "$contract" "$DEPLOY_SCRIPT" || fail "missing instrumentation: $contract"
done

for readiness_contract in \
    'BLUE_MANAGEMENT_PORT=10012' \
    'GREEN_MANAGEMENT_PORT=10022' \
    'http://localhost:$IDLE_MANAGEMENT_PORT/actuator/health/readiness'; do
    grep -Fq "$readiness_contract" "$DEPLOY_SCRIPT" || fail "missing core readiness contract: $readiness_contract"
done

echo "PASS: deploy timing summary reports completed, failed/open, and total stages once"
