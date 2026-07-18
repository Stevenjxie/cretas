#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
source "$SCRIPT_DIR/release-jar-manifest.sh"

SERVER=${CRETAS_BACKEND_SERVER:-root@47.100.235.168}
REMOTE_CACHE_DIR=${CRETAS_REMOTE_JAR_CACHE_DIR:-/www/wwwroot/cretas/release-cache/sha256}
MANIFEST=$(release_manifest_default_path)
CONFIRM=

usage() {
    cat <<'EOF'
Usage: scripts/deploy/stage-backend-artifact.sh --confirm-stage YES-STAGE [--manifest PATH]

Validates a trusted JAR against the clean reviewed build commit, then uploads
it to an immutable server-side SHA-256 cache. This command never installs the
JAR, restarts a service, or changes nginx upstream state.
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --confirm-stage) CONFIRM=${2:-}; shift 2 ;;
        --manifest) MANIFEST=${2:-}; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "ERROR: unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

[ "$CONFIRM" = "YES-STAGE" ] \
    || { echo "ERROR: staging requires --confirm-stage YES-STAGE" >&2; exit 2; }
release_manifest_require_clean_worktree "$PROJECT_ROOT" \
    || { echo "ERROR: staging requires a clean reviewed worktree" >&2; exit 1; }
[ -f "$MANIFEST" ] || { echo "ERROR: release manifest not found: $MANIFEST" >&2; exit 1; }

build_commit=$(release_manifest_field "$MANIFEST" build_commit)
backend_tree=$(release_manifest_field "$MANIFEST" backend_tree)
jar_sha=$(release_manifest_field "$MANIFEST" jar_sha256 | tr '[:upper:]' '[:lower:]')
jar_relative=$(release_manifest_field "$MANIFEST" jar_path)
head_commit=$(git -C "$PROJECT_ROOT" rev-parse HEAD)
head_tree=$(git -C "$PROJECT_ROOT" rev-parse "HEAD:$RELEASE_BACKEND_PATH")

[ "$build_commit" = "$head_commit" ] \
    || { echo "ERROR: manifest build commit is not the current reviewed HEAD" >&2; exit 1; }
[ "$backend_tree" = "$head_tree" ] \
    || { echo "ERROR: manifest backend tree is not the current reviewed backend tree" >&2; exit 1; }
[[ "$jar_sha" =~ ^[0-9a-f]{64}$ ]] || { echo "ERROR: invalid manifest JAR SHA-256" >&2; exit 1; }
[ "$jar_relative" = "$RELEASE_JAR_NAME" ] || { echo "ERROR: unexpected manifest JAR name" >&2; exit 1; }

manifest_dir=$(cd "$(dirname "$MANIFEST")" && pwd)
jar_path="$manifest_dir/$jar_relative"
release_manifest_verify_jar "$jar_path" || { echo "ERROR: JAR integrity check failed" >&2; exit 1; }
actual_sha=$(sha256sum "$jar_path" | awk '{print tolower($1)}')
[ "$actual_sha" = "$jar_sha" ] || { echo "ERROR: JAR SHA-256 does not match manifest" >&2; exit 1; }

remote_path="$REMOTE_CACHE_DIR/$jar_sha.jar"
started_at=$(date +%s)
if ssh -o ConnectTimeout=10 "$SERVER" \
    "[ -f '$remote_path' ] && [ \"\$(sha256sum '$remote_path' | awk '{print \$1}')\" = '$jar_sha' ]"; then
    echo "Backend artifact already staged: SHA-256=$jar_sha elapsed=$(( $(date +%s) - started_at ))s"
    exit 0
fi

remote_tmp="$REMOTE_CACHE_DIR/.${jar_sha}.$$"
ssh -o ConnectTimeout=10 "$SERVER" "mkdir -p '$REMOTE_CACHE_DIR' && chmod 700 '$REMOTE_CACHE_DIR'"
rsync -a --timeout=90 "$jar_path" "$SERVER:$remote_tmp"
ssh -o ConnectTimeout=10 "$SERVER" "
    set -eu
    actual=\$(sha256sum '$remote_tmp' | awk '{print \$1}')
    [ \"\$actual\" = '$jar_sha' ]
    unzip -tqq '$remote_tmp'
    chmod 0444 '$remote_tmp'
    mv -f '$remote_tmp' '$remote_path'
"

echo "Backend artifact staged without deployment: SHA-256=$jar_sha elapsed=$(( $(date +%s) - started_at ))s path=$remote_path"
