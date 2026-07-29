#!/usr/bin/env bash
# Contracts for the parallel release artifact build:
#   1. A missing/unusable JDK is rejected before either build starts, instead of
#      being discovered ~1s in while the Web build burns another 60-110s.
#   2. When one side fails, the in-flight sibling is cancelled immediately rather
#      than run to completion for an already-doomed release.
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
ARTIFACTS="$ROOT_DIR/scripts/deploy/release-cretas-artifacts.sh"
JAVA_PREFLIGHT="$ROOT_DIR/scripts/deploy/release-java-preflight.sh"
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

TARGET_TESTS='ReleaseDecisionToolTest'

# ---------------------------------------------------------------- JDK preflight
# A bogus JAVA_HOME must be rejected by the read-only preflight, not by Maven
# after the Web build has already been started.
if JAVA_HOME="$TMP_ROOT/no-such-jdk" bash "$JAVA_PREFLIGHT" \
    --repo-root "$ROOT_DIR" --tests "$TARGET_TESTS" >"$TMP_ROOT/jdk.log" 2>&1; then
    fail "preflight accepted a JAVA_HOME that has no runnable java"
fi
grep -Fq 'JAVA_HOME' "$TMP_ROOT/jdk.log" \
    || fail "bogus JAVA_HOME was rejected without naming JAVA_HOME: $(cat "$TMP_ROOT/jdk.log")"

# The real environment must still pass, otherwise the guard is useless noise.
bash "$JAVA_PREFLIGHT" --repo-root "$ROOT_DIR" --tests "$TARGET_TESTS" >/dev/null 2>&1 \
    || fail "preflight rejected the current, working JDK"

# ------------------------------------------------------------ locale hardening
# The import resolver classifies a package segment as a class name by testing
# for a leading capital. A `[A-Z]` RANGE is collation-dependent: under
# en_US.UTF-8 it also matches lowercase, so `com` in `com.cretas.aims.Foo` is
# read as the class name and every such import is falsely reported unresolvable.
# That turned any release whose selector imports project classes into a preflight
# rejection unless the operator remembered LC_ALL=C.
locale_probe="$ROOT_DIR/backend/java/cretas-api/src/test/java"
# `grep | head` would kill grep with SIGPIPE and trip pipefail; collect then slice.
locale_test_file=$(grep -rl '^import com\.cretas\.aims\.' "$locale_probe" --include=*.java 2>/dev/null || true)
locale_test_file=${locale_test_file%%$'\n'*}
[ -n "$locale_test_file" ] \
    || fail "no test class with project imports available to exercise the locale path"
locale_test_class=$(basename "$locale_test_file" .java)

for probe_locale in en_US.UTF-8 C; do
    if ! LC_ALL="$probe_locale" LANG="$probe_locale" bash "$JAVA_PREFLIGHT" \
        --repo-root "$ROOT_DIR" --tests "$locale_test_class" >"$TMP_ROOT/locale.log" 2>&1; then
        fail "preflight failed under $probe_locale: $(head -2 "$TMP_ROOT/locale.log")"
    fi
    if grep -Fq 'cannot be resolved' "$TMP_ROOT/locale.log"; then
        fail "preflight falsely reported an unresolvable import under $probe_locale: $(grep -F 'cannot be resolved' "$TMP_ROOT/locale.log" | head -1)"
    fi
done

# ---------------------------------------------------------------- fixture setup
# Each case gets a fresh clean git repo with stubbed child builders, so the
# orchestration contract is tested without invoking Maven or Vite.
make_fixture() {
    local name=$1 java_body=$2 web_body=$3
    local repo="$TMP_ROOT/$name"

    mkdir -p "$repo/scripts/deploy"
    cp "$ARTIFACTS" "$repo/scripts/deploy/release-cretas-artifacts.sh"
    cat > "$repo/scripts/deploy/release-java-preflight.sh" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
    printf '%s\n' "$java_body" > "$repo/scripts/deploy/release-jar-manifest.sh"
    printf '%s\n' "$web_body" > "$repo/scripts/deploy/release-web-manifest.sh"
    chmod +x "$repo/scripts/deploy/"*.sh
    (
        cd "$repo"
        git init -q -b main
        git config user.name test
        git config user.email test@example.com
        git add -A
        git commit -qm fixture
    )
    printf '%s\n' "$repo"
}

# The log must live outside the repo: an untracked file inside it would trip the
# builder's own clean-worktree guard before any of this is exercised.
run_log_for() {
    printf '%s/%s.run.log\n' "$TMP_ROOT" "$(basename "$1")"
}

run_case() {
    local repo=$1
    local started elapsed rc
    started=$(date +%s)
    set +e
    (cd "$repo" && bash scripts/deploy/release-cretas-artifacts.sh --tests "$TARGET_TESTS") \
        >"$(run_log_for "$repo")" 2>&1
    rc=$?
    set -e
    elapsed=$(( $(date +%s) - started ))
    printf '%s %s\n' "$rc" "$elapsed"
}

SLOW_SECONDS=12
CANCEL_BUDGET=6

# ------------------------------------------------- java fails, web must cancel
java_fast_fail=$(cat <<'EOF'
#!/usr/bin/env bash
echo "stub java build failed" >&2
exit 1
EOF
)
web_slow=$(cat <<EOF
#!/usr/bin/env bash
sleep $SLOW_SECONDS
touch "\$(dirname "\$0")/../../web-finished.marker"
EOF
)
repo=$(make_fixture java-fails "$java_fast_fail" "$web_slow")
read -r rc elapsed <<<"$(run_case "$repo")"
[ "$rc" -ne 0 ] || fail "java failure did not fail the artifact build"
[ "$elapsed" -lt "$CANCEL_BUDGET" ] \
    || fail "java failed instantly but the run still took ${elapsed}s waiting for Web"
[ ! -f "$repo/web-finished.marker" ] \
    || fail "cancelled Web build ran to completion anyway"
grep -Fq 'cancelling' "$(run_log_for "$repo")" \
    || fail "cancellation was not reported to the operator: $(cat "$(run_log_for "$repo")")"

# ------------------------------------------------- web fails, java must cancel
java_slow=$(cat <<EOF
#!/usr/bin/env bash
sleep $SLOW_SECONDS
touch "\$(dirname "\$0")/../../java-finished.marker"
EOF
)
web_fast_fail=$(cat <<'EOF'
#!/usr/bin/env bash
echo "stub web build failed" >&2
exit 1
EOF
)
repo=$(make_fixture web-fails "$java_slow" "$web_fast_fail")
read -r rc elapsed <<<"$(run_case "$repo")"
[ "$rc" -ne 0 ] || fail "web failure did not fail the artifact build"
[ "$elapsed" -lt "$CANCEL_BUDGET" ] \
    || fail "web failed instantly but the run still took ${elapsed}s waiting for Java"
[ ! -f "$repo/java-finished.marker" ] \
    || fail "cancelled Java build ran to completion anyway"

# ------------------------------------------------------ both succeed, still ok
ok_stub=$(cat <<'EOF'
#!/usr/bin/env bash
echo "stub ok $*"
EOF
)
repo=$(make_fixture both-ok "$ok_stub" "$ok_stub")
read -r rc elapsed <<<"$(run_case "$repo")"
[ "$rc" -eq 0 ] || fail "both-success run failed: $(cat "$(run_log_for "$repo")")"
grep -Fq 'JAVA_BUILD_WALL_SECONDS=' "$(run_log_for "$repo")" \
    || fail "successful run stopped reporting Java build wall time"
grep -Fq 'WEB_BUILD_WALL_SECONDS=' "$(run_log_for "$repo")" \
    || fail "successful run stopped reporting Web build wall time"
if grep -Fq 'cancelling' "$(run_log_for "$repo")"; then
    fail "successful run reported a cancellation"
fi

echo "PASS: release artifact JDK preflight and cross-cancelling parallel build"
