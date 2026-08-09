#!/bin/bash
# capability-watch.sh — Phase 4.5 observation period monitor for 数据织网 A spec.
#
# Runs lightweight health + latency probe on Python prod 8083 capability endpoint.
# Designed to be invoked from cron every 15 min during the 1-week observation
# period (per spec §9.2): "F001 + RES_3101_009 跑 1 周, 0 投诉 → 扩白名单".
#
# Outputs:
#   STDOUT — single status line for log aggregation, e.g. journald or file
#   exit 0 — all checks PASS
#   exit 1 — at least one ALERT condition triggered
#
# Cron usage (on 47.100.235.168):
#   crontab -e
#   */15 * * * * /www/wwwroot/cretas/capability-watch.sh >> /var/log/capability-watch.log 2>&1
#
# Or one-shot:
#   ssh root@47.100.235.168 'bash /www/wwwroot/cretas/capability-watch.sh'
#
# v1.0 (Apr 26 2026, Phase 4.5)

set -uo pipefail   # NOT -e: we want to collect failures, not exit at first

# ==================== Config ====================
PYTHON_HOST="${PYTHON_HOST:-localhost}"
PYTHON_PORT="${PYTHON_PORT:-8083}"
ENV_FILE="${CRETAS_ENV_FILE:-/www/wwwroot/cretas/.env.prod}"
if [ -z "${INTERNAL_SECRET:-}" ] && [ -r "$ENV_FILE" ]; then
    INTERNAL_SECRET="$(sed -n 's/^INTERNAL_API_SECRET=//p' "$ENV_FILE" | tail -1)"
fi
if [ -z "${INTERNAL_SECRET:-}" ]; then
    echo "ERROR: INTERNAL_SECRET is unset and $ENV_FILE is unavailable or incomplete" >&2
    exit 2
fi
PROBE_FACTORY="${PROBE_FACTORY:-RES_3101_009}"   # whitelisted real customer

# SLO thresholds (per spec §8.3, realistic UI load)
SLO_LATENCY_MS=200      # p95 target; single-request can be slower OK
ALERT_LATENCY_MS=500    # alert if single request > this
ERROR_WINDOW_MIN=15     # check journalctl for last N min

TS="$(date '+%Y-%m-%dT%H:%M:%S')"
ALERT=0
SUMMARY=""

emit() {
    echo "[$TS] $*"
}

# ==================== Probe 1: health endpoint ====================
HEALTH_HTTP=$(curl -sS -o /dev/null -w "%{http_code}" -m 5 \
    "http://${PYTHON_HOST}:${PYTHON_PORT}/health" 2>/dev/null || echo "000")
if [ "$HEALTH_HTTP" != "200" ]; then
    emit "ALERT health: HTTP $HEALTH_HTTP (expected 200)"
    ALERT=1
    SUMMARY="${SUMMARY}health=$HEALTH_HTTP "
else
    SUMMARY="${SUMMARY}health=ok "
fi

# ==================== Probe 2: capability endpoint (real factory) ====================
T0=$(date +%s%3N)
CAP_RESP=$(curl -sS -m 5 \
    -H "X-Internal-Secret: ${INTERNAL_SECRET}" \
    -H "X-Factory-Id: ${PROBE_FACTORY}" \
    "http://${PYTHON_HOST}:${PYTHON_PORT}/api/smartbi/capability/${PROBE_FACTORY}" 2>/dev/null || echo "")
T1=$(date +%s%3N)
LATENCY_MS=$((T1 - T0))

if [ -z "$CAP_RESP" ]; then
    emit "ALERT capability: empty response (curl failed)"
    ALERT=1
    SUMMARY="${SUMMARY}cap=fail "
else
    # Parse via python (server has python3)
    FIELD_COUNT=$(echo "$CAP_RESP" | python3 -c \
        'import json,sys; d=json.load(sys.stdin); print(len(d.get("available_fields",[])))' \
        2>/dev/null || echo "-1")
    SAT_COUNT=$(echo "$CAP_RESP" | python3 -c \
        'import json,sys; d=json.load(sys.stdin); print(sum(1 for v in d.get("template_status",{}).values() if v.get("satisfied") is True))' \
        2>/dev/null || echo "-1")

    if [ "$FIELD_COUNT" = "-1" ] || [ "$SAT_COUNT" = "-1" ]; then
        emit "ALERT capability: response not parseable as expected JSON"
        ALERT=1
        SUMMARY="${SUMMARY}cap=parse-fail "
    else
        SUMMARY="${SUMMARY}cap=${FIELD_COUNT}f/${SAT_COUNT}s "
    fi

    # Latency check
    if [ "$LATENCY_MS" -gt "$ALERT_LATENCY_MS" ]; then
        emit "ALERT capability: latency ${LATENCY_MS}ms > ${ALERT_LATENCY_MS}ms"
        ALERT=1
    fi
    SUMMARY="${SUMMARY}lat=${LATENCY_MS}ms "

    # Soft SLO note (not alerting, just tracked)
    if [ "$LATENCY_MS" -gt "$SLO_LATENCY_MS" ]; then
        SUMMARY="${SUMMARY}slo=warn "
    fi
fi

# ==================== Probe 3: gate semantics (non-whitelisted should 503) ====================
GATE_HTTP=$(curl -sS -o /dev/null -w "%{http_code}" -m 5 \
    -H "X-Internal-Secret: ${INTERNAL_SECRET}" \
    -H "X-Factory-Id: F999_FAKE_NOT_WHITELISTED" \
    "http://${PYTHON_HOST}:${PYTHON_PORT}/api/smartbi/capability/F999_FAKE_NOT_WHITELISTED" \
    2>/dev/null || echo "000")
if [ "$GATE_HTTP" != "503" ]; then
    emit "ALERT gate: non-whitelisted returned $GATE_HTTP (expected 503 from gradual rollout gate)"
    ALERT=1
    SUMMARY="${SUMMARY}gate=$GATE_HTTP "
else
    SUMMARY="${SUMMARY}gate=ok "
fi

# ==================== Probe 3b: LLM 供应商池 (餐饮 T3 规划器的地基) ====================
# 🔴 2026-08-09 补: REVIEW 槽 20 个 (账号,模型) 曾**全部**被配额耗尽/熔断吃掉,
#    T3 规划器整层 fail-closed, 而 66.5% 的餐饮提问走这一层。日志显示这个状态
#    从 08-03 起断续出现、**已 6 天没有任何告警** —— 直到有人手工翻日志才发现。
# ⛔ 判定用路由器自己的三道闸(白名单/熔断/配额退避), 不另写一套 ——
#    另写一套迟早漂移, 那时告警说「健康」而用户在收 fail-closed 文案。
POOL_OUT=$(cd /www/wwwroot/cretas/code/backend/python 2>/dev/null \
    && set -a && . /www/wwwroot/cretas/.env.prod 2>/dev/null && set +a \
    && ./venv-current/bin/python -m smartbi.scripts.llm_pool_health --slot review --min 2 2>&1)
POOL_RC=$?
POOL_USABLE=$(printf '%s' "$POOL_OUT" | grep -oE 'usable=[0-9]+/[0-9]+' | head -1)
if [ "$POOL_RC" -ne 0 ]; then
    emit "ALERT llm_pool: ${POOL_OUT}"
    ALERT=1
fi
SUMMARY="${SUMMARY}llm_pool=${POOL_USABLE:-unknown} "

# ==================== Probe 4: error rate from journald (last 15 min) ====================
# grep -c returns exit 1 when no matches but still prints "0" — don't `|| echo "0"`
# because that creates "0\n0". Just suppress the exit code and trust grep's count.
ERR_COUNT=$(journalctl -u cretas-python --since "${ERROR_WINDOW_MIN} minutes ago" --no-pager 2>/dev/null \
    | grep -ciE "capability.*error|capability.*exception|exception.*capability" || true)
ERR_COUNT="${ERR_COUNT:-0}"
if [ "$ERR_COUNT" -gt 5 ]; then
    emit "ALERT errors: $ERR_COUNT capability-related errors in journald last ${ERROR_WINDOW_MIN}min"
    ALERT=1
fi
SUMMARY="${SUMMARY}errs=${ERR_COUNT}/${ERROR_WINDOW_MIN}min "

# ==================== Final emit ====================
STATUS="OK"
[ "$ALERT" -eq 1 ] && STATUS="ALERT"
emit "$STATUS $SUMMARY"

exit "$ALERT"
