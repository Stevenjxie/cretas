#!/usr/bin/env bash
set -euo pipefail

TARGET=""
ENVIRONMENT=""
BACKEND_MARKER=""
WEB_MARKER=""

usage() {
    echo "Usage: verify-release.sh --target backend|web-admin|all --env prod|test [--backend-marker TEXT] [--web-marker TEXT]"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --target)
            [[ $# -ge 2 ]] || { usage >&2; exit 2; }
            TARGET="$2"
            shift 2
            ;;
        --env)
            [[ $# -ge 2 ]] || { usage >&2; exit 2; }
            ENVIRONMENT="$2"
            shift 2
            ;;
        --backend-marker)
            [[ $# -ge 2 ]] || { usage >&2; exit 2; }
            BACKEND_MARKER="$2"
            shift 2
            ;;
        --web-marker)
            [[ $# -ge 2 ]] || { usage >&2; exit 2; }
            WEB_MARKER="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

[[ "$TARGET" =~ ^(backend|web-admin|all)$ ]] || { usage >&2; exit 2; }
[[ "$ENVIRONMENT" =~ ^(prod|test)$ ]] || { usage >&2; exit 2; }

validate_marker() {
    local marker="$1"
    [[ "$marker" != *"'"* && "$marker" != *$'\n'* && "$marker" != *$'\r'* ]] || {
        echo "Marker cannot contain quotes or line breaks" >&2
        exit 2
    }
}

validate_marker "$BACKEND_MARKER"
validate_marker "$WEB_MARKER"

BACKEND_HOST="${CRETAS_BACKEND_HOST:-root@47.100.235.168}"
GATEWAY_HOST="${CRETAS_GATEWAY_HOST:-root@139.196.165.140}"
DEPLOYED_JAR_PATH="${CRETAS_BACKEND_JAR:-/www/wwwroot/cretas/aims-0.0.1-SNAPSHOT.jar}"
UPSTREAM_FILE="${CRETAS_UPSTREAM_FILE:-/www/server/panel/vhost/nginx/_upstream_cretas.conf}"

if [[ "$ENVIRONMENT" == "prod" ]]; then
    WEB_URL="${CRETAS_WEB_URL:-https://admin.cretaceousfuture.com}"
    WEB_ASSET_DIR="${CRETAS_WEB_ASSET_DIR:-/www/wwwroot/web-admin/assets}"
else
    WEB_URL="${CRETAS_WEB_URL:-http://139.196.165.140:8097}"
    WEB_ASSET_DIR="${CRETAS_WEB_ASSET_DIR:-/www/wwwroot/web-admin-test/assets}"
fi

resolve_prod_slot() {
    local active_port
    active_port="$(ssh "$GATEWAY_HOST" \
        "grep -E '^[[:space:]]*server[[:space:]]+47\\.100\\.235\\.168:(10010|10020);' '$UPSTREAM_FILE' | grep -oE '(10010|10020)' | sort -u")"
    case "$active_port" in
        10010) printf '%s\t%s\t%s\n' blue 10010 cretas-backend ;;
        10020) printf '%s\t%s\t%s\n' green 10020 cretas-backend-green ;;
        *)
            echo "Unable to resolve active production slot from $UPSTREAM_FILE" >&2
            return 1
            ;;
    esac
}

check_backend() {
    local slot port service
    if [[ "$ENVIRONMENT" == "prod" ]]; then
        IFS=$'\t' read -r slot port service < <(resolve_prod_slot)
    else
        slot="test"
        port="${CRETAS_TEST_BACKEND_PORT:-10011}"
        service="${CRETAS_TEST_BACKEND_SERVICE:-cretas-backend-test}"
    fi

    printf 'BACKEND_SLOT=%s\n' "$slot"
    printf 'BACKEND_PORT=%s\n' "$port"
    printf 'BACKEND_UPSTREAM=%s\n' "47.100.235.168:$port"
    printf 'BACKEND_SERVICE=%s\n' "$service"
    # 这三项检查都落在同一台主机上, 但原先分三次 ssh, 每次都要付一次跨境握手
    # (实测约 3.9s)。合并成一次连接后在远端顺序执行, 用不同退出码区分是哪一项
    # 失败 —— 比原先 set -e 静默中止更容易定位。
    local marker_check="" rc=0
    if [[ -n "$BACKEND_MARKER" ]]; then
        marker_check="unzip -p '$DEPLOYED_JAR_PATH' | grep -aFq -- '$BACKEND_MARKER' || exit 12"
    fi

    ssh "$BACKEND_HOST" "
        systemctl is-active '$service' | grep -qx active || exit 10
        curl -fsS --max-time 10 http://localhost:$port/api/mobile/health >/dev/null || exit 11
        $marker_check
    " || {
        rc=$?
        case "$rc" in
            10) echo "BACKEND_SERVICE_NOT_ACTIVE=$service" >&2 ;;
            11) echo "BACKEND_HEALTH=fail" >&2 ;;
            12) echo "BACKEND_MARKER=fail" >&2 ;;
            *)  echo "BACKEND_PROBE_FAILED rc=$rc" >&2 ;;
        esac
        return "$rc"
    }

    printf 'BACKEND_HEALTH=pass\n'
    # 注意: 这里必须用 if 而不是 `[[ ... ]] && printf`。后者在 marker 为空时整条
    # 语句返回 1, 在 set -e 下会让函数直接失败 —— 一个"没配 marker"就把发布判死。
    if [[ -n "$BACKEND_MARKER" ]]; then
        printf 'BACKEND_MARKER=pass\n'
    fi
}

check_web_admin() {
    local http
    http="$(curl -fsS -o /dev/null -w '%{http_code}' --max-time 15 "$WEB_URL")"
    [[ "$http" == "200" ]] || {
        echo "WEB_HTTP=$http" >&2
        return 1
    }
    printf 'WEB_HTTP=%s\n' "$http"

    if [[ -n "$WEB_MARKER" ]]; then
        ssh "$GATEWAY_HOST" "grep -R -Fq --include='*.js' -- '$WEB_MARKER' '$WEB_ASSET_DIR'"
        printf 'WEB_MARKER=pass\n'
    fi
}

case "$TARGET" in
    backend) check_backend ;;
    web-admin) check_web_admin ;;
    all)
        check_backend
        check_web_admin
        ;;
esac
