#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/test-helpers.sh"

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BIN="$(mktemp -d)"
trap 'rm -rf "$BIN"' EXIT

cat > "$BIN/ssh" <<'EOF'
#!/usr/bin/env bash
case "$*" in
    *_upstream_cretas.conf*) echo "${MOCK_ACTIVE_PORT:-10020}" ;;
    *systemctl*) echo active ;;
    *health*) echo '{"success":true}' ;;
    *unzip*)
        [[ "$*" == *"/www/wwwroot/cretas/aims-0.0.1-SNAPSHOT.jar"* ]] || exit 9
        exit 0
        ;;
    *grep*) exit 0 ;;
esac
EOF
cat > "$BIN/curl" <<'EOF'
#!/usr/bin/env bash
echo 200
EOF
chmod +x "$BIN/ssh" "$BIN/curl"

REPORT="$(PATH="$BIN:$PATH" bash "$ROOT/scripts/deploy/verify-release.sh" --target all --env prod --backend-marker marker --web-marker marker)"
assert_contains "$REPORT" 'BACKEND_SLOT=green'
assert_contains "$REPORT" 'BACKEND_PORT=10020'
assert_contains "$REPORT" 'BACKEND_HEALTH=pass'
assert_contains "$REPORT" 'BACKEND_MARKER=pass'
assert_contains "$REPORT" 'WEB_HTTP=200'
assert_contains "$REPORT" 'WEB_MARKER=pass'

BLUE_REPORT="$(MOCK_ACTIVE_PORT=10010 PATH="$BIN:$PATH" bash "$ROOT/scripts/deploy/verify-release.sh" --target backend --env prod)"
assert_contains "$BLUE_REPORT" 'BACKEND_SLOT=blue'
assert_contains "$BLUE_REPORT" 'BACKEND_PORT=10010'

assert_exit 1 env MOCK_ACTIVE_PORT=9999 PATH="$BIN:$PATH" \
    bash "$ROOT/scripts/deploy/verify-release.sh" --target backend --env prod
assert_exit 1 env MOCK_ACTIVE_PORT=$'10010\n10020' PATH="$BIN:$PATH" \
    bash "$ROOT/scripts/deploy/verify-release.sh" --target backend --env prod
assert_exit 2 bash "$ROOT/scripts/deploy/verify-release.sh" --target invalid --env prod
assert_exit 2 bash "$ROOT/scripts/deploy/verify-release.sh" --target backend --env prod --backend-marker "bad'marker"
