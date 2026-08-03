#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SERVER="root@47.100.235.168"
SERVICE="cretas-restaurant-demo-stream-20260805.service"
TIMER="cretas-restaurant-demo-stream-20260805.timer"
QHJ_SERVICE="cretas-restaurant-demo-stream-qhj-20260805.service"
QHJ_TIMER="cretas-restaurant-demo-stream-qhj-20260805.timer"
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
QHJ_SERVICE_PATH="$PROJECT_ROOT/scripts/systemd/$QHJ_SERVICE"
QHJ_TIMER_PATH="$PROJECT_ROOT/scripts/systemd/$QHJ_TIMER"
[[ -f "$SERVICE_PATH" && -f "$TIMER_PATH" && -f "$QHJ_SERVICE_PATH" && -f "$QHJ_TIMER_PATH" ]] || {
  echo "Tracked service/timer files are missing" >&2
  exit 1
}
grep -Fq -- '--factory MOCK_REST' "$SERVICE_PATH"
grep -Fq -- '--source cretas_live_showcase_20260805' "$SERVICE_PATH"
grep -Fq -- '--interval-seconds 10' "$SERVICE_PATH"
grep -Fq -- '--confirm MOCK_REST' "$SERVICE_PATH"
grep -Fq -- '--factory RES_3101_009' "$QHJ_SERVICE_PATH"
grep -Fq -- '--source cretas_live_showcase_20260805' "$QHJ_SERVICE_PATH"
grep -Fq -- '--interval-seconds 10' "$QHJ_SERVICE_PATH"
grep -Fq -- '--confirm RES_3101_009' "$QHJ_SERVICE_PATH"
grep -Fq -- '2026-08-05 09:00:00 Asia/Singapore' "$TIMER_PATH"
grep -Fq -- '2026-08-05 09:00:00 Asia/Singapore' "$QHJ_TIMER_PATH"
if grep -Eq -- 'F006' "$SERVICE_PATH" "$TIMER_PATH" "$QHJ_SERVICE_PATH" "$QHJ_TIMER_PATH"; then
  echo "Timer bundle contains a forbidden tenant" >&2
  exit 1
fi

SERVICE_SHA="$(sha256sum "$SERVICE_PATH" | awk '{print $1}')"
TIMER_SHA="$(sha256sum "$TIMER_PATH" | awk '{print $1}')"
QHJ_SERVICE_SHA="$(sha256sum "$QHJ_SERVICE_PATH" | awk '{print $1}')"
QHJ_TIMER_SHA="$(sha256sum "$QHJ_TIMER_PATH" | awk '{print $1}')"
REMOTE_DIR="/tmp/cretas-demo-stream-${HEAD_SHA:0:12}-$$"
REPORT_DIR="${HOME}/.cache/cretas/deploy-reports"
REPORT_PATH="$REPORT_DIR/restaurant-demo-stream-${HEAD_SHA:0:12}.json"
mkdir -p "$REPORT_DIR"

cleanup() {
  ssh "$SERVER" "rm -rf '$REMOTE_DIR'" >/dev/null 2>&1 || true
}
trap cleanup EXIT

ssh "$SERVER" "mkdir -p '$REMOTE_DIR'"
scp -q "$SERVICE_PATH" "$TIMER_PATH" "$QHJ_SERVICE_PATH" "$QHJ_TIMER_PATH" "$SERVER:$REMOTE_DIR/"

REMOTE_OUTPUT="$(ssh "$SERVER" bash -s -- \
  "$REMOTE_DIR" "$SERVICE" "$TIMER" "$QHJ_SERVICE" "$QHJ_TIMER" \
  "$SERVICE_SHA" "$TIMER_SHA" "$QHJ_SERVICE_SHA" "$QHJ_TIMER_SHA" <<'REMOTE'
set -euo pipefail
bundle="$1"
service="$2"
timer="$3"
qhj_service="$4"
qhj_timer="$5"
expected_service_sha="$6"
expected_timer_sha="$7"
expected_qhj_service_sha="$8"
expected_qhj_timer_sha="$9"

[[ "$(sha256sum "$bundle/$service" | awk '{print $1}')" == "$expected_service_sha" ]]
[[ "$(sha256sum "$bundle/$timer" | awk '{print $1}')" == "$expected_timer_sha" ]]
[[ "$(sha256sum "$bundle/$qhj_service" | awk '{print $1}')" == "$expected_qhj_service_sha" ]]
[[ "$(sha256sum "$bundle/$qhj_timer" | awk '{print $1}')" == "$expected_qhj_timer_sha" ]]
systemd-analyze verify "$bundle/$service" "$bundle/$timer" "$bundle/$qhj_service" "$bundle/$qhj_timer"
mkdir -p /www/wwwroot/cretas/logs
install -m 0644 "$bundle/$service" "/etc/systemd/system/$service"
install -m 0644 "$bundle/$timer" "/etc/systemd/system/$timer"
install -m 0644 "$bundle/$qhj_service" "/etc/systemd/system/$qhj_service"
install -m 0644 "$bundle/$qhj_timer" "/etc/systemd/system/$qhj_timer"
systemctl daemon-reload
systemctl enable --now "$timer"
systemctl enable --now "$qhj_timer"
systemctl is-enabled "$timer"
systemctl is-active "$timer"
systemctl is-enabled "$qhj_timer"
systemctl is-active "$qhj_timer"
systemctl show "$timer" -p NextElapseUSecRealtime -p LastTriggerUSec -p Unit --no-pager
systemctl show "$qhj_timer" -p NextElapseUSecRealtime -p LastTriggerUSec -p Unit --no-pager
systemctl show "$service" -p ActiveState -p SubState -p ExecMainStatus --no-pager
systemctl show "$qhj_service" -p ActiveState -p SubState -p ExecMainStatus --no-pager
REMOTE
)"

python - "$REPORT_PATH" "$HEAD_SHA" "$SERVICE_SHA" "$TIMER_SHA" "$QHJ_SERVICE_SHA" "$QHJ_TIMER_SHA" "$REMOTE_OUTPUT" <<'PY'
import json
import sys
from datetime import datetime, timezone

path, commit, service_sha, timer_sha, qhj_service_sha, qhj_timer_sha, output = sys.argv[1:]
report = {
    "format": "cretas-restaurant-demo-stream-install-v1",
    "status": "scheduled",
    "commit": commit,
    "factory_ids": ["MOCK_REST", "RES_3101_009"],
    "source": "cretas_live_showcase_20260805",
    "is_simulated": True,
    "interval_seconds": 10,
    "start": "2026-08-05T09:00:00+08:00",
    "end": "2026-08-05T14:00:00+08:00",
    "event_ceiling": 1800,
    "service_sha256": service_sha,
    "timer_sha256": timer_sha,
    "qhj_service_sha256": qhj_service_sha,
    "qhj_timer_sha256": qhj_timer_sha,
    "remote_status": output.splitlines(),
    "recorded_at": datetime.now(timezone.utc).isoformat(),
}
with open(path, "w", encoding="utf-8") as handle:
    json.dump(report, handle, ensure_ascii=False, indent=2)
    handle.write("\n")
print(json.dumps(report, ensure_ascii=False))
PY

echo "DEMO_STREAM_INSTALL_REPORT=$REPORT_PATH"
