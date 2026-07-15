#!/usr/bin/env bash
# Poll an HTTP health endpoint until it returns 200 or timeout
set -e

URL="${1:-http://localhost:10010/api/mobile/health}"
TIMEOUT="${2:-120}"
WATCH_PID="${3:-}"
INTERVAL=2

echo "[wait-for-health] 等待 $URL (超时 ${TIMEOUT}s)"

START=$(date +%s)
while true; do
  if curl -sf -o /dev/null -w "%{http_code}" "$URL" 2>/dev/null | grep -q "^200$"; then
    echo "[wait-for-health] ✓ ready"
    exit 0
  fi
  if [ -n "$WATCH_PID" ] && ! kill -0 "$WATCH_PID" 2>/dev/null; then
    echo "[wait-for-health] ✗ watched process $WATCH_PID exited before health became ready" >&2
    exit 1
  fi
  NOW=$(date +%s)
  ELAPSED=$((NOW - START))
  if [ "$ELAPSED" -ge "$TIMEOUT" ]; then
    echo "[wait-for-health] ✗ 超时 after ${TIMEOUT}s" >&2
    exit 1
  fi
  sleep "$INTERVAL"
done
