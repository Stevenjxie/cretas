#!/bin/bash
# PostgreSQL Daily Backup Script
# Cron: 0 3 * * * /www/wwwroot/cretas/scripts/backup-db.sh >> /www/wwwroot/cretas/logs/backup.log 2>&1

set -euo pipefail

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_DIR="${BACKUP_DIR:-/www/wwwroot/cretas/backups}"
# 2 天 (2026-07-29 Steve 拍板, 原 14 天): 每日三库全量约 690MB, 14 天 = 11G,
# 是 99G 盘的第二大占用。⚠️ 回滚窗口因此只有 2 天 —— 周五出的问题周一才发现时,
# 每日备份已经没了; 那种情况只能依赖手工迁移前快照 (*.dump, 不受本保留策略清理)。
RETENTION_DAYS="${RETENTION_DAYS:-2}"
LOG_PREFIX="[backup $(date '+%Y-%m-%d %H:%M:%S')]"

mkdir -p "$BACKUP_DIR"

echo "$LOG_PREFIX Starting database backup..."

# Main database (cretas_prod_db)
echo "$LOG_PREFIX Backing up cretas_prod_db..."
pg_dump -h localhost -U cretas_user cretas_prod_db 2>/dev/null | gzip > "$BACKUP_DIR/cretas_prod_db_$TIMESTAMP.sql.gz"
MAIN_SIZE=$(du -sh "$BACKUP_DIR/cretas_prod_db_$TIMESTAMP.sql.gz" | cut -f1)
echo "$LOG_PREFIX cretas_prod_db backup complete: $MAIN_SIZE"

# SmartBI database (smartbi_prod_db)
echo "$LOG_PREFIX Backing up smartbi_prod_db..."
pg_dump -h localhost -U smartbi_user smartbi_prod_db 2>/dev/null | gzip > "$BACKUP_DIR/smartbi_prod_db_$TIMESTAMP.sql.gz"
SMARTBI_SIZE=$(du -sh "$BACKUP_DIR/smartbi_prod_db_$TIMESTAMP.sql.gz" | cut -f1)
echo "$LOG_PREFIX smartbi_prod_db backup complete: $SMARTBI_SIZE"

# Test databases (optional, smaller)
if pg_dump -h localhost -U cretas_user cretas_db 2>/dev/null | gzip > "$BACKUP_DIR/cretas_db_test_$TIMESTAMP.sql.gz"; then
    echo "$LOG_PREFIX cretas_db (test) backup complete"
else
    echo "$LOG_PREFIX cretas_db (test) backup skipped (not available)"
    rm -f "$BACKUP_DIR/cretas_db_test_$TIMESTAMP.sql.gz"
fi

# Cleanup old backups
DELETED=$(find "$BACKUP_DIR" -name "*.sql.gz" -mtime +$RETENTION_DAYS -delete -print | wc -l)
echo "$LOG_PREFIX Cleaned up $DELETED old backup files (retention: ${RETENTION_DAYS} days)"

# Verify backup integrity (check file size > 0)
for f in "$BACKUP_DIR"/*_$TIMESTAMP.sql.gz; do
    if [ -f "$f" ] && [ ! -s "$f" ]; then
        echo "$LOG_PREFIX WARNING: Empty backup file: $f"
    fi
done

echo "$LOG_PREFIX Backup complete. Files in $BACKUP_DIR:"
ls -lh "$BACKUP_DIR"/*_$TIMESTAMP.sql.gz 2>/dev/null || echo "$LOG_PREFIX No backup files found for this run"
echo ""
