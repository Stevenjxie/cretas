#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

for obsolete in \
    scripts/deploy.sh \
    backend/java/cretas-api/deploy.sh \
    backend/java/cretas-api/scripts/deploy-first-time.sh; do
    [ ! -e "$ROOT_DIR/$obsolete" ] || fail "obsolete deploy entrypoint still exists: $obsolete"
done

backend="$ROOT_DIR/scripts/deploy/deploy-backend.sh"
orchestrator="$ROOT_DIR/scripts/deploy/release-cretas.sh"
web_manifest="$ROOT_DIR/scripts/deploy/release-web-manifest.sh"
web_workflow="$ROOT_DIR/.github/workflows/web-dist.yml"

grep -Fq -- '--git' "$backend" && fail "legacy server-build --git mode still exists"
grep -Fq 'deploy_git()' "$backend" && fail "legacy deploy_git function still exists"
legacy_output=$(bash "$backend" --git 2>&1) \
    && fail "removed --git option was accepted"
grep -Fq '未知参数: --git' <<<"$legacy_output" \
    || fail "removed --git option did not fail closed"
grep -Fq './deploy.sh' "$ROOT_DIR/backend/java/cretas-api/README.md" \
    && fail "backend README still advertises the removed direct deploy script"

grep -Fq 'deploy-backend.sh' "$orchestrator" \
    || fail "unified release no longer invokes the Java installer"
grep -Fq 'deploy-web-admin.sh' "$orchestrator" \
    || fail "unified release no longer invokes the Web installer"
grep -Fq 'release-web-ci-artifact.sh' "$web_manifest" \
    || fail "Web trusted-artifact retrieval is disconnected"
grep -Fq 'actions/attest-build-provenance@' "$web_workflow" \
    || fail "Web CI artifact provenance signing is missing"
grep -Fq 'actions/upload-artifact@' "$web_workflow" \
    || fail "Web CI artifact upload is missing"

echo "PASS: unified trusted-artifact release entrypoint is the only Java/Web production path"
