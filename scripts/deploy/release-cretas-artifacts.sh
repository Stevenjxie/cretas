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

( child_started=$(date +%s)
  set +e
  "$SCRIPT_DIR/release-jar-manifest.sh" build --tests "$TESTS" >"$logs_dir/java.log" 2>&1
  child_rc=$?
  printf '%s\n' "$(( $(date +%s) - child_started ))" >"$logs_dir/java.seconds"
  exit "$child_rc"
) &
java_pid=$!
( child_started=$(date +%s)
  set +e
  "$SCRIPT_DIR/release-web-manifest.sh" build >"$logs_dir/web.log" 2>&1
  child_rc=$?
  printf '%s\n' "$(( $(date +%s) - child_started ))" >"$logs_dir/web.seconds"
  exit "$child_rc"
) &
web_pid=$!

set +e
wait "$java_pid"; java_rc=$?
wait "$web_pid"; web_rc=$?
set -e

cat "$logs_dir/java.log"
cat "$logs_dir/web.log"
elapsed=$(( $(date +%s) - started ))
java_elapsed=$(cat "$logs_dir/java.seconds" 2>/dev/null || echo 0)
web_elapsed=$(cat "$logs_dir/web.seconds" 2>/dev/null || echo 0)
printf 'JAVA_BUILD_WALL_SECONDS=%s\n' "$java_elapsed"
printf 'WEB_BUILD_WALL_SECONDS=%s\n' "$web_elapsed"

if [ "$java_rc" -ne 0 ] || [ "$web_rc" -ne 0 ]; then
    echo "ERROR: parallel artifact build failed (java=$java_rc web=$web_rc elapsed=${elapsed}s)" >&2
    exit 1
fi
printf 'Trusted Java + Web artifacts built concurrently (elapsed=%ss)\n' "$elapsed"
