#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
DEPLOY_SCRIPT="$ROOT_DIR/scripts/deploy/deploy-backend.sh"
STAGE_SCRIPT="$ROOT_DIR/scripts/deploy/stage-backend-artifact.sh"
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

HELPERS=$(awk '
    /^# BEGIN_REMOTE_JAR_CACHE_HELPERS$/ {copy = 1; next}
    /^# END_REMOTE_JAR_CACHE_HELPERS$/ {exit}
    copy {print}
' "$DEPLOY_SCRIPT")
eval "$HELPERS"

SERVER=root@example
REMOTE_JAR_DIR=/srv/cretas
REMOTE_TMP=/tmp
REMOTE_JAR_CACHE_DIR=/srv/cretas/release-cache/sha256
JAR_NAME=cretas-backend-system-1.0.0.jar
SHA=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
MD5=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
SSH_LOG="$TMP_ROOT/ssh.log"

ssh() {
    printf '%s\n' "$*" >> "$SSH_LOG"
    return 0
}

claim_remote_sha256_artifact "$SHA" "$MD5" || fail "valid remote cache hit was rejected"
grep -Fq "$REMOTE_JAR_CACHE_DIR/$SHA.jar" "$SSH_LOG" || fail "cache claim did not use SHA path"
grep -Fq "sha256sum" "$SSH_LOG" || fail "cache claim skipped SHA verification"
grep -Fq "md5sum" "$SSH_LOG" || fail "cache claim skipped MD5 verification"
grep -Fq "unzip -tqq" "$SSH_LOG" || fail "cache claim skipped JAR integrity verification"
grep -Fq "awk '{print \$1}'" "$SSH_LOG" || fail "cache claim checksum command was escaped incorrectly"

: > "$SSH_LOG"
persist_remote_sha256_artifact "$SHA" || fail "valid remote cache persistence failed"
grep -Fq "chmod 0444" "$SSH_LOG" || fail "persisted cache artifact is not immutable"
grep -Fq "mv -f" "$SSH_LOG" || fail "cache persistence is not atomic"

: > "$SSH_LOG"
if claim_remote_sha256_artifact invalid "$MD5"; then
    fail "invalid SHA was accepted"
fi
[ ! -s "$SSH_LOG" ] || fail "invalid SHA contacted the server"

grep -Fq 'claim_remote_sha256_artifact "$LOCAL_SHA256" "$LOCAL_MD5"' "$DEPLOY_SCRIPT" \
    || fail "remote cache claim is not wired before upload"
grep -Fq 'persist_remote_sha256_artifact "$LOCAL_SHA256"' "$DEPLOY_SCRIPT" \
    || fail "verified uploads are not persisted in remote cache"
grep -Fq 'WINNER=$(cat "$UPLOAD_STATUS_DIR/winner" 2>/dev/null || true)' "$DEPLOY_SCRIPT" \
    || fail "remote cache winner is overwritten before fallback selection"

grep -Fq '[ "$build_commit" = "$head_commit" ]' "$STAGE_SCRIPT" \
    || fail "prestage does not bind the manifest to reviewed HEAD"
grep -Fq '[ "$backend_tree" = "$head_tree" ]' "$STAGE_SCRIPT" \
    || fail "prestage does not bind the manifest to reviewed backend tree"
grep -Fq 'release_manifest_verify_jar "$jar_path"' "$STAGE_SCRIPT" \
    || fail "prestage skips local JAR integrity validation"
if grep -Eq 'systemctl|nginx -s|NGINX_UPSTREAM' "$STAGE_SCRIPT"; then
    fail "prestage script contains runtime deployment operations"
fi

echo "PASS: remote SHA-256 cache claim and atomic persistence contracts"
