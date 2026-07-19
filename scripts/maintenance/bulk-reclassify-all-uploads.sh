#!/bin/bash
# One-shot: reclassify all COMPLETED uploads through Python γ-unified classifier.
#
# Usage:
#   # Runs on server (recommended — uses localhost, no firewall concerns):
#   ssh root@47.100.235.168 'bash -s' < scripts/maintenance/bulk-reclassify-all-uploads.sh
#
#   # Or upload + run:
#   scp scripts/maintenance/bulk-reclassify-all-uploads.sh root@47.100.235.168:/tmp/
#   ssh root@47.100.235.168 'bash /tmp/bulk-reclassify-all-uploads.sh'
#
# Options (env vars):
#   TARGET=test               # hit Python 8084 + smartbi_db (default: prod = 8083 + smartbi_prod_db)
#   REMATERIALIZE=true        # also re-run templates (default: false — scan-only)
#   LIMIT=N                   # stop after N uploads (default: unlimited)
#   DRY_RUN=1                 # print what would be called, don't execute
#
# Output: per-upload line + end-of-run summary (total, changed, errored).

set -euo pipefail

TARGET="${TARGET:-prod}"
REMATERIALIZE="${REMATERIALIZE:-false}"
LIMIT="${LIMIT:-}"
DRY_RUN="${DRY_RUN:-0}"

if [ "$TARGET" = "test" ]; then
    PY_PORT=8084
    DB_NAME=smartbi_db
else
    PY_PORT=8083
    DB_NAME=smartbi_prod_db
fi

# Load the internal secret from the target environment; fail closed when absent.
ENV_FILE="${CRETAS_ENV_FILE:-/www/wwwroot/cretas/.env.${TARGET}}"
if [ ! -r "$ENV_FILE" ]; then
    echo "ERROR: missing readable environment file: $ENV_FILE" >&2
    exit 1
fi
SECRET="$(sed -n 's/^INTERNAL_API_SECRET=//p' "$ENV_FILE" | tail -1)"
if [ -z "$SECRET" ]; then
    echo "ERROR: INTERNAL_API_SECRET is unset in $ENV_FILE" >&2
    exit 1
fi

echo "=========================================="
echo "Bulk reclassify — target=$TARGET port=$PY_PORT db=$DB_NAME"
echo "rematerialize=$REMATERIALIZE dry_run=$DRY_RUN"
echo "=========================================="

LIMIT_SQL=""
if [ -n "$LIMIT" ]; then LIMIT_SQL="LIMIT $LIMIT"; fi

# id | factory_id  — tab-separated, one per line
mapfile -t UPLOADS < <(
    sudo -u postgres psql -d "$DB_NAME" -t -A -F $'\t' -c "
        SELECT id, factory_id
          FROM smart_bi_pg_excel_uploads
         WHERE upload_status = 'COMPLETED'
         ORDER BY id
         $LIMIT_SQL;
    "
)

echo "Found ${#UPLOADS[@]} COMPLETED uploads"
echo ""

total=0
changed=0
unchanged=0
errored=0
t_start=$(date +%s)

for line in "${UPLOADS[@]}"; do
    [ -z "$line" ] && continue
    id="$(printf '%s' "$line" | cut -f1)"
    factory="$(printf '%s' "$line" | cut -f2)"
    total=$((total + 1))

    if [ "$DRY_RUN" = "1" ]; then
        printf 'DRY upload=%-5s factory=%s\n' "$id" "$factory"
        continue
    fi

    url="http://localhost:${PY_PORT}/api/smartbi/analytics/reclassify/${id}?rematerialize=${REMATERIALIZE}"
    resp="$(curl -s -m 60 -X POST \
                -H "X-Internal-Secret: $SECRET" \
                -H "X-Factory-Id: $factory" \
                -d '{}' \
                "$url" 2>/dev/null || true)"

    if [ -z "$resp" ]; then
        printf 'upload=%-5s factory=%-16s ERROR no-response\n' "$id" "$factory"
        errored=$((errored + 1))
        continue
    fi

    # Parse fields using jq if available, else fall back to python
    if command -v jq >/dev/null 2>&1; then
        success=$(printf '%s' "$resp" | jq -r '.success // false' 2>/dev/null)
        c_count=$(printf '%s' "$resp" | jq -r '.changes_count // "null"' 2>/dev/null)
        wall=$(printf '%s' "$resp" | jq -r '.wall_ms // "null"' 2>/dev/null)
    else
        success=$(printf '%s' "$resp" | python3 -c "import sys,json; d=json.loads(sys.stdin.read()); print(d.get('success',False))" 2>/dev/null || echo "False")
        c_count=$(printf '%s' "$resp" | python3 -c "import sys,json; d=json.loads(sys.stdin.read()); print(d.get('changes_count','null'))" 2>/dev/null || echo "null")
        wall=$(printf '%s' "$resp" | python3 -c "import sys,json; d=json.loads(sys.stdin.read()); print(d.get('wall_ms','null'))" 2>/dev/null || echo "null")
    fi

    if [ "$success" != "true" ] && [ "$success" != "True" ]; then
        msg=$(printf '%s' "$resp" | head -c 160 | tr -d '\n')
        printf 'upload=%-5s factory=%-16s ERROR %s\n' "$id" "$factory" "$msg"
        errored=$((errored + 1))
        continue
    fi

    if [ "$c_count" = "0" ]; then
        unchanged=$((unchanged + 1))
    else
        changed=$((changed + 1))
        printf 'upload=%-5s factory=%-16s changes=%s wall=%sms\n' "$id" "$factory" "$c_count" "$wall"
    fi
done

t_elapsed=$(( $(date +%s) - t_start ))

echo ""
echo "=========================================="
echo "Summary ($t_elapsed seconds)"
echo "  total     : $total"
echo "  changed   : $changed"
echo "  unchanged : $unchanged"
echo "  errored   : $errored"
echo "=========================================="

if [ "$REMATERIALIZE" = "false" ] && [ "$changed" -gt 0 ]; then
    echo ""
    echo "Scan-only pass done — $changed upload(s) have classification changes."
    echo "To re-run templates for those, re-invoke with REMATERIALIZE=true"
    echo "(warning: rematerialize re-runs analytics for EVERY upload, not just changed ones)."
fi
