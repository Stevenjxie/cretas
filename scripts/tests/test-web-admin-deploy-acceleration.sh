#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
DEPLOY_SCRIPT="$ROOT_DIR/scripts/deploy/deploy-web-admin.sh"
WEB_MANIFEST_SCRIPT="$ROOT_DIR/scripts/deploy/release-web-manifest.sh"
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

make_fixture() {
    local fixture=$1
    mkdir -p "$fixture/scripts/deploy" "$fixture/scripts/lib" "$fixture/web-admin" "$fixture/mock-bin"
    cp "$DEPLOY_SCRIPT" "$fixture/scripts/deploy/deploy-web-admin.sh"
    cp "$WEB_MANIFEST_SCRIPT" "$fixture/scripts/deploy/release-web-manifest.sh"
    printf 'check_git_sync() { :; }\n' > "$fixture/scripts/lib/deploy-common.sh"
    printf '{"name":"fixture","lockfileVersion":3,"packages":{}}\n' > "$fixture/web-admin/package-lock.json"
cat > "$fixture/mock-bin/npm" <<'MOCK_NPM'
#!/usr/bin/env bash
set -euo pipefail
if [ "${1:-}" = "--version" ]; then
    printf '10.0.0\n'
    exit 0
fi
printf '%s\n' "$*" >> "$MOCK_NPM_LOG"
if [ "${1:-}" = "ci" ]; then
    mkdir -p node_modules/.bin
    printf '#!/usr/bin/env bash\n' > node_modules/.bin/vite
    chmod +x node_modules/.bin/vite
    exit 0
fi
if [ "${1:-}" = "run" ] && [ "${2:-}" = "build" ]; then
    mkdir -p dist/assets
    printf '<script type="module" src="/assets/index-test.js"></script>\n' > dist/index.html
    printf 'fixture\n' > dist/assets/index-test.js
    exit 0
fi
exit 99
MOCK_NPM
    chmod +x "$fixture/mock-bin/npm"
    printf 'web-admin/dist\nweb-admin/node_modules\ncache\n*.log\n' > "$fixture/.gitignore"
    git -C "$fixture" init -q -b main
    git -C "$fixture" config user.email fixture@example.com
    git -C "$fixture" config user.name Fixture
    git -C "$fixture" add .
    git -C "$fixture" commit -qm fixture
    git -C "$fixture" update-ref refs/remotes/origin/main HEAD
}

run_dry_build() {
    local fixture=$1
    (
        cd "$fixture"
        PATH="$fixture/mock-bin:$PATH" MOCK_NPM_LOG="$fixture/npm.log" \
            CRETAS_WEB_CACHE_DIR="$fixture/cache" \
            bash scripts/deploy/deploy-web-admin.sh --env test --dry-run
    )
}

# A non-interactive production caller must make an explicit production choice.
CONFIRM="$TMP_ROOT/confirm"
make_fixture "$CONFIRM"
set +e
(
    cd "$CONFIRM"
    PATH="$CONFIRM/mock-bin:$PATH" MOCK_NPM_LOG="$CONFIRM/npm.log" \
        CRETAS_WEB_CACHE_DIR="$CONFIRM/cache" \
        bash scripts/deploy/deploy-web-admin.sh --env prod --dry-run </dev/null
) > "$TMP_ROOT/prod-unconfirmed.log" 2>&1
unconfirmed_rc=$?
set -e
[[ "$unconfirmed_rc" -ne 0 ]] || fail "unconfirmed non-interactive prod deploy was accepted"
grep -Fq -- "--confirm-prod YES-PROD" "$TMP_ROOT/prod-unconfirmed.log" || fail "missing actionable prod confirmation error"

# An explicit flag and environment variable both enable non-interactive dry runs.
(
    cd "$CONFIRM"
    PATH="$CONFIRM/mock-bin:$PATH" MOCK_NPM_LOG="$CONFIRM/npm.log" \
        CRETAS_WEB_CACHE_DIR="$CONFIRM/cache" \
        bash scripts/deploy/deploy-web-admin.sh --env prod --confirm-prod YES-PROD --dry-run </dev/null
) > "$TMP_ROOT/prod-flag.log" 2>&1
(
    cd "$CONFIRM"
    PATH="$CONFIRM/mock-bin:$PATH" MOCK_NPM_LOG="$CONFIRM/npm.log" CRETAS_WEB_PROD_CONFIRM=YES-PROD \
        CRETAS_WEB_CACHE_DIR="$CONFIRM/cache" \
        bash scripts/deploy/deploy-web-admin.sh --env prod --dry-run </dev/null
) > "$TMP_ROOT/prod-env.log" 2>&1
grep -Fq "explicitly confirmed by caller" "$TMP_ROOT/prod-flag.log" || fail "prod flag was not recognized"
grep -Fq "explicitly confirmed by caller" "$TMP_ROOT/prod-env.log" || fail "prod environment confirmation was not recognized"

# Cache miss: npm ci runs once, then one build, and atomically records the lock digest.
MISS="$TMP_ROOT/miss"
make_fixture "$MISS"
run_dry_build "$MISS" > "$MISS/output.log"
mapfile -t miss_calls < "$MISS/npm.log"
[[ "${#miss_calls[@]}" -eq 2 ]] || fail "cache miss expected npm ci plus one build; got: ${miss_calls[*]}"
[[ "${miss_calls[0]}" = "ci --legacy-peer-deps --prefer-offline --no-audit --no-fund" ]] || fail "unexpected npm ci command: ${miss_calls[0]}"
[[ "${miss_calls[1]}" = "run build" ]] || fail "cache miss did not build exactly once"
expected_hash=$(sha256sum "$MISS/web-admin/package-lock.json" | awk '{print $1}')
actual_hash=$(tr -d '\r\n' < "$MISS/web-admin/node_modules/.cretas-package-lock.sha256")
[[ "$actual_hash" = "$expected_hash" ]] || fail "cache miss did not record the package-lock digest"
grep -Fq "Dependency restore stage:" "$MISS/output.log" || fail "missing dependency restore timing"

# The successful fallback build writes a trusted dist. The next exact-main
# invocation must reuse it without either npm ci or npm run build.
: > "$MISS/npm.log"
run_dry_build "$MISS" > "$MISS/reuse-output.log"
[[ ! -s "$MISS/npm.log" ]] || fail "trusted Web dist reuse unexpectedly invoked npm"
grep -Fq "Trusted Web dist manifest hit; npm ci/build skipped" "$MISS/reuse-output.log" || fail "missing trusted dist reuse log"
grep -Fq "复用可信 Web dist" "$MISS/reuse-output.log" || fail "reuse path did not reach packaging"

# Cache hit: matching manifest plus executable tool skips npm ci and builds once.
HIT="$TMP_ROOT/hit"
make_fixture "$HIT"
mkdir -p "$HIT/web-admin/node_modules/.bin"
printf '#!/usr/bin/env bash\n' > "$HIT/web-admin/node_modules/.bin/vite"
chmod +x "$HIT/web-admin/node_modules/.bin/vite"
sha256sum "$HIT/web-admin/package-lock.json" | awk '{print $1}' > "$HIT/web-admin/node_modules/.cretas-package-lock.sha256"
run_dry_build "$HIT" > "$HIT/output.log"
mapfile -t hit_calls < "$HIT/npm.log"
[[ "${#hit_calls[@]}" -eq 1 && "${hit_calls[0]}" = "run build" ]] || fail "cache hit did not skip npm ci or build exactly once"
grep -Fq "Dependency reuse stage:" "$HIT/output.log" || fail "missing dependency reuse timing"

# A changed lock hash must invalidate the manifest even when Vite exists.
printf 'stale\n' > "$HIT/web-admin/node_modules/.cretas-package-lock.sha256"
rm -rf "$HIT/cache/current"
: > "$HIT/npm.log"
run_dry_build "$HIT" > "$HIT/stale-output.log"
mapfile -t stale_calls < "$HIT/npm.log"
[[ "${stale_calls[0]}" = "ci --legacy-peer-deps --prefer-offline --no-audit --no-fund" ]] || fail "stale manifest incorrectly reused dependencies"

echo "PASS: web-admin prod confirmation and dependency cache hit/miss contracts"
