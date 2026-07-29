#!/usr/bin/env bash
# Contracts for scripts/deploy/deploy-mall-backend.sh Step 1 (Maven 打包):
#   1. PROJECT_ROOT resolves to the REPO ROOT, not to scripts/deploy — the Mall
#      project lives at <repo>/MallCenter/mall_admin_center, so a dirname-only
#      PROJECT_ROOT makes the very first `cd` fail and the script die at Step 1.
#   2. A failing Maven build MUST fail the script. Piping the build into `tail -5`
#      under `set -e` without `pipefail` takes the exit code from `tail` (always 0),
#      so the compile failure is swallowed entirely.
#   3. Because the build ran without `clean`, a JAR from the PREVIOUS build was
#      still sitting in target/, so the `[ -f "$JAR_PATH" ]` check passed anyway and
#      the script uploaded + restarted the service with STALE code, all green.
#      => a failed build must never reach the upload/restart steps.
#   4. The happy path must still get past Step 1 into the upload stage.
#
# Everything is stubbed: no Maven, no ssh/sftp/scp/rsync, no server is touched.
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
TARGET="$ROOT_DIR/scripts/deploy/deploy-mall-backend.sh"
COMMON="$ROOT_DIR/scripts/lib/deploy-common.sh"
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

JAR_REL="MallCenter/mall_admin_center/logistics-admin/target/logistics-admin.jar"

# The script under test invokes `./mvnw.cmd`. Under Git Bash that is handed to
# cmd.exe, which would read a bash stub as a batch file, so the stub logic lives in
# a .sh and mvnw.cmd becomes a thin batch shim that forwards args and exit code.
IS_WINDOWS=0
case "${OSTYPE:-}" in msys*|cygwin*|win32*) IS_WINDOWS=1 ;; esac

# ---------------------------------------------------------------- fixture setup
# A fake repo root laid out exactly like the real one, so the script's own
# path arithmetic (scripts/deploy -> ../.. -> repo root) is what gets exercised.
#   $1 fixture name
#   $2 mvnw stub body (bash)
#   $3 "stale" to pre-seed a JAR from a "previous build", anything else for none
#   $4 "break-md5" to make md5sum fail, exercising the non-Step-1 pipes
make_fixture() {
    local name=$1 mvnw_body=$2 seed_jar=$3 break_md5=${4:-}
    local repo="$TMP_ROOT/$name"
    local mall="$repo/MallCenter/mall_admin_center"

    mkdir -p "$repo/scripts/deploy" "$repo/scripts/lib" "$repo/stubs"
    mkdir -p "$mall/logistics-admin/target"
    cp "$TARGET" "$repo/scripts/deploy/deploy-mall-backend.sh"
    cp "$COMMON" "$repo/scripts/lib/deploy-common.sh"

    printf '%s\n' "$mvnw_body" > "$mall/mvnw-stub.sh"
    chmod +x "$mall/mvnw-stub.sh"
    if [ "$IS_WINDOWS" = 1 ]; then
        printf '@echo off\r\nbash "%%~dp0mvnw-stub.sh" %%*\r\nexit /b %%ERRORLEVEL%%\r\n' \
            > "$mall/mvnw.cmd"
    else
        cp "$mall/mvnw-stub.sh" "$mall/mvnw.cmd"
    fi
    chmod +x "$mall/mvnw.cmd" "$repo/scripts/deploy/"*.sh

    if [ "$seed_jar" = "stale" ]; then
        printf 'JAR FROM THE PREVIOUS BUILD\n' > "$repo/$JAR_REL"
    fi

    # Any transport the script may reach for records that a deploy was attempted
    # and then fails, so a bug that lets execution past Step 1 is caught by the
    # marker rather than by an actual connection to 139.196.165.140.
    local t
    for t in ssh sftp scp rsync; do
        cat > "$repo/stubs/$t" <<EOF
#!/usr/bin/env bash
echo "\$(basename "\$0") \$*" >> "$repo/deploy-attempted.marker"
exit 1
EOF
        chmod +x "$repo/stubs/$t"
    done

    if [ "$break_md5" = "break-md5" ]; then
        cat > "$repo/stubs/md5sum" <<'EOF'
#!/usr/bin/env bash
echo "md5sum: read error" >&2
exit 1
EOF
        chmod +x "$repo/stubs/md5sum"
    fi

    printf '%s\n' "$repo"
}

# Run the script from the REPO ROOT — the invocation that used to die at Step 1.
run_case() {
    local repo=$1 rc=0
    set +e
    (
        cd "$repo"
        PATH="$repo/stubs:$PATH" FIXTURE_REPO="$repo" \
            bash scripts/deploy/deploy-mall-backend.sh
    ) >"$repo/run.log" 2>&1
    rc=$?
    set -e
    printf '%s\n' "$rc"
}

# A stub that fails like a real compile error would, after echoing to stdout+stderr
# (the swallowed-by-tail bug only shows up when the build actually produces output).
MVNW_FAIL=$(cat <<'EOF'
#!/usr/bin/env bash
echo "[INFO] Scanning for projects..."
echo "[ERROR] COMPILATION ERROR : cannot find symbol" >&2
exit 1
EOF
)

# A stub that behaves like a successful build: records the cwd it was invoked from
# and the args it received, honours `clean` by wiping target/, then writes a JAR.
MVNW_OK=$(cat <<'EOF'
#!/usr/bin/env bash
pwd > "$FIXTURE_REPO/mvnw-cwd.txt"
printf '%s\n' "$@" > "$FIXTURE_REPO/mvnw-args.txt"
target="$FIXTURE_REPO/MallCenter/mall_admin_center/logistics-admin/target"
for a in "$@"; do
    [ "$a" = "clean" ] && rm -rf "$target"
done
mkdir -p "$target"
printf 'FRESHLY BUILT JAR\n' > "$target/logistics-admin.jar"
echo "[INFO] BUILD SUCCESS"
EOF
)

# ------------------------------------------- 1. PROJECT_ROOT resolves to repo root
# Ordered first on purpose: a broken PROJECT_ROOT kills the script before Maven is
# ever reached, and it should be reported as a path bug rather than surface later as
# a confusing "Maven's error was never surfaced" from the fail-fast cases.
# The build stub records its own cwd; the script cds there via PROJECT_ROOT, so the
# recorded path proves PROJECT_ROOT was the repo root and not scripts/deploy.
repo=$(make_fixture project-root "$MVNW_OK" none)
run_case "$repo" >/dev/null
[ -f "$repo/mvnw-cwd.txt" ] \
    || fail "Maven was never invoked — Step 1 died before the build: $(cat "$repo/run.log")"
actual_cwd=$(cat "$repo/mvnw-cwd.txt")
expected_cwd=$(cd "$repo/MallCenter/mall_admin_center" && pwd)
[ "$actual_cwd" = "$expected_cwd" ] \
    || fail "PROJECT_ROOT resolved wrong: built in '$actual_cwd', expected '$expected_cwd'"
case "$actual_cwd" in
    */scripts/deploy/*) fail "PROJECT_ROOT still resolves under scripts/deploy: $actual_cwd" ;;
esac
# `clean` guarantees a failed build cannot leave a previous JAR behind for the
# existence check to accept.
grep -Fqx 'clean' "$repo/mvnw-args.txt" \
    || fail "Maven is invoked without 'clean'; a failed build would leave the previous JAR in target/"

# ------------------------------------------- 2. Maven fails, no JAR in target/
repo=$(make_fixture mvn-fails-clean-target "$MVNW_FAIL" none)
rc=$(run_case "$repo")
[ "$rc" -ne 0 ] || fail "a failing Maven build exited 0: $(cat "$repo/run.log")"
[ ! -f "$repo/deploy-attempted.marker" ] \
    || fail "failed build still reached the upload/restart stage: $(cat "$repo/deploy-attempted.marker")"

# ------------------------ 3. CORE REGRESSION: Maven fails, STALE JAR in target/
# This is the shape that silently shipped old code: `package` without `clean` left
# the previous JAR behind, so the existence check passed despite the failed build.
repo=$(make_fixture mvn-fails-stale-jar "$MVNW_FAIL" stale)
rc=$(run_case "$repo")
[ "$rc" -ne 0 ] \
    || fail "Maven failed but a stale JAR made the script succeed — stale code would ship: $(cat "$repo/run.log")"
[ ! -f "$repo/deploy-attempted.marker" ] \
    || fail "stale JAR was uploaded after a failed build: $(cat "$repo/deploy-attempted.marker")"
if grep -Fq 'Step 2/4' "$repo/run.log"; then
    fail "failed build proceeded into Step 2 upload: $(cat "$repo/run.log")"
fi
# The stale JAR must still be sitting there — proving the script stopped because it
# detected the build failure, not merely because the JAR happened to be missing.
[ -f "$repo/$JAR_REL" ] \
    || fail "test is not exercising the stale-JAR path: the seeded JAR is gone"
# The operator must be told the build failed, not left guessing at 5 lines of tail.
grep -Fq 'COMPILATION ERROR' "$repo/run.log" \
    || fail "Maven's actual error was never surfaced to the operator: $(cat "$repo/run.log")"

# ------------------------------------ 4. happy path still gets past Step 1
# Uploads are stubbed to fail, so the run ends at "所有上传方式均失败" — but only
# after Step 1 succeeded and Step 2 was entered, which is what this asserts.
repo=$(make_fixture happy-path "$MVNW_OK" none)
run_case "$repo" >/dev/null
grep -Fq 'Step 2/4' "$repo/run.log" \
    || fail "successful build did not reach Step 2: $(cat "$repo/run.log")"
if grep -Fq 'JAR 未生成' "$repo/run.log"; then
    fail "successful build reported a missing JAR: $(cat "$repo/run.log")"
fi
if grep -Fq 'Maven 打包失败' "$repo/run.log"; then
    fail "successful build was reported as a Maven failure: $(cat "$repo/run.log")"
fi
[ -f "$repo/deploy-attempted.marker" ] \
    || fail "successful build never attempted an upload: $(cat "$repo/run.log")"

# ------------------------------- 5. pipefail still guards the non-Step-1 pipelines
# `LOCAL_MD5=$(md5sum "$JAR_PATH" | cut -d' ' -f1)` has the same shape as the bug:
# without pipefail the exit code comes from `cut`, so a failed md5sum yields an EMPTY
# LOCAL_MD5 and the script marches into Step 2, burning upload attempts against a
# checksum that can never match. It must abort before Step 2 instead.
repo=$(make_fixture md5-fails "$MVNW_OK" none break-md5)
rc=$(run_case "$repo")
[ "$rc" -ne 0 ] || fail "md5sum failure exited 0: $(cat "$repo/run.log")"
if grep -Fq 'Step 2/4' "$repo/run.log"; then
    fail "md5sum failed but the script still entered Step 2 with an empty checksum: $(cat "$repo/run.log")"
fi
[ ! -f "$repo/deploy-attempted.marker" ] \
    || fail "md5sum failed yet uploads were attempted: $(cat "$repo/deploy-attempted.marker")"

echo "PASS: deploy-mall-backend Step 1 repo-root resolution and Maven fail-fast"
