#!/bin/bash
#
# refresh-demo-rest.sh — daily refresh of DEMO_REST operational demo data.
#
# WHY: backend stats/"this month"/"today" endpoints default their date range to
# LocalDate.now() (current month/day). The demo's operational seed data
# (wastage / stocktaking) is stamped at fixed past dates, so once the calendar
# rolls past that window the headline aggregates collapse to ¥0 / 0 even though
# the detail rows still exist (损耗总额 ¥0.00, 盘盈/盘亏次数 0, 首页本月损耗 0).
#
# This re-runs the ops seeder with --end=today so wastage/stocktaking always
# land inside the current month. The seeder is a full-replace (deletes its own
# demo_rest_wst_% / demo_rest_stk_% rows first), so reruns never accumulate
# orphans. Scoped to DEMO_REST only — never touches real tenant data.
#
# Install (on server 47, as root):
#   crontab -e
#   17 3 * * *  /www/wwwroot/cretas/code/scripts/cron/refresh-demo-rest.sh
#
set -euo pipefail

PYDIR=/www/wwwroot/cretas/code/backend/python
ENV_FILE=/www/wwwroot/cretas/.env.prod
LOG=/www/wwwroot/cretas/logs/demo-rest-refresh.log

mkdir -p "$(dirname "$LOG")"

# Load DB_PASSWORD from the server env file (never hardcode the secret in repo).
# shellcheck disable=SC1090
set -a; source "$ENV_FILE"; set +a
: "${DB_PASSWORD:?DB_PASSWORD not found in $ENV_FILE}"

DSN="dbname=cretas_prod_db user=cretas_user password=${DB_PASSWORD} host=localhost"
TODAY=$(date +%F)

{
  echo "=== $(date '+%F %T') refresh DEMO_REST ops (end=$TODAY) ==="
  cd "$PYDIR"
  # shellcheck disable=SC1091
  source "$PYDIR/venv38/bin/activate"
  python smartbi/scripts/seed_demo_rest_ops.py --dsn "$DSN" --end "$TODAY"
  echo "=== done (rc=$?) ==="
} >> "$LOG" 2>&1
