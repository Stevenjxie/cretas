#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
PROD_CONFIRM=
INDEPENDENT_CONFIRM=

usage() {
    cat <<'EOF'
Usage:
  scripts/deploy/deploy-cretas-parallel.sh \
    --confirm-prod YES-PROD \
    --confirm-independent-services YES-INDEPENDENT-SERVICES

Validates both trusted artifacts, then runs Web atomic deployment and Java
blue-green deployment concurrently. Use only when the two releases are known
to be API-compatible in either activation order. Each child keeps its own
integrity, health, rollback and stale-asset gates; this wrapper never bypasses
them.
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --confirm-prod) PROD_CONFIRM=${2:-}; shift 2 ;;
        --confirm-independent-services) INDEPENDENT_CONFIRM=${2:-}; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "ERROR: unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

[ "$PROD_CONFIRM" = "YES-PROD" ] || { echo "ERROR: production confirmation requires --confirm-prod YES-PROD" >&2; exit 2; }
[ "$INDEPENDENT_CONFIRM" = "YES-INDEPENDENT-SERVICES" ] \
    || { echo "ERROR: parallel release requires --confirm-independent-services YES-INDEPENDENT-SERVICES" >&2; exit 2; }
[ -z "$(git -C "$PROJECT_ROOT" status --porcelain --untracked-files=normal)" ] \
    || { echo "ERROR: parallel deploy requires a clean worktree" >&2; exit 1; }

git -C "$PROJECT_ROOT" fetch --quiet origin main
[ "$(git -C "$PROJECT_ROOT" rev-parse HEAD)" = "$(git -C "$PROJECT_ROOT" rev-parse origin/main)" ] \
    || { echo "ERROR: parallel deploy requires HEAD == origin/main" >&2; exit 1; }

"$SCRIPT_DIR/release-preflight.sh" --skip-fetch
"$SCRIPT_DIR/release-jar-manifest.sh" validate
"$SCRIPT_DIR/release-web-manifest.sh" validate

logs_dir=$(mktemp -d "${TMPDIR:-/tmp}/cretas-parallel-deploy.XXXXXX")
cleanup() { rm -rf "$logs_dir"; }
trap cleanup EXIT
started=$(date +%s)

( child_started=$(date +%s)
  set +e
  "$SCRIPT_DIR/deploy-backend.sh" --env prod >"$logs_dir/java.log" 2>&1
  child_rc=$?
  printf '%s\n' "$(( $(date +%s) - child_started ))" >"$logs_dir/java.seconds"
  exit "$child_rc"
) &
java_pid=$!
( child_started=$(date +%s)
  set +e
  "$SCRIPT_DIR/deploy-web-admin.sh" --env prod --confirm-prod YES-PROD >"$logs_dir/web.log" 2>&1
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
printf 'JAVA_DEPLOY_WALL_SECONDS=%s\n' "$java_elapsed"
printf 'WEB_DEPLOY_WALL_SECONDS=%s\n' "$web_elapsed"
printf 'JAVA_DEPLOY_RC=%s\n' "$java_rc"
printf 'WEB_DEPLOY_RC=%s\n' "$web_rc"
if [ "$java_rc" -ne 0 ] || [ "$web_rc" -ne 0 ]; then
    echo "ERROR: parallel production release failed (java=$java_rc web=$web_rc elapsed=${elapsed}s)" >&2
    echo "Each successful child remains independently deployed; inspect the printed child log before any follow-up action." >&2
    exit 1
fi
printf 'Parallel production release completed (java=0 web=0 elapsed=%ss)\n' "$elapsed"
