#!/usr/bin/env bash
# 两条路的合计层毛利对账 —— 形态 D 的闸。
#
# 同一个「合计毛利」有两份实现(日结走 generic_executor, 毛利问答走
# resolve_gross_margin 自带 SQL)。owner 2026-08-13 裁定: 抽不动就立闸钉住两份一致。
# ⛔ 两个数字都对外, 不一致时店长会问「为什么不一样」—— 最贵的形态 D。
#
# 形状抄 replay-equivalence-daily.sh(同一天同一租户各算一次, 比产出)。
# ⛔ 不阻断任何东西 —— 它是观测, 产出是台账 + 告警行, 给人看。
set -uo pipefail

PYDIR=/www/wwwroot/cretas/code/backend/python
LOG=/www/wwwroot/cretas/logs/margin-parity.log
ALERTS=/www/wwwroot/cretas/logs/margin-parity-alerts.log
LEDGER=/www/wwwroot/cretas/logs/margin-parity-ledger.jsonl

{
  echo "=== $(date '+%F %T') margin parity ==="
  cd "$PYDIR" || exit 1
  # shellcheck disable=SC1091
  source "$PYDIR/venv-current/bin/activate"

  set -a
  # shellcheck disable=SC1091
  source /www/wwwroot/cretas/.env.prod 2>/dev/null
  # shellcheck disable=SC1091
  source /www/wwwroot/cretas/.env.daily-close 2>/dev/null
  set +a

  # ⚠️ `.env.prod` 不含 POSTGRES_DB —— 只在 systemd unit 里。不补会落到测试库。
  svc_pid=$(systemctl show -p MainPID --value cretas-python.service 2>/dev/null)
  if [ -n "${svc_pid:-}" ] && [ "$svc_pid" != "0" ] && [ -r "/proc/$svc_pid/environ" ]; then
    while IFS= read -r -d '' kv; do
      case "$kv" in POSTGRES_*|INTERNAL_API_SECRET=*|DASHSCOPE_*) export "${kv?}" ;; esac
    done < "/proc/$svc_pid/environ"
  else
    echo "WARN: 拿不到活服务进程 environ (pid=${svc_pid:-none}) —— 库名可能落到默认值(测试库)"
  fi

  # ⛔ 先删上一次的产出 —— 不删的话台账会把上次的计数配上这次的 rc(实测踩过)。
  rm -f /tmp/margin_parity.json

  # 租户默认取日结名单的第一个; 没有就用 demo 租户。
  parity_factory="${PARITY_FACTORY:-${DAILY_CLOSE_FACTORIES%%,*}}"
  parity_factory="${parity_factory:-MOCK_REST}"

  out=$(PARITY_FACTORY="$parity_factory" PARITY_OUT=/tmp/margin_parity.json \
        PYTHONIOENCODING=utf-8 \
        python -X utf8 -u -m smartbi.scripts.margin_parity_probe 2>&1)
  rc=$?
  echo "$out"
  echo "=== done (rc=$rc) ==="

  # rc: 0 一致 / 1 不一致 / 2 有一条路没算出数(本次读数作废)
  # ⛔ 「两边都是 None」在数值上相等 —— 那不是一致, 是没量到。必须分开喊。
  if [ "$rc" -eq 2 ]; then
    echo "MARGIN PARITY INSTRUMENT DEAD $(date '+%F %T') — 有一条路没算出合计毛利, 本次读数作废, 见 $LOG" >> "$ALERTS"
  elif [ "$rc" -ne 0 ]; then
    echo "MARGIN PARITY BROKEN $(date '+%F %T') — 两条路的合计毛利不一致, 见 $LOG" >> "$ALERTS"
  fi

  if [ -r /tmp/margin_parity.json ]; then
    PARITY_RC="$rc" python -X utf8 -u -m smartbi.scripts.margin_parity_ledger >> "$LEDGER"
  fi
} >> "$LOG" 2>&1
