#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SERVER="root@47.100.235.168"
SERVICE="cretas-restaurant-demo-stream-20260805.service"
TIMER="cretas-restaurant-demo-stream-20260805.timer"
CONFIRM=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --confirm-prod) CONFIRM="${2:-}"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

[[ "$CONFIRM" == "YES-PROD-DEMO-STREAM" ]] || {
  echo "Production timer installation requires --confirm-prod YES-PROD-DEMO-STREAM" >&2
  exit 2
}

cd "$PROJECT_ROOT"
git fetch origin main --quiet
[[ -z "$(git status --porcelain)" ]] || {
  echo "Release worktree must be clean" >&2
  exit 1
}
HEAD_SHA="$(git rev-parse HEAD)"
MAIN_SHA="$(git rev-parse origin/main)"
[[ "$HEAD_SHA" == "$MAIN_SHA" ]] || {
  echo "Timer must be installed from exact origin/main ($HEAD_SHA != $MAIN_SHA)" >&2
  exit 1
}

SERVICE_PATH="$PROJECT_ROOT/scripts/systemd/$SERVICE"
TIMER_PATH="$PROJECT_ROOT/scripts/systemd/$TIMER"
[[ -f "$SERVICE_PATH" && -f "$TIMER_PATH" ]] || {
  echo "Tracked service/timer files are missing" >&2
  exit 1
}
grep -Fq -- '--factory MOCK_REST' "$SERVICE_PATH"
grep -Fq -- '--source cretas_live_showcase_20260805' "$SERVICE_PATH"
grep -Fq -- '--interval-seconds 10' "$SERVICE_PATH"
grep -Fq -- '--confirm MOCK_REST' "$SERVICE_PATH"
grep -Fq -- '2026-08-05 09:00:00 Asia/Singapore' "$TIMER_PATH"
if grep -Eq -- 'RES_3101_009|F006' "$SERVICE_PATH" "$TIMER_PATH"; then
  echo "Timer bundle contains a forbidden tenant" >&2
  exit 1
fi

SERVICE_SHA="$(sha256sum "$SERVICE_PATH" | awk '{print $1}')"
TIMER_SHA="$(sha256sum "$TIMER_PATH" | awk '{print $1}')"
REMOTE_DIR="/tmp/cretas-demo-stream-${HEAD_SHA:0:12}-$$"
REPORT_DIR="${HOME}/.cache/cretas/deploy-reports"
REPORT_PATH="$REPORT_DIR/restaurant-demo-stream-${HEAD_SHA:0:12}.json"
mkdir -p "$REPORT_DIR"

cleanup() {
  ssh "$SERVER" "rm -rf '$REMOTE_DIR'" >/dev/null 2>&1 || true
}
trap cleanup EXIT

ssh "$SERVER" "mkdir -p '$REMOTE_DIR'"
scp -q "$SERVICE_PATH" "$TIMER_PATH" "$SERVER:$REMOTE_DIR/"

REMOTE_OUTPUT="$(ssh "$SERVER" bash -s -- \
  "$REMOTE_DIR" "$SERVICE" "$TIMER" "$SERVICE_SHA" "$TIMER_SHA" <<'REMOTE'
set -euo pipefail
bundle="$1"
service="$2"
timer="$3"
expected_service_sha="$4"
expected_timer_sha="$5"

[[ "$(sha256sum "$bundle/$service" | awk '{print $1}')" == "$expected_service_sha" ]]
[[ "$(sha256sum "$bundle/$timer" | awk '{print $1}')" == "$expected_timer_sha" ]]
systemd-analyze verify "$bundle/$service" "$bundle/$timer"
mkdir -p /www/wwwroot/cretas/logs
install -m 0644 "$bundle/$service" "/etc/systemd/system/$service"
install -m 0644 "$bundle/$timer" "/etc/systemd/system/$timer"
systemctl daemon-reload
systemctl enable --now "$timer"
systemctl is-enabled "$timer"
systemctl is-active "$timer"
systemctl show "$timer" -p NextElapseUSecRealtime -p LastTriggerUSec -p Unit --no-pager
systemctl show "$service" -p ActiveState -p SubState -p ExecMainStatus --no-pager
REMOTE
)"

python - "$REPORT_PATH" "$HEAD_SHA" "$SERVICE_SHA" "$TIMER_SHA" "$REMOTE_OUTPUT" <<'PY'
import json
import sys
from datetime import datetime, timezone

path, commit, service_sha, timer_sha, output = sys.argv[1:]
report = {
    "format": "cretas-restaurant-demo-stream-install-v1",
    "status": "scheduled",
    "commit": commit,
    "factory_id": "MOCK_REST",
    "source": "cretas_live_showcase_20260805",
    "is_simulated": True,
    "interval_seconds": 10,
    "start": "2026-08-05T09:00:00+08:00",
    "end": "2026-08-05T14:00:00+08:00",
    "event_ceiling": 1800,
    "service_sha256": service_sha,
    "timer_sha256": timer_sha,
    "remote_status": output.splitlines(),
    "recorded_at": datetime.now(timezone.utc).isoformat(),
}
with open(path, "w", encoding="utf-8") as handle:
    json.dump(report, handle, ensure_ascii=False, indent=2)
    handle.write("\n")
print(json.dumps(report, ensure_ascii=False))
PY

echo "DEMO_STREAM_INSTALL_REPORT=$REPORT_PATH"
