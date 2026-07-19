#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEPLOY_SCRIPT="$ROOT/scripts/deploy/deploy-smartbi-python.sh"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }

bash -n "$DEPLOY_SCRIPT" || fail "deploy script syntax invalid"

help_output="$(bash "$DEPLOY_SCRIPT" --help)"
grep -Fq -- '--migration-target VERSION' <<< "$help_output" \
    || fail "help does not document migration target"
grep -Fq -- '--migration-only' <<< "$help_output" \
    || fail "help does not document migration-only mode"

assert_rejected() {
    local expected="$1"
    shift
    local output status
    set +e
    output="$(bash "$DEPLOY_SCRIPT" "$@" 2>&1)"
    status=$?
    set -e
    [[ "$status" -ne 0 ]] || fail "command unexpectedly succeeded: $*"
    grep -Fq -- "$expected" <<< "$output" \
        || fail "missing rejection '$expected' for: $*"
}

assert_rejected '--migration-target 需要 VERSION 参数' --migration-target
assert_rejected '--migration-target 必须匹配' --migration-target latest
assert_rejected '--migration-target 必须匹配' --migration-target V20261028_4
assert_rejected '未知参数' --migration-target V20261028_04 --surprise
assert_rejected '不支持 --env all' --env all --migration-target V20261028_04
assert_rejected '--migration-only 必须同时提供' --migration-only
assert_rejected '在当前 exact main 中不存在' --migration-target V20991231_99

set +e
combined_output="$(
    SKIP_MIGRATIONS=1 bash "$DEPLOY_SCRIPT" \
        --env prod --migration-target V20261028_04 2>&1
)"
combined_status=$?
set -e
[[ "$combined_status" -ne 0 ]] || fail "skip + target unexpectedly succeeded"
grep -Fq '不能同时使用' <<< "$combined_output" \
    || fail "skip + target conflict was not explicit"

grep -Fq 'migration_args=(--target "$MIGRATION_TARGET")' "$DEPLOY_SCRIPT" \
    || fail "target is not converted to a runner argument"
grep -Fq -- '--env "$DEPLOY_ENV" --migs-dir "$REMOTE_MIGRATION_DIR"' "$DEPLOY_SCRIPT" \
    || fail "runner is not pinned to the exact-main migration bundle"
grep -Fq -- '"${migration_args[@]}"' "$DEPLOY_SCRIPT" \
    || fail "target is not forwarded to the remote runner"
grep -Fq '[migration-only] 完成；未同步应用代码、未安装依赖、未重启服务' "$DEPLOY_SCRIPT" \
    || fail "migration-only completion boundary is missing"
grep -Fq 'set -eo pipefail' "$DEPLOY_SCRIPT" \
    || fail "rsync pipelines are not fail-closed"

# Execute a valid migration-only request with stubbed transport. The copied
# script resolves a fake exact-main SHA, stages two --delete rsync inputs under
# that SHA, forwards --migs-dir/--target, and never reaches restart/dependency
# paths. A second run forces rsync failure to prove pipefail stops before runner.
FAKE_PROJECT="$TMP_DIR/project"
FAKE_BIN="$TMP_DIR/bin"
MOCK_LOG="$TMP_DIR/transport.log"
mkdir -p \
    "$FAKE_PROJECT/scripts/deploy" \
    "$FAKE_PROJECT/scripts/lib" \
    "$FAKE_PROJECT/scripts/migrations" \
    "$FAKE_PROJECT/backend/python/smartbi/database/migrations" \
    "$FAKE_BIN"
cp "$DEPLOY_SCRIPT" "$FAKE_PROJECT/scripts/deploy/deploy-smartbi-python.sh"
touch \
    "$FAKE_PROJECT/scripts/migrations/apply-smartbi-migrations.sh" \
    "$FAKE_PROJECT/scripts/migrations/backfill-applied.sh" \
    "$FAKE_PROJECT/scripts/migrations/test-runner.sh" \
    "$FAKE_PROJECT/backend/python/smartbi/database/migrations/V20261028_04__target.sql"

cat > "$FAKE_PROJECT/scripts/lib/deploy-common.sh" <<'COMMON'
log() { printf '[%s] %s\n' "$1" "${*:2}"; }
check_git_sync() { :; }
COMMON
cat > "$FAKE_BIN/git" <<'FAKEGIT'
#!/usr/bin/env bash
printf '%040d\n' 0 | tr '0' 'a'
FAKEGIT
cat > "$FAKE_BIN/ssh" <<'FAKESSH'
#!/usr/bin/env bash
printf 'ssh|%s\n' "$*" >> "$MOCK_LOG"
FAKESSH
cat > "$FAKE_BIN/rsync" <<'FAKERSYNC'
#!/usr/bin/env bash
printf 'rsync|%s\n' "$*" >> "$MOCK_LOG"
if [[ "${MOCK_RSYNC_FAIL:-0}" == "1" ]]; then exit 23; fi
FAKERSYNC
chmod +x "$FAKE_BIN/git" "$FAKE_BIN/ssh" "$FAKE_BIN/rsync"

: > "$MOCK_LOG"
success_output="$(
    PATH="$FAKE_BIN:$PATH" MOCK_LOG="$MOCK_LOG" \
        bash "$FAKE_PROJECT/scripts/deploy/deploy-smartbi-python.sh" \
        --env prod --migration-only --migration-target V20261028_04
)"
grep -Fq '[migration-only] 完成' <<< "$success_output" \
    || fail "valid migration-only path did not complete"
[[ "$(grep -c '^rsync|' "$MOCK_LOG")" == "2" ]] \
    || fail "migration-only should sync exactly SQL and runner bundles"
grep -Eq 'rsync\|.*--delete.*\.release-migrations/a{40}/sql/' "$MOCK_LOG" \
    || fail "SQL bundle is not SHA-isolated and --delete aligned"
grep -Eq 'rsync\|.*--delete.*\.release-migrations/a{40}/scripts/' "$MOCK_LOG" \
    || fail "runner bundle is not SHA-isolated and --delete aligned"
grep -Eq 'ssh\|.*apply-smartbi-migrations\.sh --env prod --migs-dir .*\.release-migrations/a{40}/sql --target V20261028_04' "$MOCK_LOG" \
    || fail "valid target/migs-dir argv did not reach the runner"
if grep -Eq 'systemctl|restart|requirements' "$MOCK_LOG"; then
    fail "migration-only touched restart or dependency paths"
fi

: > "$MOCK_LOG"
set +e
PATH="$FAKE_BIN:$PATH" MOCK_LOG="$MOCK_LOG" MOCK_RSYNC_FAIL=1 \
    bash "$FAKE_PROJECT/scripts/deploy/deploy-smartbi-python.sh" \
    --env prod --migration-only --migration-target V20261028_04 \
    >"$TMP_DIR/rsync-failure.out" 2>&1
failure_status=$?
set -e
[[ "$failure_status" -ne 0 ]] || fail "rsync failure was masked by tail"
if grep -Fq 'apply-smartbi-migrations.sh --env' "$MOCK_LOG"; then
    fail "runner executed after rsync failure"
fi

echo "PASS: SmartBI Python migration target CLI is validated and forwarded"
