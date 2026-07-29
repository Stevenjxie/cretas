#!/usr/bin/env bash
# Contracts for the parallel PRODUCTION deploy wrapper:
#   1. When one side fails while the sibling is still in a reversible phase
#      (build/upload), the sibling is cancelled immediately instead of being
#      allowed to finish a full production rollout — that is what produced the
#      "old Java / new Web" half-release.
#   2. When the sibling has already entered its irreversible phase (Java
#      blue-green switch, Web atomic directory swap), it is deliberately NOT
#      killed — cancelling there leaves a worse intermediate state — but the
#      operator is alerted within seconds instead of the parent waiting silently.
#   3. Both-success behaviour and the failure receipt/exit-code semantics are
#      unchanged.
#
# Everything runs against stub child scripts; no build, no ssh, no production.
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
PARALLEL_DEPLOY="$ROOT_DIR/scripts/deploy/deploy-cretas-parallel.sh"
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

# The failing side waits this long so both children have certainly emitted their
# opening log lines; otherwise the phase probe could race the child's first echo.
FAIL_DELAY=2
# The surviving stub runs far longer than the reaction budget, so "reacted fast"
# and "waited for the sibling" are unambiguously distinguishable.
SLOW_SECONDS=15
REACTION_BUDGET=9

# Phase banners the stubs replay, copied verbatim from the real child scripts
# (deploy-backend.sh line ~1622, deploy-web-admin.sh line ~507).
JAVA_PONR_LINE='🔄 [3b] Blue-Green 切换...'
WEB_PONR_LINE='🚀 [4/4] 原子交换 + 清理旧 backups...'
# The emoji is deliberately excluded from every grep: Git Bash's grep cannot
# match 4-byte UTF-8 code points, which is also why the wrapper's own phase
# probe anchors on the ASCII/CJK part only.
JAVA_PONR_ANCHOR='[3b] Blue-Green 切换'
WEB_PONR_ANCHOR='[4/4] 原子交换'

grep -Fq "$JAVA_PONR_ANCHOR" "$ROOT_DIR/scripts/deploy/deploy-backend.sh" \
    || fail "deploy-backend.sh no longer prints the blue-green phase banner the wrapper keys on"
grep -Fq "$WEB_PONR_ANCHOR" "$ROOT_DIR/scripts/deploy/deploy-web-admin.sh" \
    || fail "deploy-web-admin.sh no longer prints the atomic-swap phase banner the wrapper keys on"

# ---------------------------------------------------------------- fixture setup
# The wrapper demands a clean worktree whose HEAD equals origin/main, so each
# case gets a throwaway repo with a real local origin.
make_fixture() {
    local name=$1 java_body=$2 web_body=$3
    local repo="$TMP_ROOT/$name" origin="$TMP_ROOT/$name-origin.git"

    mkdir -p "$repo/scripts/deploy"
    cp "$PARALLEL_DEPLOY" "$repo/scripts/deploy/deploy-cretas-parallel.sh"
    for helper in release-preflight.sh release-jar-manifest.sh release-web-manifest.sh; do
        printf '#!/usr/bin/env bash\nexit 0\n' > "$repo/scripts/deploy/$helper"
    done
    printf '%s\n' "$java_body" > "$repo/scripts/deploy/deploy-backend.sh"
    printf '%s\n' "$web_body" > "$repo/scripts/deploy/deploy-web-admin.sh"
    chmod +x "$repo/scripts/deploy/"*.sh
    (
        cd "$repo"
        git init -q -b main
        git config user.name test
        git config user.email test@example.com
        git add -A
        git commit -qm fixture
        git clone -q --bare . "$origin"
        git remote add origin "$origin"
        git fetch -q origin main
    )
    printf '%s\n' "$repo"
}

run_log_for() {
    # Must live outside the repo: an untracked file inside it would trip the
    # wrapper's own clean-worktree guard before any of this is exercised.
    printf '%s/%s.run.log\n' "$TMP_ROOT" "$(basename "$1")"
}

start_case() {
    local repo=$1
    (
        cd "$repo" && bash scripts/deploy/deploy-cretas-parallel.sh \
            --confirm-prod YES-PROD \
            --confirm-independent-services YES-INDEPENDENT-SERVICES
    ) >"$(run_log_for "$repo")" 2>&1 &
    # Assigned in the caller's shell (never via command substitution) so the job
    # stays a child of the test shell and `wait` can actually reap it.
    CASE_PID=$!
}

# Seconds until PATTERN shows up in the run log, or -1 if it never did.
seconds_until() {
    local log=$1 pattern=$2 limit=$3 waited=0
    while [ "$waited" -le "$limit" ]; do
        if grep -Fq "$pattern" "$log" 2>/dev/null; then
            printf '%s\n' "$waited"
            return 0
        fi
        sleep 1
        waited=$((waited + 1))
    done
    printf '%s\n' -1
}

# ------------------------------------------------------------------ stub bodies
fast_fail_stub() {
    cat <<EOF
#!/usr/bin/env bash
echo "stub $1 deploy starting"
sleep $FAIL_DELAY
echo "stub $1 deploy failed" >&2
exit 1
EOF
}

# Still in a reversible phase (upload) — cancellation here is safe and expected.
reversible_stub() {
    cat <<EOF
#!/usr/bin/env bash
echo "📤 [3/4] stub $1 upload in progress"
sleep $SLOW_SECONDS
touch "\$(dirname "\$0")/../../$1-finished.marker"
EOF
}

# Past the point of no return — the wrapper must leave this one alone.
irreversible_stub() {
    cat <<EOF
#!/usr/bin/env bash
echo "$2"
sleep $SLOW_SECONDS
touch "\$(dirname "\$0")/../../$1-finished.marker"
EOF
}

ok_stub() {
    printf '#!/usr/bin/env bash\necho "stub ok $*"\n'
}

# ============================================== 1. Java fails, Web still safe
repo=$(make_fixture java-fails-web-reversible "$(fast_fail_stub java)" "$(reversible_stub web)")
log=$(run_log_for "$repo")
started=$(date +%s)
start_case "$repo"
cancel_at=$(seconds_until "$log" 'cancelling the still-reversible Web deployment' "$REACTION_BUDGET")
set +e
wait "$CASE_PID"; rc=$?
set -e
elapsed=$(( $(date +%s) - started ))

[ "$cancel_at" -ge 0 ] || fail "Java failure never cancelled the reversible Web deploy within ${REACTION_BUDGET}s: $(cat "$log")"
[ "$elapsed" -lt "$SLOW_SECONDS" ] \
    || fail "Java failed at ${FAIL_DELAY}s but the wrapper still ran ${elapsed}s waiting for Web"
[ ! -f "$repo/web-finished.marker" ] || fail "cancelled Web deployment completed anyway"
[ "$rc" -ne 0 ] || fail "Java failure did not fail the parallel deploy"
grep -Fq 'JAVA_DEPLOY_RC=1' "$log" || fail "Java child rc receipt changed: $(cat "$log")"
grep -Fq 'WEB_DEPLOY_RC=143' "$log" || fail "cancelled Web child was not reported as terminated: $(cat "$log")"
grep -Fq 'cancelled=web' "$log" || fail "failure summary did not record the cancellation"
grep -Fq 'never reached production' "$log" || fail "operator was not told the cancelled side never shipped"
echo "  case 1 (Java fails, Web reversible): cancelled at ${cancel_at}s, total ${elapsed}s"

# ============================================== 2. Web fails, Java still safe
repo=$(make_fixture web-fails-java-reversible "$(reversible_stub java)" "$(fast_fail_stub web)")
log=$(run_log_for "$repo")
started=$(date +%s)
start_case "$repo"
cancel_at=$(seconds_until "$log" 'cancelling the still-reversible Java deployment' "$REACTION_BUDGET")
set +e
wait "$CASE_PID"; rc=$?
set -e
elapsed=$(( $(date +%s) - started ))

[ "$cancel_at" -ge 0 ] || fail "Web failure never cancelled the reversible Java deploy within ${REACTION_BUDGET}s: $(cat "$log")"
[ "$elapsed" -lt "$SLOW_SECONDS" ] \
    || fail "Web failed at ${FAIL_DELAY}s but the wrapper still ran ${elapsed}s waiting for Java"
[ ! -f "$repo/java-finished.marker" ] || fail "cancelled Java deployment completed anyway"
[ "$rc" -ne 0 ] || fail "Web failure did not fail the parallel deploy"
grep -Fq 'JAVA_DEPLOY_RC=143' "$log" || fail "cancelled Java child was not reported as terminated: $(cat "$log")"
echo "  case 2 (Web fails, Java reversible): cancelled at ${cancel_at}s, total ${elapsed}s"

# ======================= 3. Java fails, Web already inside the atomic swap
repo=$(make_fixture java-fails-web-swapping "$(fast_fail_stub java)" "$(irreversible_stub web "$WEB_PONR_LINE")")
log=$(run_log_for "$repo")
started=$(date +%s)
start_case "$repo"
alert_at=$(seconds_until "$log" '半上线风险' "$REACTION_BUDGET")
set +e
wait "$CASE_PID"; rc=$?
set -e
elapsed=$(( $(date +%s) - started ))

[ "$alert_at" -ge 0 ] \
    || fail "uncancellable Web deploy produced no alert within ${REACTION_BUDGET}s: $(cat "$log")"
[ -f "$repo/web-finished.marker" ] \
    || fail "Web was killed mid atomic swap — exactly the intermediate state this must avoid"
[ "$elapsed" -ge "$SLOW_SECONDS" ] || fail "Web finished implausibly fast (${elapsed}s); it should have run to completion"
[ "$rc" -ne 0 ] || fail "Java failure did not fail the parallel deploy"
grep -Fq 'WEB_DEPLOY_RC=0' "$log" || fail "uncancelled Web child rc was not reported: $(cat "$log")"
grep -Fq 'uncancellable=web' "$log" || fail "failure summary did not flag the uncancellable side"
grep -Fq 'mixed-version' "$log" || fail "operator was not warned production is now mixed-version"
grep -Fq 'web-admin-backups' "$log" || fail "alert did not name the Web rollback path"
if grep -Fq 'cancelling the still-reversible Web deployment' "$log"; then
    fail "wrapper cancelled a Web deploy that was past its point of no return"
fi
echo "  case 3 (Java fails, Web past PONR): alerted at ${alert_at}s, waited ${elapsed}s (no kill)"

# ======================= 4. Web fails, Java already inside the blue-green switch
repo=$(make_fixture web-fails-java-switching "$(irreversible_stub java "$JAVA_PONR_LINE")" "$(fast_fail_stub web)")
log=$(run_log_for "$repo")
started=$(date +%s)
start_case "$repo"
alert_at=$(seconds_until "$log" '半上线风险' "$REACTION_BUDGET")
set +e
wait "$CASE_PID"; rc=$?
set -e
elapsed=$(( $(date +%s) - started ))

[ "$alert_at" -ge 0 ] \
    || fail "uncancellable Java deploy produced no alert within ${REACTION_BUDGET}s: $(cat "$log")"
[ -f "$repo/java-finished.marker" ] \
    || fail "Java was killed mid blue-green switch — that strands two production JVMs"
[ "$elapsed" -ge "$SLOW_SECONDS" ] || fail "Java finished implausibly fast (${elapsed}s); it should have run to completion"
[ "$rc" -ne 0 ] || fail "Web failure did not fail the parallel deploy"
grep -Fq 'uncancellable=java' "$log" || fail "failure summary did not flag the uncancellable Java side"
grep -Fq -- '--rollback' "$log" || fail "alert did not name the Java rollback command"
echo "  case 4 (Web fails, Java past PONR): alerted at ${alert_at}s, waited ${elapsed}s (no kill)"

# ================================================ 5. Both succeed, unchanged
repo=$(make_fixture both-ok "$(ok_stub)" "$(ok_stub)")
log=$(run_log_for "$repo")
started=$(date +%s)
start_case "$repo"
set +e
wait "$CASE_PID"; rc=$?
set -e
elapsed=$(( $(date +%s) - started ))

[ "$rc" -eq 0 ] || fail "both-success run failed: $(cat "$log")"
grep -Fq 'Parallel production release completed' "$log" || fail "success receipt missing"
grep -Fq 'JAVA_DEPLOY_WALL_SECONDS=' "$log" || fail "successful run stopped reporting Java wall time"
grep -Fq 'WEB_DEPLOY_WALL_SECONDS=' "$log" || fail "successful run stopped reporting Web wall time"
grep -Fq 'JAVA_DEPLOY_RC=0' "$log" || fail "successful run stopped reporting Java rc"
grep -Fq 'WEB_DEPLOY_RC=0' "$log" || fail "successful run stopped reporting Web rc"
if grep -Eq 'cancelling|半上线风险' "$log"; then
    fail "successful run reported a cancellation or half-release alert"
fi
echo "  case 5 (both succeed): rc=0 in ${elapsed}s, no false alarm"

echo "PASS: parallel production deploy cancels only reversible siblings and alerts on the rest"
