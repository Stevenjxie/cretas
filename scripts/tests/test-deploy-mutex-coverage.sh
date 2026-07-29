#!/usr/bin/env bash
# Two concurrent chats/terminals must not be able to deploy the same target at
# once. deploy-backend.sh has held a mutex since the shared-JAR incident; the
# Web deploy and the unified release orchestrator had none, so two sessions
# could interleave a dist upload or clobber the shared artifact cache.
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
COMMON="$ROOT_DIR/scripts/lib/deploy-common.sh"
BACKEND="$ROOT_DIR/scripts/deploy/deploy-backend.sh"
WEB="$ROOT_DIR/scripts/deploy/deploy-web-admin.sh"
RELEASE="$ROOT_DIR/scripts/deploy/release-cretas.sh"
TMP_ROOT=$(mktemp -d)
HOLDER_PID=""
trap 'if [ -n "$HOLDER_PID" ]; then kill "$HOLDER_PID" 2>/dev/null || true; fi; rm -rf "$TMP_ROOT"' EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

# Returns the empty string (not a failure) when a script takes no lock, so the
# assertions below can report which entry point is unprotected.
lock_name_of() {
    local script=$1
    grep -Eom1 'acquire_deploy_lock "[a-z0-9-]+"' "$script" 2>/dev/null \
        | sed -E 's/.*"([a-z0-9-]+)".*/\1/' || true
}

# Every deploy entry point must take a mutex, and each must take a DIFFERENT one:
# the release orchestrator invokes the component scripts, so a shared name would
# deadlock the very path this is meant to protect.
backend_lock=$(lock_name_of "$BACKEND")
web_lock=$(lock_name_of "$WEB")
release_lock=$(lock_name_of "$RELEASE")

[ -n "$backend_lock" ] || fail "deploy-backend.sh no longer acquires a deploy lock"
[ -n "$web_lock" ] || fail "deploy-web-admin.sh does not acquire a deploy lock"
[ -n "$release_lock" ] || fail "release-cretas.sh does not acquire a deploy lock"

[ "$backend_lock" != "$web_lock" ] || fail "backend and web share lock '$backend_lock'"
[ "$release_lock" != "$backend_lock" ] \
    || fail "release orchestrator shares lock '$release_lock' with deploy-backend.sh; it would deadlock"
[ "$release_lock" != "$web_lock" ] \
    || fail "release orchestrator shares lock '$release_lock' with deploy-web-admin.sh; it would deadlock"

# The primitive itself must actually exclude a second holder rather than warn.
# shellcheck source=/dev/null
source "$COMMON"

cat > "$TMP_ROOT/holder.sh" <<EOF
#!/usr/bin/env bash
set -euo pipefail
source "$COMMON"
acquire_deploy_lock "cretas-mutex-selftest" || exit 1
touch "$TMP_ROOT/held"
sleep 30
EOF
chmod +x "$TMP_ROOT/holder.sh"

rm -f /tmp/cretas-mutex-selftest.lock
bash "$TMP_ROOT/holder.sh" &
HOLDER_PID=$!

for _ in $(seq 1 50); do
    [ -f "$TMP_ROOT/held" ] && break
    sleep 0.1
done
[ -f "$TMP_ROOT/held" ] || fail "first holder never acquired the lock"

if ( acquire_deploy_lock "cretas-mutex-selftest" ) >/dev/null 2>&1; then
    fail "a second holder acquired a lock that was already held"
fi

kill "$HOLDER_PID" 2>/dev/null || true
wait "$HOLDER_PID" 2>/dev/null || true
HOLDER_PID=""

# A dead holder must not wedge the next deploy forever.
if ! ( acquire_deploy_lock "cretas-mutex-selftest" ) >/dev/null 2>&1; then
    fail "lock stayed held after its owner died; deploys would be permanently blocked"
fi
rm -f /tmp/cretas-mutex-selftest.lock

echo "PASS: deploy mutex covers backend, web-admin, and the release orchestrator"
