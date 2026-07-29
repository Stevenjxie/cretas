#!/usr/bin/env bash
# The JAR upload dominates the Java deploy window: 54s of a 199s deploy, moving
# 168MB at a measured 4.00 MB/s. rsync's delta algorithm only engages when the
# destination path already holds a file, and verify_and_claim `mv`s the stable
# rsync target away after every success — so delta never ran and every deploy
# re-sent all 168MB.
#
# Spring Boot stores nested dependency jars UNCOMPRESSED, so consecutive builds
# are largely byte-identical. Measured on the server between two consecutive
# releases: literal 21,396,028 / matched 154,884,240 / speedup 8.18x.
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
DEPLOY_SCRIPT="$ROOT_DIR/scripts/deploy/deploy-backend.sh"
ORCHESTRATOR="$ROOT_DIR/scripts/deploy/release-cretas.sh"
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

# ------------------------------------------------------- wiring contracts
grep -Fq 'seed_rsync_delta_basis()' "$DEPLOY_SCRIPT" \
    || fail "delta basis seeding helper is gone"

# The seed must run BEFORE the rsync that depends on it, and inside the primary
# rsync channel only — the fallbacks stay untouched so a seeding bug cannot take
# every upload path down with it.
# `|| true` on every grep capture: under `set -e -o pipefail` a non-matching grep
# would abort this script with no output at all, turning a real regression into a
# silent exit instead of a named failure.
seed_call_line=$(grep -Fn 'seed_rsync_delta_basis "$TMP_FILE"' "$DEPLOY_SCRIPT" | cut -d: -f1 || true)
[ -n "$seed_call_line" ] || fail "seeding is never invoked"
[ "$(printf '%s\n' "$seed_call_line" | wc -l | tr -d '[:space:]')" = "1" ] \
    || fail "seeding is invoked from more than the primary rsync channel"

rsync_line=$(grep -Fn 'rsync -az --timeout=60 "$JAR_PATH"' "$DEPLOY_SCRIPT" | cut -d: -f1 || true)
[ -n "$rsync_line" ] || fail "primary rsync upload command changed shape"
[ "$seed_call_line" -lt "$rsync_line" ] \
    || fail "basis is seeded after the rsync that needs it (line $seed_call_line vs $rsync_line)"

# The compress and scp fallbacks must remain unseeded.
if awk '/upload_rsync_compress\(\) \{/,/^    \}/' "$DEPLOY_SCRIPT" | grep -Fq 'seed_rsync_delta_basis'; then
    fail "compress fallback was seeded; keep the blast radius on one channel"
fi
if awk '/upload_scp\(\) \{/,/^    \}/' "$DEPLOY_SCRIPT" | grep -Fq 'seed_rsync_delta_basis'; then
    fail "scp fallback was seeded; it cannot use a delta basis at all"
fi

# Seeding must never be able to fail the deploy: a missing cache, an unreachable
# host, or an unreadable jar has to degrade to a full transfer, not an abort.
seed_body=$(awk '/seed_rsync_delta_basis\(\) \{/,/^    \}/' "$DEPLOY_SCRIPT")
# Anchor on the ssh invocation's own terminator, not any `|| true` in the body:
# the inner `cp ... || true` would otherwise satisfy a loose match and let a
# removal of the OUTER tolerance pass unnoticed (caught by mutation testing).
grep -Fq '" 2>/dev/null || true' <<<"$seed_body" \
    || fail "the ssh seeding call can abort the deploy instead of degrading to a full transfer"
grep -Fq 'exit 0' <<<"$seed_body" \
    || fail "seeding does not short-circuit on an empty artifact cache"

# ------------------------------------------------- behavioural: seed semantics
# Reproduce the helper's remote body locally: newest cached jar wins, absent
# cache is a silent no-op, and the target is left byte-identical to the basis.
cache="$TMP_ROOT/cache"
mkdir -p "$cache"
target="$TMP_ROOT/upload.jar.rsync"

seed_local() {
    local cache_dir=$1 dest=$2
    newest=$(ls -t "$cache_dir"/*.jar 2>/dev/null | head -1) || true
    [ -n "$newest" ] || return 0
    cp -f "$newest" "$dest" 2>/dev/null || true
}

# Empty cache: no target created, no failure.
seed_local "$cache" "$target" || fail "empty cache made seeding fail"
[ ! -f "$target" ] || fail "empty cache still produced a basis file"

printf 'older jar\n' > "$cache/aaa.jar"
sleep 1
printf 'newest jar payload\n' > "$cache/zzz.jar"
seed_local "$cache" "$target" || fail "seeding failed with a populated cache"
[ -f "$target" ] || fail "basis file was not created"
cmp -s "$target" "$cache/zzz.jar" \
    || fail "seed used a stale cache entry instead of the newest jar"

# A cache directory that does not exist at all must also be a silent no-op.
rm -f "$target"
seed_local "$TMP_ROOT/nope" "$target" || fail "missing cache dir made seeding fail"
[ ! -f "$target" ] || fail "missing cache dir still produced a basis file"

# ------------------------------------------------------- staging visibility
# --stage-backend moves the upload out of the deploy window entirely, but it is
# opt-in and was `not-requested` in six consecutive releases. A build that skips
# it while Java changed must say so.
grep -Fq 'HINT: Java 制品未预热到服务器缓存' "$ORCHESTRATOR" \
    || fail "skipping artifact staging is silent again"
hint_block=$(awk '/^stage_backend_artifact\(\) \{/,/^\}/' "$ORCHESTRATOR")
grep -Fq 'JAVA_CHANGED' <<<"$hint_block" \
    || fail "staging hint is not gated on Java actually having changed"

echo "PASS: rsync delta basis seeding and artifact staging visibility"
