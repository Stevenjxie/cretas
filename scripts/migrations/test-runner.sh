#!/usr/bin/env bash
# Unit tests for apply-smartbi-migrations.sh.
#
# Spec: docs/superpowers/specs/2026-05-07-smartbi-migration-runner-spec.md §4 Phase B.2
# Runs against a temporary postgres database (CREATE/DROP) — never touches
# real smartbi_db / smartbi_prod_db.
#
# Cases:
#   1. fresh DB → bootstrap + apply all migrations
#   2. already-applied → all skip on re-run
#   3. add new file → only it applies
#   4. modify already-applied file → checksum mismatch fail (exit 1)
#   5. broken SQL → tx rollback, tracker not updated, exit 1
#   6. --dry-run → no tracker writes, but detects new
#   7. --target → stops at version
#   8. duplicate filename versions remain distinct
#   9. --target rejects nonexistent, database-ahead, and --env all cases

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNNER="$SCRIPT_DIR/apply-smartbi-migrations.sh"
BOOTSTRAP_SQL="$(cd "$SCRIPT_DIR/../../backend/python/smartbi/database/migrations" && pwd)/V20260507_01__smartbi_migrations_tracker.sql"

TEST_DB="smartbi_runner_test_$$"
WORK_DIR="$(mktemp -d -t smartbi-runner-test-XXXX)"
FIXTURES="$WORK_DIR/migrations"
mkdir -p "$FIXTURES"

PASS=0
FAIL=0
TOTAL=0

# ── Helpers ─────────────────────────────────────────────────────────

cleanup() {
    sudo -u postgres psql -d postgres -c "DROP DATABASE IF EXISTS $TEST_DB" >/dev/null 2>&1 || true
    rm -rf "$WORK_DIR"
}
trap cleanup EXIT

create_db() {
    sudo -u postgres psql -d postgres -c "DROP DATABASE IF EXISTS $TEST_DB" >/dev/null 2>&1 || true
    sudo -u postgres psql -d postgres -c "CREATE DATABASE $TEST_DB" >/dev/null
    # Need smartbi_user role for grants; create if missing (idempotent).
    sudo -u postgres psql -d postgres -tAc \
        "SELECT 1 FROM pg_roles WHERE rolname='smartbi_user'" \
        | grep -q 1 \
        || sudo -u postgres psql -d postgres -c "CREATE ROLE smartbi_user" >/dev/null
}

apply_bootstrap() {
    sudo -u postgres psql -d "$TEST_DB" -v ON_ERROR_STOP=1 -f "$BOOTSTRAP_SQL" >/dev/null
}

reset_fixtures() {
    rm -f "$FIXTURES"/V*.sql
}

write_fixture() {
    local fname="$1" body="$2"
    printf '%s\n' "$body" > "$FIXTURES/$fname"
}

run_runner() {
    bash "$RUNNER" --env test --db-name "$TEST_DB" --migs-dir "$FIXTURES" "$@" 2>&1
}

tracker_count() {
    sudo -u postgres psql -d "$TEST_DB" -tAc "SELECT COUNT(*) FROM smartbi_migrations"
}

tracker_has() {
    local fname="$1"
    local n
    n=$(sudo -u postgres psql -d "$TEST_DB" -tAc \
        "SELECT COUNT(*) FROM smartbi_migrations WHERE filename = '$fname'")
    [ "$n" = "1" ]
}

table_exists() {
    local tname="$1"
    local n
    n=$(sudo -u postgres psql -d "$TEST_DB" -tAc \
        "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = '$tname'")
    [ "$n" = "1" ]
}

assert() {
    local desc="$1"
    local cond="$2"
    TOTAL=$((TOTAL + 1))
    if eval "$cond"; then
        echo "  PASS: $desc"
        PASS=$((PASS + 1))
    else
        echo "  FAIL: $desc"
        echo "         (cond: $cond)"
        FAIL=$((FAIL + 1))
    fi
}

start_case() {
    echo ""
    echo "── $1 ─────────────────────────────────"
    create_db
    apply_bootstrap
    reset_fixtures
}

# ── Test 1: fresh DB → bootstrap + apply all ────────────────────────
start_case "Test 1: fresh DB applies all V*.sql"
write_fixture "V20260101_01__test_a.sql" "CREATE TABLE test_runner_a (id INT);"
write_fixture "V20260101_02__test_b.sql" "CREATE TABLE test_runner_b (id INT);"
write_fixture "V20260101_03__test_c.sql" "CREATE TABLE test_runner_c (id INT);"
output=$(run_runner) || true
echo "$output" | sed 's/^/    /'
assert "tracker has 3 rows"            '[ "$(tracker_count)" = "3" ]'
assert "test_runner_a created"         'table_exists test_runner_a'
assert "test_runner_b created"         'table_exists test_runner_b'
assert "test_runner_c created"         'table_exists test_runner_c'
assert "output mentions applied: 3"    'echo "$output" | grep -q "applied: 3"'

# ── Test 2: already-applied → all skip ──────────────────────────────
start_case "Test 2: re-run on populated tracker → all skip"
write_fixture "V20260201_01__test_d.sql" "CREATE TABLE test_runner_d (id INT);"
run_runner >/dev/null   # first apply
output=$(run_runner)
echo "$output" | sed 's/^/    /'
assert "tracker still has 1 row"     '[ "$(tracker_count)" = "1" ]'
assert "output says all up-to-date"  'echo "$output" | grep -q "all up-to-date"'

# ── Test 3: new file → only it applies ──────────────────────────────
start_case "Test 3: add new file after apply, only it applies"
write_fixture "V20260301_01__test_e.sql" "CREATE TABLE test_runner_e (id INT);"
run_runner >/dev/null                        # apply 1
write_fixture "V20260301_02__test_f.sql" "CREATE TABLE test_runner_f (id INT);"
output=$(run_runner)
echo "$output" | sed 's/^/    /'
assert "tracker has 2 rows"            '[ "$(tracker_count)" = "2" ]'
assert "second migration applied"      'tracker_has V20260301_02__test_f.sql'
assert "test_runner_f created"         'table_exists test_runner_f'
assert "output: applied 1, skipped 1"  'echo "$output" | grep -q "applied: 1, skipped: 1"'

# ── Test 4: edit applied file → checksum mismatch fail ──────────────
start_case "Test 4: editing applied file → checksum mismatch fail (exit 1)"
write_fixture "V20260401_01__test_g.sql" "CREATE TABLE test_runner_g (id INT);"
run_runner >/dev/null
write_fixture "V20260401_01__test_g.sql" "CREATE TABLE test_runner_g (id INT, extra TEXT);"
exit_code=0
output=$(run_runner) || exit_code=$?
echo "$output" | sed 's/^/    /'
assert "exit code is 1"                       '[ "$exit_code" = "1" ]'
assert "output mentions checksum mismatch"    'echo "$output" | grep -q "checksum mismatch"'
assert "tracker still 1 row"                  '[ "$(tracker_count)" = "1" ]'

# ── Test 5: broken SQL → tx rollback, tracker unchanged ─────────────
start_case "Test 5: broken SQL → tx rollback, tracker unchanged"
write_fixture "V20260501_01__test_h.sql" "CREATE TABLE test_runner_h (id INT);"
write_fixture "V20260501_02__test_broken.sql" "THIS IS NOT VALID SQL;"
exit_code=0
output=$(run_runner) || exit_code=$?
echo "$output" | sed 's/^/    /'
assert "exit code is 1"                       '[ "$exit_code" = "1" ]'
assert "test_runner_h created (1st applied)"  'table_exists test_runner_h'
assert "tracker has 1 row (broken not in)"    '[ "$(tracker_count)" = "1" ]'
assert "broken NOT in tracker"                '! tracker_has V20260501_02__test_broken.sql'

# ── Test 6: --dry-run → tracker untouched ───────────────────────────
start_case "Test 6: --dry-run detects new but does not write tracker"
write_fixture "V20260601_01__test_i.sql" "CREATE TABLE test_runner_i (id INT);"
output=$(run_runner --dry-run)
echo "$output" | sed 's/^/    /'
assert "tracker has 0 rows"               '[ "$(tracker_count)" = "0" ]'
assert "test_runner_i NOT created"        '! table_exists test_runner_i'
assert "output mentions DRY RUN"          'echo "$output" | grep -q "DRY RUN"'
assert "output mentions would apply"      'echo "$output" | grep -q "would apply"'

# Then real apply works after dry-run.
output2=$(run_runner)
assert "subsequent real apply puts 1 row"  '[ "$(tracker_count)" = "1" ]'
assert "test_runner_i actually created"    'table_exists test_runner_i'

# ── Test 7: --target stops at version ───────────────────────────────
start_case "Test 7: --target V20260701_02 applies only first 2"
write_fixture "V20260701_01__test_j.sql" "CREATE TABLE test_runner_j (id INT);"
write_fixture "V20260701_02__test_k.sql" "CREATE TABLE test_runner_k (id INT);"
write_fixture "V20260701_03__test_l.sql" "CREATE TABLE test_runner_l (id INT);"
output=$(run_runner --target V20260701_02)
echo "$output" | sed 's/^/    /'
assert "tracker has 2 rows"               '[ "$(tracker_count)" = "2" ]'
assert "test_runner_j created"            'table_exists test_runner_j'
assert "test_runner_k created"            'table_exists test_runner_k'
assert "test_runner_l NOT created"        '! table_exists test_runner_l'

# ── Test 8: filename PK keeps dupes distinct (PR-A spec deviation) ──
start_case "Test 8: two files with same version (filename PK) both record"
write_fixture "V20260801_01__alpha.sql" "CREATE TABLE test_runner_alpha (id INT);"
write_fixture "V20260801_01__bravo.sql" "CREATE TABLE test_runner_bravo (id INT);"
output=$(run_runner)
echo "$output" | sed 's/^/    /'
assert "tracker has 2 rows"                '[ "$(tracker_count)" = "2" ]'
assert "alpha recorded"                    'tracker_has V20260801_01__alpha.sql'
assert "bravo recorded"                    'tracker_has V20260801_01__bravo.sql'

# ── Test 9: target safety guards ────────────────────────────────────
start_case "Test 9: --target rejects nonexistent, ahead, and env=all"
write_fixture "V20260901_01__first.sql" "CREATE TABLE test_runner_target_first (id INT);"
write_fixture "V20260901_02__second.sql" "CREATE TABLE test_runner_target_second (id INT);"
run_runner --target V20260901_02 >/dev/null

ahead_exit=0
ahead_output=$(run_runner --target V20260901_01) || ahead_exit=$?
assert "lower target exits 1 when DB is ahead"       '[ "$ahead_exit" = "1" ]'
assert "lower target reports database ahead"         'echo "$ahead_output" | grep -q "database is already ahead"'
assert "ahead rejection preserves tracker rows"      '[ "$(tracker_count)" = "2" ]'

missing_exit=0
missing_output=$(run_runner --target V20260901_99) || missing_exit=$?
assert "nonexistent target exits 1"                   '[ "$missing_exit" = "1" ]'
assert "nonexistent target is explicit"               'echo "$missing_output" | grep -q "target does not exist"'

all_exit=0
all_output=$(run_runner --env all --target V20260901_02) || all_exit=$?
assert "target with env=all exits 2"                  '[ "$all_exit" = "2" ]'
assert "target with env=all is explicit"              'echo "$all_output" | grep -q "cannot be combined"'

# ── Summary ─────────────────────────────────────────────────────────
echo ""
echo "════════════════════════════════════════════════"
echo "  RESULTS: $PASS / $TOTAL passed"
[ "$FAIL" -gt 0 ] && echo "  $FAIL FAILED"
echo "════════════════════════════════════════════════"

[ "$FAIL" -eq 0 ] || exit 1
