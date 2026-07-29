#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
TESTS=

usage() {
    cat <<'EOF'
Usage: scripts/deploy/release-cretas-artifacts.sh --tests <MavenTestSelector>

Builds trusted Java and Web release artifacts concurrently from one clean,
reviewed worktree. Java still runs exactly one Maven clean-package lifecycle.
Run this before merge; after merge validate/reuse both manifests from a clean
exact origin/main release worktree.
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --tests) TESTS=${2:-}; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "ERROR: unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

[ -n "$TESTS" ] || { echo "ERROR: --tests requires a Maven test selector" >&2; exit 2; }
[ -z "$(git -C "$PROJECT_ROOT" status --porcelain --untracked-files=normal)" ] \
    || { echo "ERROR: artifact build requires a clean worktree" >&2; exit 1; }

"$SCRIPT_DIR/release-java-preflight.sh" --repo-root "$PROJECT_ROOT" --tests "$TESTS"

logs_dir=$(mktemp -d "${TMPDIR:-/tmp}/cretas-release-artifacts.XXXXXX")
cleanup() { rm -rf "$logs_dir"; }
trap cleanup EXIT
started=$(date +%s)

# Job control gives each child its own process group, so cancelling one reaches
# its Maven/Vite grandchildren instead of only the wrapper subshell. An orphaned
# build would keep writing target/ or dist/ and could race the operator's retry.
set -m
( child_started=$(date +%s)
  set +e
  "$SCRIPT_DIR/release-jar-manifest.sh" build --tests "$TESTS" >"$logs_dir/java.log" 2>&1
  child_rc=$?
  printf '%s\n' "$(( $(date +%s) - child_started ))" >"$logs_dir/java.seconds"
  printf '%s\n' "$child_rc" >"$logs_dir/java.rc.tmp" && mv -f "$logs_dir/java.rc.tmp" "$logs_dir/java.rc"
  exit "$child_rc"
) &
java_pid=$!
( child_started=$(date +%s)
  set +e
  "$SCRIPT_DIR/release-web-manifest.sh" build >"$logs_dir/web.log" 2>&1
  child_rc=$?
  printf '%s\n' "$(( $(date +%s) - child_started ))" >"$logs_dir/web.seconds"
  printf '%s\n' "$child_rc" >"$logs_dir/web.rc.tmp" && mv -f "$logs_dir/web.rc.tmp" "$logs_dir/web.rc"
  exit "$child_rc"
) &
web_pid=$!
set +m

# Once either side fails the release is already dead, so cancel the sibling
# instead of letting it run to completion. Previously the parent learned about a
# 1s Java failure immediately but still blocked on `wait` until the Web build
# finished, burning 60-110s per failed attempt.
cancel_sibling() {
    local pid=$1 failed_label=$2 cancelled_label=$3
    echo "INFO: $failed_label artifact build failed; cancelling the in-flight $cancelled_label build" >&2
    kill -- "-$pid" 2>/dev/null || kill "$pid" 2>/dev/null || true
}

set +e
java_rc=
web_rc=
cancelled=
while :; do
    [ -n "$java_rc" ] || { [ -f "$logs_dir/java.rc" ] && java_rc=$(cat "$logs_dir/java.rc"); }
    [ -n "$web_rc" ] || { [ -f "$logs_dir/web.rc" ] && web_rc=$(cat "$logs_dir/web.rc"); }

    if [ -z "$cancelled" ] && [ -n "$java_rc" ] && [ "$java_rc" -ne 0 ] && [ -z "$web_rc" ]; then
        cancel_sibling "$web_pid" Java Web
        cancelled=web
        web_rc=143
    fi
    if [ -z "$cancelled" ] && [ -n "$web_rc" ] && [ "$web_rc" -ne 0 ] && [ -z "$java_rc" ]; then
        cancel_sibling "$java_pid" Web Java
        cancelled=java
        java_rc=143
    fi

    [ -n "$java_rc" ] && [ -n "$web_rc" ] && break
    sleep 1
done
wait "$java_pid" 2>/dev/null
wait "$web_pid" 2>/dev/null
set -e

cat "$logs_dir/java.log"
cat "$logs_dir/web.log"
elapsed=$(( $(date +%s) - started ))
java_elapsed=$(cat "$logs_dir/java.seconds" 2>/dev/null || echo 0)
web_elapsed=$(cat "$logs_dir/web.seconds" 2>/dev/null || echo 0)
printf 'JAVA_BUILD_WALL_SECONDS=%s\n' "$java_elapsed"
printf 'WEB_BUILD_WALL_SECONDS=%s\n' "$web_elapsed"

if [ "$java_rc" -ne 0 ] || [ "$web_rc" -ne 0 ]; then
    echo "ERROR: parallel artifact build failed (java=$java_rc web=$web_rc elapsed=${elapsed}s${cancelled:+ cancelled=$cancelled})" >&2
    exit 1
fi
printf 'Trusted Java + Web artifacts built concurrently (elapsed=%ss)\n' "$elapsed"
