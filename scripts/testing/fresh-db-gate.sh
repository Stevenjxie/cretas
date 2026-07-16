#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/docker-compose.fresh-db.yml"
BACKEND_DIR="$REPO_ROOT/backend/java/cretas-api"

FRESH_DB_PORT="${FRESH_DB_PORT:-55432}"
FRESH_DB_PROJECT="${FRESH_DB_PROJECT:-cretas-fresh-db}"
FRESH_DB_APP_PORT="${FRESH_DB_APP_PORT:-10019}"
FRESH_DB_KEEP="${FRESH_DB_KEEP:-0}"
STARTUP_TIMEOUT="${FRESH_DB_STARTUP_TIMEOUT:-300}"
DRY_RUN=0
BACKEND_PID=""
WORK_DIR="${TMPDIR:-/tmp}/cretas-fresh-db-${$}"
BACKEND_LOG="$WORK_DIR/backend.log"

usage() {
    cat <<'EOF'
Usage: bash scripts/testing/fresh-db-gate.sh [--keep] [--dry-run]

Starts a dedicated PostgreSQL 17 + pgvector container on localhost:55432,
runs the same Repository query startup gate as CI, then boots the real `pg`
profile against the empty database to exercise Flyway and full Spring/JPA
initialization. Containers are removed after the run unless --keep is used.

Options:
  --keep       Keep the database container after success or failure.
  --dry-run    Print the exact plan without invoking Docker or Maven.
  -h, --help   Show this help.

Local-only overrides:
  FRESH_DB_PORT=55432
  FRESH_DB_APP_PORT=10019
  FRESH_DB_PROJECT=cretas-fresh-db[-suffix]
  FRESH_DB_STARTUP_TIMEOUT=300
EOF
}

log() {
    printf '[fresh-db] %s\n' "$*"
}

fail() {
    printf '[fresh-db] ERROR: %s\n' "$*" >&2
    exit 1
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --keep) FRESH_DB_KEEP=1 ;;
        --dry-run) DRY_RUN=1 ;;
        -h|--help) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
    shift
done

validate_port_range() {
    local name="$1"
    local raw_value="$2"
    [[ "$raw_value" =~ ^[0-9]{1,5}$ ]] \
        || fail "$name must be an integer between 1024 and 65535"
    local value=$((10#$raw_value))
    [ "$value" -ge 1024 ] && [ "$value" -le 65535 ] \
        || fail "$name must be between 1024 and 65535"
}

port_is_available() {
    local port="$1"
    if command -v powershell.exe >/dev/null 2>&1; then
        powershell.exe -NoProfile -NonInteractive -Command \
            "\$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $port); try { \$listener.Start(); \$listener.Stop(); exit 0 } catch { exit 1 }" \
            >/dev/null 2>&1
        return $?
    fi
    if command -v python3 >/dev/null 2>&1; then
        python3 - "$port" <<'PY'
import socket
import sys

sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
try:
    sock.bind(("127.0.0.1", int(sys.argv[1])))
except OSError:
    sys.exit(1)
finally:
    sock.close()
PY
        return $?
    fi
    fail "cannot verify local port availability (PowerShell or python3 is required)"
}

require_port_available() {
    local name="$1"
    local port="$2"
    port_is_available "$port" \
        || fail "$name $port is already in use on 127.0.0.1; stop the existing process or choose another port"
}

validate_port_range "FRESH_DB_PORT" "$FRESH_DB_PORT"
validate_port_range "FRESH_DB_APP_PORT" "$FRESH_DB_APP_PORT"
[[ "$STARTUP_TIMEOUT" =~ ^[0-9]+$ ]] || fail "FRESH_DB_STARTUP_TIMEOUT must be numeric"
[ "$FRESH_DB_PORT" -ne 5432 ] || fail "port 5432 is intentionally forbidden; use the isolated default 55432"
case "$FRESH_DB_APP_PORT" in
    10010|10011|10020)
        fail "FRESH_DB_APP_PORT $FRESH_DB_APP_PORT is reserved; use an isolated local application port"
        ;;
esac
[ "$FRESH_DB_PORT" -ne "$FRESH_DB_APP_PORT" ] \
    || fail "FRESH_DB_PORT and FRESH_DB_APP_PORT must be different"
[[ "$FRESH_DB_PROJECT" =~ ^cretas-fresh-db(-[a-z0-9-]+)?$ ]] \
    || fail "FRESH_DB_PROJECT must be cretas-fresh-db or a safe cretas-fresh-db-* suffix"

COMPOSE=(docker compose -p "$FRESH_DB_PROJECT" -f "$COMPOSE_FILE")

run_maven() {
    if command -v mvn >/dev/null 2>&1; then
        mvn "$@"
    elif [ -x "$BACKEND_DIR/mvnw" ]; then
        "$BACKEND_DIR/mvnw" "$@"
    else
        fail "Maven is unavailable (install mvn or keep the repository Maven wrapper executable)"
    fi
}

terminate_process_tree() {
    local pid="$1"
    [ -n "$pid" ] || return 0
    if command -v cmd.exe >/dev/null 2>&1; then
        cmd.exe /d /c taskkill /PID "$pid" /T /F >/dev/null 2>&1 || true
        return 0
    fi
    if command -v pgrep >/dev/null 2>&1; then
        local child
        while IFS= read -r child; do
            [ -n "$child" ] && terminate_process_tree "$child"
        done < <(pgrep -P "$pid" 2>/dev/null || true)
    fi
    kill "$pid" >/dev/null 2>&1 || true
}

cleanup() {
    local rc=$?
    if [ -n "$BACKEND_PID" ]; then
        terminate_process_tree "$BACKEND_PID"
        wait "$BACKEND_PID" >/dev/null 2>&1 || true
        BACKEND_PID=""
    fi
    if [ "$DRY_RUN" -eq 0 ] && command -v docker >/dev/null 2>&1; then
        if [ "$FRESH_DB_KEEP" = "1" ]; then
            log "container kept: project=$FRESH_DB_PROJECT port=$FRESH_DB_PORT"
        else
            "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
            log "isolated database removed"
        fi
    fi
    if [ "$rc" -ne 0 ] && [ -f "$BACKEND_LOG" ]; then
        printf '\n[fresh-db] backend startup tail (%s):\n' "$BACKEND_LOG" >&2
        tail -120 "$BACKEND_LOG" >&2 || true
    fi
    if [ -d "$WORK_DIR" ]; then
        if [ "$FRESH_DB_KEEP" = "1" ]; then
            log "work directory kept: $WORK_DIR"
        else
            rm -rf -- "$WORK_DIR"
        fi
    fi
    return "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if [ "$DRY_RUN" -eq 1 ]; then
    cat <<EOF
[fresh-db] DRY RUN
compose project : $FRESH_DB_PROJECT
database        : pgvector/pgvector:pg17 on 127.0.0.1:$FRESH_DB_PORT (never 5432)
application     : http://127.0.0.1:$FRESH_DB_APP_PORT
cleanup         : $([ "$FRESH_DB_KEEP" = "1" ] && echo keep || echo remove)

1. docker compose down -v, then up a genuinely empty database
2. create vector extensions and the local-only smartbi database
3. mvn -B test -Dspring.profiles.active=test -Dtest='*RepositoryQueryValidationTest' -Dsurefire.failIfNoSpecifiedTests=false
4. mvn -B spring-boot:run -Dspring-boot.run.profiles=pg (database URL overridden to port $FRESH_DB_PORT)
5. require /api/mobile/health and a non-empty, successful flyway_schema_history
EOF
    exit 0
fi

command -v docker >/dev/null 2>&1 || fail "Docker CLI not found; install/start Docker Desktop and reopen Git Bash"
docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 is required (docker compose)"
command -v curl >/dev/null 2>&1 || fail "curl is required for the Spring health gate"
[ -f "$COMPOSE_FILE" ] || fail "missing $COMPOSE_FILE"
require_port_available "FRESH_DB_PORT" "$FRESH_DB_PORT"
require_port_available "FRESH_DB_APP_PORT" "$FRESH_DB_APP_PORT"
mkdir -p "$WORK_DIR"

export FRESH_DB_PORT
log "resetting dedicated Compose project $FRESH_DB_PROJECT (host port $FRESH_DB_PORT)"
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --force-recreate

log "waiting for PostgreSQL readiness"
ready=0
for _ in $(seq 1 60); do
    if "${COMPOSE[@]}" exec -T postgres pg_isready -U cretas_user -d cretas_db >/dev/null 2>&1; then
        ready=1
        break
    fi
    sleep 1
done
[ "$ready" -eq 1 ] || fail "PostgreSQL did not become ready within 60 seconds"

log "bootstrapping pgvector and SmartBI database (local placeholder credentials only)"
"${COMPOSE[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U cretas_user -d cretas_db <<'SQL'
CREATE EXTENSION IF NOT EXISTS vector;
CREATE USER smartbi_user WITH PASSWORD 'smartbi_local_only';
CREATE DATABASE smartbi_db OWNER smartbi_user;
SQL
"${COMPOSE[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U cretas_user -d smartbi_db \
    -c 'CREATE EXTENSION IF NOT EXISTS vector;'

log "running CI-equivalent Repository query startup gate"
(
    cd "$BACKEND_DIR"
    run_maven -B test \
        -Dspring.profiles.active=test \
        "-Dtest=*RepositoryQueryValidationTest" \
        -Dsurefire.failIfNoSpecifiedTests=false
)

DB_URL="jdbc:postgresql://127.0.0.1:${FRESH_DB_PORT}/cretas_db?currentSchema=public&stringtype=unspecified"
SMARTBI_URL="jdbc:postgresql://127.0.0.1:${FRESH_DB_PORT}/smartbi_db"
RUN_ARGS="--server.address=127.0.0.1 --server.port=${FRESH_DB_APP_PORT} --spring.datasource.url=${DB_URL} --spring.datasource.username=cretas_user --spring.datasource.password=cretas_local_only --smartbi.postgres.url=${SMARTBI_URL} --smartbi.postgres.username=smartbi_user --smartbi.postgres.password=smartbi_local_only --spring.jpa.show-sql=false --python-smartbi.enabled=false --python-error-analysis.enabled=false --python-classifier.enabled=false"
export DB_PASSWORD=cretas_local_only
export POSTGRES_SMARTBI_PASSWORD=smartbi_local_only
export JWT_SECRET=fresh_db_local_jwt_secret_not_for_remote_use

log "starting real pg profile for Flyway + full Spring/JPA gate"
require_port_available "FRESH_DB_APP_PORT" "$FRESH_DB_APP_PORT"
(
    cd "$BACKEND_DIR"
    run_maven -B spring-boot:run \
        -Dspring-boot.run.profiles=pg \
        "-Dspring-boot.run.arguments=$RUN_ARGS"
) >"$BACKEND_LOG" 2>&1 &
BACKEND_PID=$!

healthy=0
for _ in $(seq 1 "$STARTUP_TIMEOUT"); do
    if ! kill -0 "$BACKEND_PID" >/dev/null 2>&1; then
        wait "$BACKEND_PID" || true
        BACKEND_PID=""
        fail "Spring process exited before becoming healthy"
    fi
    if curl --silent --show-error --fail --max-time 2 \
        "http://127.0.0.1:${FRESH_DB_APP_PORT}/api/mobile/health" >/dev/null 2>&1 \
        && kill -0 "$BACKEND_PID" >/dev/null 2>&1 \
        && grep -q 'Started CretasBackendApplication' "$BACKEND_LOG"; then
        healthy=1
        break
    fi
    sleep 1
done
[ "$healthy" -eq 1 ] || fail "Spring health gate timed out after ${STARTUP_TIMEOUT}s"
kill -0 "$BACKEND_PID" >/dev/null 2>&1 \
    || fail "Spring process exited immediately after the health response"

log "verifying Flyway history on the fresh database"
MIGRATION_COUNT=$("${COMPOSE[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 \
    -U cretas_user -d cretas_db -tAc 'SELECT COUNT(*) FROM flyway_schema_history;')
FAILED_COUNT=$("${COMPOSE[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 \
    -U cretas_user -d cretas_db -tAc 'SELECT COUNT(*) FROM flyway_schema_history WHERE success = false;')
[[ "$MIGRATION_COUNT" =~ ^[0-9]+$ ]] && [ "$MIGRATION_COUNT" -gt 0 ] \
    || fail "Flyway history is missing or empty"
[ "$FAILED_COUNT" = "0" ] || fail "Flyway history contains $FAILED_COUNT failed migration(s)"

log "PASS: Repository gate + Flyway + full Spring/JPA startup (${MIGRATION_COUNT} migrations)"
