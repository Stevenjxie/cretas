#!/usr/bin/env bash
# LLM provider 每日体检 —— A5。两件事，一次跑完：
#
#   ① `deepseek_spend_alert`      —— 昨天花了多少（阈值按**金额** $1/天，⛔ 不按次数）
#   ② `minimal_safe_set_liveness` —— 兜底最小集里那几个，**今天还能不能应答**
#
# ## 为什么②必须天天跑
#
# `_MINIMAL_SAFE_SET` 是**所有链都挂了之后**的地板。地板坏了最难发现 ——
# 因为它平时不承载流量，而它坏的那一天正是最需要它的那一天。
# 2026-08-13 实测过一次同形：跑道最长的模型昨天还双证，今天全 403。
# ⇒ **到期日不是存活性。** 只有真发一次请求才知道。
#
# ## rc 三态（硬约束 4）—— 这是本脚本的重点
#
#   rc=0  没问题
#   rc=1  有问题（读数有效，且指向缺陷）：花超了 / 有候选答不了
#   rc=2  **这次没量到东西**：拿不到 key / 全部超时 / 探针自己崩了
#
# 🔴 rc=2 必须**用与 rc=1 不同的措辞**单独告警。
#    两态跑批会把「没量到」折叠进「没问题」——一个连不上的跑批会安静地天天绿，
#    而它一个样本都没看过。本仓有先例：52 断言的回归电池随租户收敛一起死了 4 天。
#
# 🔴 而且「探针没跑到做决定那一步」要与「决定不告警」分开：
#    `[ -s file ]` 里，「文件不存在」和「决定不喊」长得**一模一样**。
#    ⇒ 「没喊」有两种：决定不喊 / 没能做决定。**后者必须喊。**
set -uo pipefail

PYDIR=/www/wwwroot/cretas/code/backend/python
LOG=/www/wwwroot/cretas/logs/llm-provider-health.log
ALERTS=/www/wwwroot/cretas/logs/llm-provider-health-alerts.log
LEDGER=/www/wwwroot/cretas/logs/llm-provider-health-ledger.jsonl

{
  echo "=== $(date '+%F %T') llm provider health ==="
  cd "$PYDIR" || exit 1
  # shellcheck disable=SC1091
  source "$PYDIR/venv-current/bin/activate"

  set -a
  # shellcheck disable=SC1091
  source /www/wwwroot/cretas/.env.prod 2>/dev/null
  set +a
  # ⚠️ 库名/部分 key 只在 systemd unit 里，不在 .env.prod —— 问活进程要（形态 A′）。
  svc_pid=$(systemctl show -p MainPID --value cretas-python.service 2>/dev/null)
  if [ -n "${svc_pid:-}" ] && [ "$svc_pid" != "0" ] && [ -r "/proc/$svc_pid/environ" ]; then
    while IFS= read -r -d '' kv; do
      case "$kv" in POSTGRES_*|LLM_*|DASHSCOPE_*|INTERNAL_API_SECRET=*) export "${kv?}" ;; esac
    done < "/proc/$svc_pid/environ"
  else
    echo "WARN: 拿不到活服务进程 environ (pid=${svc_pid:-none})"
  fi

  # ⛔ 先删上一次的产出 —— 只要有一条路径不写文件，台账就会把**上一次的计数**
  #    配上**这一次的 rc**，而那种行格式合法、字段齐全、看起来完全正常。
  rm -f /tmp/llm_spend.json /tmp/llm_liveness.json

  spend_out=$(PROBE_OUT=/tmp/llm_spend.json PYTHONIOENCODING=utf-8 \
              python -X utf8 -u -m smartbi.scripts.deepseek_spend_alert 2>&1)
  spend_rc=$?
  echo "--- spend (rc=$spend_rc) ---"; echo "$spend_out"

  live_out=$(PROBE_OUT=/tmp/llm_liveness.json PYTHONIOENCODING=utf-8 \
             python -X utf8 -u -m smartbi.scripts.minimal_safe_set_liveness 2>&1)
  live_rc=$?
  echo "--- liveness (rc=$live_rc) ---"; echo "$live_out"

  rm -f /tmp/llm_reminder.json
  rem_out=$(PROBE_OUT=/tmp/llm_reminder.json PYTHONIOENCODING=utf-8             python -X utf8 -u -m smartbi.scripts.provider_expiry_reminder 2>&1)
  rem_rc=$?
  echo "--- reminder (rc=$rem_rc) ---"; echo "$rem_out"

  # 提醒行单独进告警文件 —— 它不是「系统坏了」, 是「有个日期该看了」,
  # ⛔ 与降级/没量到用不同前缀, 免得三件事挤在一个措辞里。
  echo "$rem_out" | grep -E '^PROVIDER (EXPIRY|REVIEW|REMINDER)' | while IFS= read -r line; do
    echo "$line $(date '+%F %T') — 见 $LOG" >> "$ALERTS"
  done

  # 整体 rc：**2 优先于 1** —— 「没量到」比「量到了有问题」更需要先处理，
  # 因为后者的读数在前者成立时根本不可信。
  rc=0
  [ "$spend_rc" -eq 1 ] || [ "$live_rc" -eq 1 ] || [ "$rem_rc" -eq 1 ] && rc=1
  [ "$spend_rc" -eq 2 ] || [ "$live_rc" -eq 2 ] || [ "$rem_rc" -eq 2 ] && rc=2
  echo "=== done (spend=$spend_rc liveness=$live_rc reminder=$rem_rc overall=$rc) ==="

  # ── 告警：三态，措辞各不相同 ─────────────────────────────────────────
  if [ ! -r /tmp/llm_spend.json ] && [ ! -r /tmp/llm_liveness.json ] \
     && [ ! -r /tmp/llm_reminder.json ]; then
    # 三个产出一个都没有 = 探针没跑到做决定那一步（大概率崩了 / 环境不对）
    echo "LLM HEALTH INSTRUMENT DEAD $(date '+%F %T') — 三个探针都没写出产出(spend=$spend_rc liveness=$live_rc reminder=$rem_rc), 本次读数作废; 见 $LOG" >> "$ALERTS"
  elif [ "$rc" -eq 2 ]; then
    echo "LLM HEALTH NOT MEASURED $(date '+%F %T') — 这次没量到东西(spend=$spend_rc liveness=$live_rc reminder=$rem_rc), ⛔ 不要读成「没问题」; 见 $LOG" >> "$ALERTS"
  elif [ "$rc" -eq 1 ]; then
    # ⚠️ 提醒(rem_rc=1)**不算降级** —— 它是「有个日期该看了」, 上面已经单独
    #    进过告警文件。只有花费/存活才算降级, 否则从今天起到 9-13 每天都会
    #    多出一条「DEGRADED」, 而系统好好的(形态 E: 天天误报的告警会被关掉)。
    if [ "$spend_rc" -eq 1 ] || [ "$live_rc" -eq 1 ]; then
      echo "LLM HEALTH DEGRADED $(date '+%F %T') — 花费超阈值或兜底候选答不了(spend=$spend_rc liveness=$live_rc); 见 $LOG" >> "$ALERTS"
    fi
  fi

  # 台账：每天一行。⚠️ 三个 rc 分开记，⛔ 不许压成一个 —— 压了就分不清
  #      「花费探针挂了」和「兜底候选答不了」。
  python - "$spend_rc" "$live_rc" "$rem_rc" "$rc" <<'PY' >> "$LEDGER"
import datetime, json, os, sys

def load(path):
    try:
        with open(path, encoding='utf-8') as f:
            return json.load(f)
    except Exception:
        return None

spend, live = load('/tmp/llm_spend.json'), load('/tmp/llm_liveness.json')
print(json.dumps({
    "date": datetime.date.today().isoformat(),
    "spend_rc": int(sys.argv[1]),
    "liveness_rc": int(sys.argv[2]),
    "reminder_rc": int(sys.argv[3]),
    "rc": int(sys.argv[4]),
    # ⛔ 读不到就写 None, **不要兜底成 0** —— 「我不知道」和「是 0」
    #    对下游完全不同, 而 0 会被当成一个真实读数往下传。
    "spend_usd": (spend or {}).get("total_usd"),
    "alive": (live or {}).get("alive"),
    "checked": (live or {}).get("checked"),
}, ensure_ascii=False))
PY
} >> "$LOG" 2>&1
