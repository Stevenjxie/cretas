#!/usr/bin/env bash
#
# Sprint 12 Phase D — nightly restaurant finance ETL bulk trigger
#
# Calls POST /api/smartbi/restaurant/etl/finance-etl/trigger-bulk with the
# default RESTAURANT_FACTORY_BACKFILL_LIST (F001 + RES_3101_009 + R_GML_DEMO
# + R_XMX_CHAIN) for yesterday's date. F006 excluded — no POS source data.
#
# Installation (DevOps, on 47.100.235.168):
#   1. Ensure scripts/systemd/cretas-finance-etl-daily.service + .timer are
#      symlinked to /etc/systemd/system/
#   2. systemctl daemon-reload
#   3. systemctl enable --now cretas-finance-etl-daily.timer
#   4. Verify:
#        systemctl list-timers cretas-finance-etl-daily.timer
#        systemctl status cretas-finance-etl-daily.service
#   5. Logs land in journalctl -u cretas-finance-etl-daily
#
# Manual ad-hoc invocation:
#   /www/wwwroot/cretas/code/scripts/ops/run-finance-etl-daily.sh
#   YESTERDAY_ONLY=0 START_DATE=2025-01-01 END_DATE=2025-12-31 \
#     /www/wwwroot/cretas/code/scripts/ops/run-finance-etl-daily.sh   # full backfill
#
# Auth: reads ADMIN_JWT from /etc/cretas/finance-etl-cron.env (mode 600).
# The token must belong to a platform_admin / factory_super_admin user.
#
# Exit codes:
#   0 — bulk job accepted (jobId returned by API)
#   1 — API returned non-200 (auth / 400 / 500)
#   2 — infrastructure error (missing env / curl missing)
set -euo pipefail

# --- Config ---
API_BASE="${CRETAS_API_BASE:-http://localhost:10010}"
ENV_FILE="${CRETAS_ETL_ENV_FILE:-/etc/cretas/finance-etl-cron.env}"
LOG_TAG="finance-etl-daily"

if [ ! -r "$ENV_FILE" ]; then
    echo "[$LOG_TAG] ERROR: $ENV_FILE not readable. Create it with: echo 'ADMIN_JWT=<token>' > $ENV_FILE && chmod 600 $ENV_FILE" >&2
    exit 2
fi
# shellcheck disable=SC1090
. "$ENV_FILE"

if [ -z "${ADMIN_JWT:-}" ]; then
    echo "[$LOG_TAG] ERROR: ADMIN_JWT not set in $ENV_FILE" >&2
    exit 2
fi

if ! command -v curl >/dev/null 2>&1; then
    echo "[$LOG_TAG] ERROR: curl not installed" >&2
    exit 2
fi

# --- Date range ---
# Default: backfill yesterday only (cron caught-up mode).
# Set YESTERDAY_ONLY=0 + START_DATE / END_DATE for ad-hoc range.
if [ "${YESTERDAY_ONLY:-1}" = "1" ]; then
    YESTERDAY="$(date -d 'yesterday' +%Y-%m-%d)"
    START_DATE="$YESTERDAY"
    END_DATE="$YESTERDAY"
fi

START_DATE="${START_DATE:-}"
END_DATE="${END_DATE:-}"

# --- Build JSON body ---
if [ -n "$START_DATE" ] && [ -n "$END_DATE" ]; then
    BODY="{\"startDate\":\"$START_DATE\",\"endDate\":\"$END_DATE\"}"
else
    BODY="{}"
fi

# --- Call API ---
URL="$API_BASE/api/smartbi/restaurant/etl/finance-etl/trigger-bulk"
echo "[$LOG_TAG] POST $URL body=$BODY"

HTTP_CODE=$(curl -sS -o /tmp/finance-etl-daily.out -w "%{http_code}" \
    -X POST "$URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_JWT" \
    -d "$BODY") || HTTP_CODE="000"

echo "[$LOG_TAG] HTTP $HTTP_CODE"
cat /tmp/finance-etl-daily.out 2>/dev/null || true
echo ""

if [ "$HTTP_CODE" = "200" ]; then
    exit 0
else
    echo "[$LOG_TAG] ERROR: API returned $HTTP_CODE" >&2
    exit 1
fi
