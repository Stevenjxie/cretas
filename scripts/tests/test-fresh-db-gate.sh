#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT_DIR/scripts/testing/fresh-db-gate.sh"
COMPOSE="$ROOT_DIR/docker-compose.fresh-db.yml"
CI_WORKFLOW="$ROOT_DIR/.github/workflows/ci.yml"

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

assert_contains() {
    local haystack="$1"
    local needle="$2"
    [[ "$haystack" == *"$needle"* ]] || fail "missing [$needle]"
}

[ -f "$SCRIPT" ] || fail "missing fresh DB runner"
[ -f "$COMPOSE" ] || fail "missing fresh DB compose file"
bash -n "$SCRIPT"

compose_text=$(cat "$COMPOSE")
assert_contains "$compose_text" 'pgvector/pgvector:pg17'
assert_contains "$compose_text" '127.0.0.1:${FRESH_DB_PORT:-55432}:5432'
assert_contains "$compose_text" 'POSTGRES_PASSWORD: cretas_local_only'
assert_contains "$compose_text" 'tmpfs:'

dry_output=$(bash "$SCRIPT" --dry-run)
assert_contains "$dry_output" 'pgvector/pgvector:pg17 on 127.0.0.1:55432 (never 5432)'
assert_contains "$dry_output" "-Dspring.profiles.active=test"
assert_contains "$dry_output" "-Dtest='*RepositoryQueryValidationTest'"
assert_contains "$dry_output" '-Dspring-boot.run.profiles=pg'
assert_contains "$dry_output" 'flyway_schema_history'
assert_contains "$dry_output" 'cleanup         : remove'

keep_output=$(bash "$SCRIPT" --dry-run --keep)
assert_contains "$keep_output" 'cleanup         : keep'

set +e
port_output=$(FRESH_DB_PORT=5432 bash "$SCRIPT" --dry-run 2>&1)
port_rc=$?
project_output=$(FRESH_DB_PROJECT=production bash "$SCRIPT" --dry-run 2>&1)
project_rc=$?
set -e
[ "$port_rc" -ne 0 ] || fail "port 5432 was accepted"
[ "$project_rc" -ne 0 ] || fail "unsafe Compose project was accepted"
assert_contains "$port_output" 'port 5432 is intentionally forbidden'
assert_contains "$project_output" 'FRESH_DB_PROJECT must be cretas-fresh-db'

script_text=$(cat "$SCRIPT")
ci_text=$(cat "$CI_WORKFLOW")
assert_contains "$script_text" 'down -v --remove-orphans'
assert_contains "$script_text" 'CREATE EXTENSION IF NOT EXISTS vector;'
assert_contains "$script_text" 'SELECT COUNT(*) FROM flyway_schema_history WHERE success = false;'
assert_contains "$script_text" 'Docker CLI not found; install/start Docker Desktop and reopen Git Bash'
# Guard against silently drifting away from the actual CI Repository gate.
assert_contains "$ci_text" 'JPA repository query startup gate'
assert_contains "$ci_text" "-Dspring.profiles.active=test"
assert_contains "$ci_text" "-Dtest='*RepositoryQueryValidationTest'"
assert_contains "$ci_text" '-Dsurefire.failIfNoSpecifiedTests=false'

echo "PASS: fresh DB gate dry-run and safety contract"
