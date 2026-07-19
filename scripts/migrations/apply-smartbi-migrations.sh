#!/usr/bin/env bash
# Apply pending smartbi V*.sql migrations.
#
# Spec: docs/superpowers/specs/2026-05-07-smartbi-migration-runner-spec.md
# Trigger: task #30 — 8 个 data fabric C 系列 migrations 当初部署漏跑 prod
# Tracker table: smartbi_migrations (PK = filename, see PR-A rationale)
#
# Usage:
#   apply-smartbi-migrations.sh --env <test|prod|all> [--dry-run] [--target VERSION]
#                              [--migs-dir PATH] [--db-name NAME]
#
# Options:
#   --env       Target environment (test=smartbi_db, prod=smartbi_prod_db, all=both)
#   --dry-run   Wrap each migration in BEGIN/ROLLBACK; verifies SQL parses + DDL
#               applies cleanly without persisting changes. Tracker untouched.
#   --target    Apply only files with version <= VERSION (inclusive). VERSION
#               must name an existing VYYYYMMDD_NN file. Fails if the tracker
#               already contains a migration above the target. Default: all.
#   --migs-dir  Override migrations dir (default: backend/python/smartbi/database/migrations
#               relative to script). Used by test-runner.sh.
#   --db-name   Override DB name (default mapped from --env). Used by test-runner.sh
#               for isolated test DB; bypasses --env mapping.
#
# Exit codes:
#   0  All up-to-date or applied successfully
#   1  Checksum mismatch / SQL error / DB unreachable / unexpected failure
#   2  Usage error / missing required arg

set -euo pipefail

ENV=""
DRY_RUN=0
TARGET=""
MIGS_DIR_OVERRIDE=""
DB_NAME_OVERRIDE=""

usage() {
    sed -n '3,18p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit "${1:-2}"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --env)        ENV="${2:-}"; shift 2 ;;
        --dry-run)    DRY_RUN=1; shift ;;
        --target)     TARGET="${2:-}"; shift 2 ;;
        --migs-dir)   MIGS_DIR_OVERRIDE="${2:-}"; shift 2 ;;
        --db-name)    DB_NAME_OVERRIDE="${2:-}"; shift 2 ;;
        -h|--help)    usage 0 ;;
        *)            echo "Unknown arg: $1" >&2; usage 2 ;;
    esac
done

[ -n "$ENV" ] || { echo "--env required" >&2; usage 2; }
if [ -n "$TARGET" ] && [[ ! "$TARGET" =~ ^V[0-9]{8}_[0-9]{2}$ ]]; then
    echo "--target must match VYYYYMMDD_NN" >&2
    exit 2
fi
if [ -n "$TARGET" ] && [ "$ENV" = "all" ]; then
    echo "--target cannot be combined with --env all; migrate and verify one environment at a time" >&2
    exit 2
fi

# Resolve migrations dir relative to script (mirrors backfill-applied.sh layout).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -n "$MIGS_DIR_OVERRIDE" ]; then
    MIGS_DIR="$MIGS_DIR_OVERRIDE"
else
    MIGS_DIR="$(cd "$SCRIPT_DIR/../../backend/python/smartbi/database/migrations" && pwd)"
fi

if [ ! -d "$MIGS_DIR" ]; then
    echo "[migrations] migrations dir not found: $MIGS_DIR" >&2
    exit 1
fi

# Map --env to DB name (or use override for tests).
map_db() {
    local env="$1"
    if [ -n "$DB_NAME_OVERRIDE" ]; then
        echo "$DB_NAME_OVERRIDE"
        return
    fi
    case "$env" in
        test) echo "smartbi_db" ;;
        prod) echo "smartbi_prod_db" ;;
        *)    echo "" ;;
    esac
}

# Run psql against db with --tuples-only --no-align for clean stdout capture.
psql_query() {
    local db="$1"; shift
    sudo -u postgres psql -d "$db" -v ON_ERROR_STOP=1 -tA "$@"
}

# Run psql with file/stdin SQL and ON_ERROR_STOP. Output passes through.
psql_exec() {
    local db="$1"; shift
    sudo -u postgres psql -d "$db" -v ON_ERROR_STOP=1 "$@"
}

apply_one_env() {
    local env="$1"
    local db
    db="$(map_db "$env")"
    [ -n "$db" ] || { echo "[migrations] unknown env: $env" >&2; return 1; }

    echo "[migrations] target env=$env, db=$db"
    [ "$DRY_RUN" = "1" ] && echo "[migrations] DRY RUN — changes will be rolled back"
    [ -n "$TARGET" ]    && echo "[migrations] applying up to version <= $TARGET (inclusive)"

    # Sanity: tracker table exists. If not, abort with actionable message.
    local has_tracker
    has_tracker=$(psql_query "$db" -c "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'smartbi_migrations'" || echo "0")
    if [ "$has_tracker" != "1" ]; then
        echo "[migrations] tracker table missing in $db." >&2
        echo "[migrations] Bootstrap first:" >&2
        echo "  sudo -u postgres psql -d $db -f $MIGS_DIR/V20260507_01__smartbi_migrations_tracker.sql" >&2
        echo "  bash $SCRIPT_DIR/backfill-applied.sh $env" >&2
        return 1
    fi

    local total=0 applied=0 skipped=0 dryran=0
    local files
    # shellcheck disable=SC2207
    files=( $(ls -1 "$MIGS_DIR"/V*.sql 2>/dev/null | sort) )
    echo "[migrations] discovered ${#files[@]} V-prefix files"

    if [ -n "$TARGET" ]; then
        local target_present=0 file_version
        for file in "${files[@]}"; do
            file_version=$(basename "$file" | grep -oE '^V[0-9]{8}_[0-9]{2}' || true)
            if [ "$file_version" = "$TARGET" ]; then
                target_present=1
                break
            fi
        done
        if [ "$target_present" != "1" ]; then
            echo "[migrations] FAIL: target does not exist in migrations dir: $TARGET" >&2
            return 1
        fi

        local ahead_filename
        ahead_filename=$(psql_query "$db" -c \
            "SELECT filename FROM smartbi_migrations WHERE version > '$TARGET' ORDER BY version, filename LIMIT 1")
        if [ -n "$ahead_filename" ]; then
            echo "[migrations] FAIL: database is already ahead of target $TARGET ($ahead_filename)" >&2
            echo "[migrations] Refusing to treat a lower target as a schema rollback." >&2
            return 1
        fi
    fi

    for file in "${files[@]}"; do
        total=$((total + 1))
        local fname ver checksum
        fname=$(basename "$file")
        ver=$(echo "$fname" | grep -oE '^V[0-9]+_[0-9]+' || true)
        if [ -z "$ver" ]; then
            echo "[migrations] skip $fname — cannot parse version" >&2
            continue
        fi

        # --target: skip files with version > TARGET (string compare works for VYYYYMMDD_NN).
        if [ -n "$TARGET" ] && [[ "$ver" > "$TARGET" ]]; then
            continue
        fi

        checksum=$(sha256sum "$file" | cut -d' ' -f1)

        local existing
        existing=$(psql_query "$db" -c "SELECT checksum FROM smartbi_migrations WHERE filename = '$fname'")
        if [ -n "$existing" ]; then
            if [ "$existing" != "$checksum" ]; then
                echo "[migrations] FAIL: $fname checksum mismatch" >&2
                echo "[migrations]   tracker: $existing" >&2
                echo "[migrations]   on disk: $checksum" >&2
                echo "[migrations] An applied migration was edited. Restore the file or" >&2
                echo "[migrations] manually update tracker after verifying intent." >&2
                return 1
            fi
            skipped=$((skipped + 1))
            continue
        fi

        # New migration → apply atomically.
        local file_content
        file_content="$(cat "$file")"
        local start_ms end_ms duration

        # Normalize psql exit codes (3 = ON_ERROR_STOP triggered) to spec exit 1.
        local apply_status=0

        if [ "$DRY_RUN" = "1" ]; then
            echo "[migrations] [dry-run] would apply $fname"
            start_ms=$(date +%s%3N)
            # Quoted-EOF heredoc would suppress $var expansion, but we WANT
            # $file_content to expand. Plain EOF is correct here.
            psql_exec "$db" <<EOF || apply_status=$?
BEGIN;
$file_content
ROLLBACK;
EOF
            if [ "$apply_status" -ne 0 ]; then
                echo "[migrations] FAIL: dry-run apply of $fname returned psql exit $apply_status" >&2
                return 1
            fi
            end_ms=$(date +%s%3N)
            duration=$((end_ms - start_ms))
            echo "[migrations] [dry-run] $ver would apply in ${duration}ms (rolled back)"
            dryran=$((dryran + 1))
        else
            echo "[migrations] applying $fname ..."
            start_ms=$(date +%s%3N)
            psql_exec "$db" <<EOF || apply_status=$?
BEGIN;
$file_content
INSERT INTO smartbi_migrations (filename, version, checksum)
    VALUES ('$fname', '$ver', '$checksum');
COMMIT;
EOF
            if [ "$apply_status" -ne 0 ]; then
                echo "[migrations] FAIL: $fname application returned psql exit $apply_status" >&2
                echo "[migrations] Transaction rolled back. Tracker NOT updated for $fname." >&2
                return 1
            fi
            end_ms=$(date +%s%3N)
            duration=$((end_ms - start_ms))
            # Best-effort wall-clock duration record. Outside the apply tx so a
            # failure here doesn't undo the migration.
            psql_exec "$db" -q -c "UPDATE smartbi_migrations SET duration_ms = $duration WHERE filename = '$fname'" >/dev/null || true
            echo "[migrations] $ver applied in ${duration}ms"
            applied=$((applied + 1))
        fi
    done

    if [ "$DRY_RUN" = "1" ]; then
        echo "[migrations] [dry-run] would-apply: $dryran, already-applied (skipped): $skipped"
    else
        if [ "$applied" -eq 0 ]; then
            echo "[migrations] all up-to-date (skipped $skipped)"
        else
            echo "[migrations] applied: $applied, skipped: $skipped"
        fi
    fi
}

case "$ENV" in
    test|prod)
        apply_one_env "$ENV"
        ;;
    all)
        apply_one_env test
        apply_one_env prod
        ;;
    *)
        echo "[migrations] --env must be one of: test, prod, all" >&2
        exit 2
        ;;
esac
