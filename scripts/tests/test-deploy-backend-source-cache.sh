#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
DEPLOY_SCRIPT="$ROOT_DIR/scripts/deploy/deploy-backend.sh"
MANIFEST_HELPER="$ROOT_DIR/scripts/deploy/release-jar-manifest.sh"
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

source "$MANIFEST_HELPER"

HELPERS=$(awk '
    /^# BEGIN_BACKEND_SOURCE_CACHE_HELPERS$/ {copy = 1; next}
    /^# END_BACKEND_SOURCE_CACHE_HELPERS$/ {exit}
    copy {print}
' "$DEPLOY_SCRIPT")
eval "$HELPERS"

FIXTURE_REPO="$TMP_ROOT/repo"
SOURCE_JAR="$TMP_ROOT/source.jar"
JAR_NAME="cretas-backend-system-1.0.0.jar"
DEST_JAR="$TMP_ROOT/destination/$JAR_NAME"
PROJECT_ROOT="$FIXTURE_REPO"
LOCAL_JAR_CACHE_ROOT="$TMP_ROOT/cache"
mkdir -p "$FIXTURE_REPO/backend/java/cretas-api"
(
    cd "$FIXTURE_REPO"
    git init -q -b main
    git config user.name test
    git config user.email test@example.com
    printf '<project/>\n' > backend/java/cretas-api/pom.xml
    git add backend/java/cretas-api/pom.xml
    git commit -qm backend
    git update-ref refs/remotes/origin/main HEAD
)
printf 'verified jar bytes\n' > "$SOURCE_JAR"
JAR_CONTENT="$TMP_ROOT/jar-content"
mkdir -p "$JAR_CONTENT"
printf 'verified jar bytes\n' > "$JAR_CONTENT/payload.txt"
rm -f "$SOURCE_JAR"
(cd "$JAR_CONTENT" && jar --create --file "$SOURCE_JAR" payload.txt)

# Store a verified main artifact, advance main with docs only, and prove the
# unchanged backend tree reuses the same bytes across commits.
LOCAL_MAVEN_BUILD_COMPLETED=true store_local_source_artifact_cache "$SOURCE_JAR"
(
    cd "$FIXTURE_REPO"
    mkdir -p docs
    printf 'docs only\n' > docs/note.md
    git add docs/note.md
    git commit -qm docs
    git update-ref refs/remotes/origin/main HEAD
)
reuse_local_source_artifact_cache "$DEST_JAR"
cmp "$SOURCE_JAR" "$DEST_JAR" || fail "docs-only commit did not reuse identical JAR"

# Backend content changes must produce a different tree and fail closed.
(
    cd "$FIXTURE_REPO"
    printf '<project>changed</project>\n' > backend/java/cretas-api/pom.xml
    git add backend/java/cretas-api/pom.xml
    git commit -qm backend-change
    git update-ref refs/remotes/origin/main HEAD
)
if reuse_local_source_artifact_cache "$DEST_JAR"; then
    fail "changed backend tree reused stale JAR"
fi

# Restore the cached tree, then corrupt its checksum and prove rejection.
git -C "$FIXTURE_REPO" reset -q --hard HEAD~1
git -C "$FIXTURE_REPO" update-ref refs/remotes/origin/main HEAD
sed -i 's/^jar_sha256=.*/jar_sha256=0000000000000000000000000000000000000000000000000000000000000000/' \
    "$LOCAL_JAR_CACHE_ROOT/current/$RELEASE_MANIFEST_NAME"
if reuse_local_source_artifact_cache "$DEST_JAR"; then
    fail "corrupt cache checksum was accepted"
fi

# Explicit cache disable must fail closed.
LOCAL_MAVEN_BUILD_COMPLETED=true store_local_source_artifact_cache "$SOURCE_JAR"
if DISABLE_LOCAL_JAR_CACHE=1 reuse_local_source_artifact_cache "$DEST_JAR"; then
    fail "DISABLE_LOCAL_JAR_CACHE=1 did not disable reuse"
fi

# Dirty includes untracked files. A clean index/diff is not enough.
printf 'untracked\n' > "$FIXTURE_REPO/untracked.txt"
if reuse_local_source_artifact_cache "$DEST_JAR"; then
    fail "untracked release worktree was accepted"
fi
rm "$FIXTURE_REPO/untracked.txt"

# Recompute the checksum over deliberately non-ZIP bytes. SHA matching alone
# must not make a damaged JAR trustworthy.
printf 'not a jar\n' > "$LOCAL_JAR_CACHE_ROOT/current/$JAR_NAME"
DAMAGED_SHA=$(sha256sum "$LOCAL_JAR_CACHE_ROOT/current/$JAR_NAME" | awk '{print $1}')
sed -i "s/^jar_sha256=.*/jar_sha256=$DAMAGED_SHA/" \
    "$LOCAL_JAR_CACHE_ROOT/current/$RELEASE_MANIFEST_NAME"
if reuse_local_source_artifact_cache "$DEST_JAR"; then
    fail "damaged non-ZIP JAR was accepted with a matching SHA"
fi

# Mock the two SSH hosts: identical bytes + real parsed upstream + active unit
# + HTTP 200 allow no-op. Any byte mismatch or FORCE_REDEPLOY must reject it.
MODE=jar
DEPLOY_ENV=prod
DEPLOY_MODE=bluegreen
SERVER=root@backend
GATEWAY=root@gateway
REMOTE_JAR_DIR=/srv/cretas
RUNTIME_JAR_NAME=aims-0.0.1-SNAPSHOT.jar
NGINX_UPSTREAM_FILE=/etc/nginx/upstream.conf
BLUE_SERVICE=cretas-backend
GREEN_SERVICE=cretas-backend-green
LOCAL_MD5=0123456789abcdef0123456789abcdef
MOCK_UPSTREAM_PORT=10020
MOCK_SERVICE_STATE=active
MOCK_HTTP_STATUS=200
ssh() {
    case "$*" in
        *md5sum*)
            [[ "$*" == *"$REMOTE_JAR_DIR/$RUNTIME_JAR_NAME"* ]] \
                || fail "production no-op checked the upload artifact instead of the runtime JAR"
            printf '%s\n' "$LOCAL_MD5"
            ;;
        *"cat '$NGINX_UPSTREAM_FILE'"*) printf 'upstream x { server 47.100.235.168:%s; }\n' "$MOCK_UPSTREAM_PORT" ;;
        *systemctl*) printf '%s\n%s' "$MOCK_SERVICE_STATE" "$MOCK_HTTP_STATUS" ;;
        *) return 1 ;;
    esac
}
prod_already_runs_local_artifact "$LOCAL_MD5" || fail "healthy identical production artifact was not a no-op"
if prod_already_runs_local_artifact ffffffffffffffffffffffffffffffff; then
    fail "remote MD5 mismatch was accepted"
fi
if FORCE_REDEPLOY=1 prod_already_runs_local_artifact "$LOCAL_MD5"; then
    fail "FORCE_REDEPLOY=1 did not bypass no-op"
fi
MOCK_UPSTREAM_PORT=10030
if prod_already_runs_local_artifact "$LOCAL_MD5"; then
    fail "unknown upstream port was accepted"
fi
MOCK_UPSTREAM_PORT=10020
MOCK_SERVICE_STATE=inactive
if prod_already_runs_local_artifact "$LOCAL_MD5"; then
    fail "inactive systemd unit was accepted"
fi
MOCK_SERVICE_STATE=active
MOCK_HTTP_STATUS=503
if prod_already_runs_local_artifact "$LOCAL_MD5"; then
    fail "unhealthy active slot was accepted"
fi

grep -Fq 'if reuse_local_source_artifact_cache "$JAR_TARGET"; then' "$DEPLOY_SCRIPT" \
    || fail "source cache is not wired before Maven"
grep -Fq 'store_local_source_artifact_cache "$JAR_PATH"' "$DEPLOY_SCRIPT" \
    || fail "verified JAR is not persisted"
grep -Fq 'SKIP_BUILD=1 但可信 manifest 未命中；安全回退本地 clean package' "$DEPLOY_SCRIPT" \
    || fail "SKIP_BUILD manifest miss does not fall back to clean package"
grep -Fq '[ "${ENABLE_CI_ARTIFACT_REUSE:-0}" != "1" ]' "$DEPLOY_SCRIPT" \
    || fail "CI artifact fallback is not explicit opt-in"
grep -Fq 'prod_already_runs_local_artifact "$LOCAL_MD5"' "$DEPLOY_SCRIPT" \
    || fail "identical production no-op is not wired"

echo "PASS: backend tree cache and healthy identical-production no-op"
