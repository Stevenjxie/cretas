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
# After that, the reserved-version aggregate refresh extends demo
# sales through yesterday, the latest complete business day. It is idempotent,
# auditable and reversible through the seed version owned by the Python module.
# The source and target are hard-confirmed demo identities; real tenant data is
# never selected by this cron entry.
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

# Load secrets from the server env file (never hardcode or print them).
[[ -r "$ENV_FILE" ]] || { echo "Required server environment file is unavailable" >&2; exit 1; }
# shellcheck disable=SC1090
set -a; source "$ENV_FILE"; set +a
: "${DB_PASSWORD:?DB_PASSWORD not found in $ENV_FILE}"
: "${SMARTBI_DB_NAME:?SMARTBI_DB_NAME not found in $ENV_FILE}"
: "${SMARTBI_DB_PASSWORD:?SMARTBI_DB_PASSWORD not found in $ENV_FILE}"

# The aggregate refresher reads POSTGRES_* only. Map the existing SmartBI
# production variables explicitly and fail closed before invoking Python.
export POSTGRES_HOST=localhost
export POSTGRES_PORT=5432
export POSTGRES_DB="$SMARTBI_DB_NAME"
export POSTGRES_USER=smartbi_user
export POSTGRES_PASSWORD="$SMARTBI_DB_PASSWORD"
: "${POSTGRES_HOST:?POSTGRES_HOST is required}"
: "${POSTGRES_PORT:?POSTGRES_PORT is required}"
: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}"

DSN="dbname=cretas_prod_db user=cretas_user password=${DB_PASSWORD} host=localhost"
TODAY=$(date +%F)
YESTERDAY=$(date -d yesterday +%F)

{
  echo "=== $(date '+%F %T') refresh DEMO_REST ops (end=$TODAY) ==="
  cd "$PYDIR"
  # shellcheck disable=SC1091
  source "$PYDIR/venv-current/bin/activate"
  python smartbi/scripts/seed_demo_rest_ops.py --dsn "$DSN" --end "$TODAY"
  echo "=== refresh DEMO_REST sales aggregate (source=RES_3101_009, end=$YESTERDAY) ==="
  python -m smartbi.scripts.refresh_qhj_demo_recent_agg \
    --apply \
    --confirm RES_3101_009 \
    --end "$YESTERDAY"
  echo "=== refresh DEMO_REST agg_daily from own POS grain (end=$YESTERDAY) ==="
  python -m smartbi.scripts.refresh_demo_rest_agg_daily \
    --apply \
    --confirm DEMO_REST \
    --end "$YESTERDAY"
  echo "=== refresh DEMO_REST dish-level POS items (end=$YESTERDAY) ==="
  python -m smartbi.scripts.refresh_demo_rest_dish_facts \
    --apply \
    --confirm DEMO_REST \
    --end "$YESTERDAY"
  echo "=== refresh RES_3101_009 dish-level POS grain (end=$YESTERDAY) ==="
  python -m smartbi.scripts.refresh_demo_rest_dish_facts \
    --factory RES_3101_009 \
    --apply \
    --confirm RES_3101_009 \
    --end "$YESTERDAY"
  echo "=== done (rc=$?) ==="
} >> "$LOG" 2>&1
