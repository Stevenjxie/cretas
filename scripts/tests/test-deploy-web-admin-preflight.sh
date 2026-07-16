#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
DEPLOY_SCRIPT="$ROOT_DIR/scripts/deploy/deploy-web-admin.sh"
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
    cat > "$fixture/scripts/lib/deploy-common.sh" <<'COMMON'
check_git_sync() { :; }
COMMON
    printf '{"name":"fixture","lockfileVersion":3,"packages":{}}\n' > "$fixture/web-admin/package-lock.json"
    cat > "$fixture/mock-bin/npm" <<'MOCK_NPM'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$MOCK_NPM_LOG"

if [ "${1:-}" = "ci" ]; then
    if [ "${MOCK_NPM_CI_FAIL:-0}" = "1" ]; then
        exit 42
    fi
    mkdir -p node_modules/.bin
    printf '#!/usr/bin/env bash\n' > node_modules/.bin/vite
    chmod +x node_modules/.bin/vite
    exit 0
fi

if [ "${1:-}" = "run" ] && [ "${2:-}" = "build" ]; then
    mkdir -p dist/assets
    printf '<script type="module" src="/assets/index-test.js"></script>\n' > dist/index.html
    printf 'test\n' > dist/assets/index-test.js
    exit 0
fi

exit 99
MOCK_NPM
    chmod +x "$fixture/mock-bin/npm"
}

run_dry_build() {
    local fixture=$1
    (
        cd "$fixture"
        PATH="$fixture/mock-bin:$PATH" \
            MOCK_NPM_LOG="$fixture/npm.log" \
            MOCK_NPM_CI_FAIL="${MOCK_NPM_CI_FAIL:-0}" \
            bash scripts/deploy/deploy-web-admin.sh --env test --dry-run
    )
}

# Missing Vite: restore once with the exact cache-preferred command, then build.
MISSING_FIXTURE="$TMP_ROOT/missing"
make_fixture "$MISSING_FIXTURE"
run_dry_build "$MISSING_FIXTURE" > "$MISSING_FIXTURE/output.log"
mapfile -t missing_calls < "$MISSING_FIXTURE/npm.log"
[[ "${#missing_calls[@]}" -eq 2 ]] || fail "missing-Vite path expected 2 npm calls"
[[ "${missing_calls[0]}" = "ci --legacy-peer-deps --prefer-offline --no-audit --no-fund" ]] || fail "unexpected npm ci arguments: ${missing_calls[0]}"
[[ "${missing_calls[1]}" = "run build" ]] || fail "build did not follow dependency restore"
grep -Fq "Web 依赖恢复完成，已原子记录 package-lock digest" "$MISSING_FIXTURE/output.log" || fail "missing restore success log"
expected_hash=$(sha256sum "$MISSING_FIXTURE/web-admin/package-lock.json" | awk '{print $1}')
actual_hash=$(tr -d '\r\n' < "$MISSING_FIXTURE/web-admin/node_modules/.cretas-package-lock.sha256")
[[ "$actual_hash" = "$expected_hash" ]] || fail "restore did not atomically record the package-lock digest"

# Existing Vite: do not reinstall dependencies; go straight to the build.
READY_FIXTURE="$TMP_ROOT/ready"
make_fixture "$READY_FIXTURE"
mkdir -p "$READY_FIXTURE/web-admin/node_modules/.bin"
printf '#!/usr/bin/env bash\n' > "$READY_FIXTURE/web-admin/node_modules/.bin/vite"
chmod +x "$READY_FIXTURE/web-admin/node_modules/.bin/vite"
sha256sum "$READY_FIXTURE/web-admin/package-lock.json" | awk '{print $1}' > "$READY_FIXTURE/web-admin/node_modules/.cretas-package-lock.sha256"
run_dry_build "$READY_FIXTURE" > "$READY_FIXTURE/output.log"
mapfile -t ready_calls < "$READY_FIXTURE/npm.log"
[[ "${#ready_calls[@]}" -eq 1 ]] || fail "ready-Vite path unexpectedly ran npm ci"
[[ "${ready_calls[0]}" = "run build" ]] || fail "ready-Vite path did not build directly"
grep -Fq "Trusted package-lock dependency cache hit; npm ci skipped" "$READY_FIXTURE/output.log" || fail "missing skip log"

# Failed restore: exit before build and keep the failure visible.
FAILED_FIXTURE="$TMP_ROOT/failed"
make_fixture "$FAILED_FIXTURE"
set +e
MOCK_NPM_CI_FAIL=1 run_dry_build "$FAILED_FIXTURE" > "$FAILED_FIXTURE/output.log" 2>&1
failed_rc=$?
set -e
[[ "$failed_rc" -ne 0 ]] || fail "failed npm ci must stop deployment"
mapfile -t failed_calls < "$FAILED_FIXTURE/npm.log"
[[ "${#failed_calls[@]}" -eq 1 ]] || fail "failed npm ci must not continue to build"
[[ "${failed_calls[0]}" = "ci --legacy-peer-deps --prefer-offline --no-audit --no-fund" ]] || fail "failure path used unexpected npm arguments"
grep -Fq "Web 依赖恢复失败" "$FAILED_FIXTURE/output.log" || fail "missing dependency failure log"

# Production confirmation must fail fast before dependency/network work, while
# dependency restoration must still happen before the single build.
preflight_line=$(grep -n '^ensure_web_admin_dependencies$' "$DEPLOY_SCRIPT" | cut -d: -f1)
confirm_line=$(grep -n '^    confirm_prod_deploy$' "$DEPLOY_SCRIPT" | cut -d: -f1)
build_line=$(grep -n '^npm run build ' "$DEPLOY_SCRIPT" | cut -d: -f1)
[[ "$confirm_line" -lt "$preflight_line" ]] || fail "explicit prod confirmation must fail before dependency work"
[[ "$preflight_line" -lt "$build_line" ]] || fail "dependency preflight must run before build"

echo "PASS: web-admin dependency preflight validates lock digest, restores, skips, and fails safely"
