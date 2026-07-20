#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
DEPLOY_SCRIPT="$ROOT_DIR/scripts/deploy/deploy-backend.sh"
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

PROBE_HELPER=$(awk '
    /^# BEGIN_POST_SWITCH_PROBE_HELPER$/ {copy = 1; next}
    /^# END_POST_SWITCH_PROBE_HELPER$/ {copy = 0}
    copy {print}
' "$DEPLOY_SCRIPT")
[ -n "$PROBE_HELPER" ] || fail "post-switch probe helper markers missing"
eval "$PROBE_HELPER"

PROBE_STATE="$TMP_ROOT/probe-count"
PROBE_MODE=retry_then_pass
printf '0\n' > "$PROBE_STATE"

ssh() {
    local count
    count=$(cat "$PROBE_STATE")
    count=$((count + 1))
    printf '%s\n' "$count" > "$PROBE_STATE"

    if [ "$PROBE_MODE" = "always_fail" ] || [ "$count" -le 2 ]; then
        return 255
    fi
    case "$*" in
        *curl*) printf '200\n' ;;
        *systemctl*) printf 'active\n' ;;
        *) return 2 ;;
    esac
}

sleep() { :; }

CRETAS_POST_SWITCH_PROBE_ATTEMPTS=3
CRETAS_POST_SWITCH_PROBE_RETRY_SECONDS=0
post_switch_probe gateway server cretas-backend \
    || fail "probe did not recover after one transient SSH round"
[ "$(cat "$PROBE_STATE")" -eq 4 ] || fail "probe did not stop after the successful retry"
[ "$POST_SWITCH_HTTP" = "200" ] || fail "successful HTTP status was not retained"
[ "$POST_SWITCH_SYSTEMD" = "active" ] || fail "successful systemd status was not retained"

PROBE_MODE=always_fail
printf '0\n' > "$PROBE_STATE"
if post_switch_probe gateway server cretas-backend; then
    fail "sustained probe failure unexpectedly passed"
fi
[ "$(cat "$PROBE_STATE")" -eq 6 ] || fail "sustained failure did not consume exactly three bounded attempts"

echo "PASS: post-switch probe retries transient SSH failures and preserves sustained-failure rollback signaling"
