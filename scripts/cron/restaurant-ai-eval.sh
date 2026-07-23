#!/usr/bin/env bash
# 餐饮 AI 每日回归评测 — R6→R22 全部问法类的可执行断言 (52 案例)。
# 数据刷新 (refresh-demo-rest, 03:17) 之后跑; 失败追加 alerts 日志。
#
# 2026-07-23 飞轮日报: 评测后追加晋升候选 (--list) 与 miss 复盘 (--misses)
# 报告到本日志; 有待人审条目时各追加一行 alerts (报告失败不影响 eval rc —
# 报告是 best-effort, 评测是硬门)。
# ⚠️ 本文件 + scripts/restaurant-intent-promote.py 不被 deploy-smartbi-python
# 同步 (它只 rsync backend/python) — 改动后需手动 scp 到
# /www/wwwroot/cretas/code/ 对应路径。
set -uo pipefail

PYDIR=/www/wwwroot/cretas/code/backend/python
CODEDIR=/www/wwwroot/cretas/code
LOG=/www/wwwroot/cretas/logs/restaurant-ai-eval.log
ALERTS=/www/wwwroot/cretas/logs/restaurant-ai-eval-alerts.log

{
  echo "=== $(date '+%F %T') restaurant AI eval ==="
  cd "$PYDIR"
  # shellcheck disable=SC1091
  source "$PYDIR/venv38/bin/activate"
  PYTHONIOENCODING=utf-8 python -X utf8 -m smartbi.scripts.restaurant_ai_eval \
    --base https://admin.cretaceousfuture.com
  rc=$?
  echo "=== done (rc=$rc) ==="
  if [ "$rc" -ne 0 ]; then
    echo "RESTAURANT AI EVAL FAILED rc=$rc $(date '+%F %T') — 见 $LOG" >> "$ALERTS"
  fi

  # ── 飞轮日报 (best-effort, 不影响 rc) ──
  if [ -f "$CODEDIR/scripts/restaurant-intent-promote.py" ]; then
    set -a
    # shellcheck disable=SC1091
    source /www/wwwroot/cretas/.env.prod 2>/dev/null
    set +a
    export POSTGRES_HOST=localhost POSTGRES_PORT=5432 \
           POSTGRES_DB=smartbi_prod_db POSTGRES_USER=smartbi_user \
           POSTGRES_PASSWORD="${SMARTBI_DB_PASSWORD:-}"
    echo "=== $(date '+%F %T') 飞轮日报: 晋升候选 ==="
    LIST_OUT=$(cd "$CODEDIR" && PYTHONIOENCODING=utf-8 python -X utf8 \
      scripts/restaurant-intent-promote.py --list 2>&1) || true
    echo "$LIST_OUT"
    N_PROMOTE=$(printf '%s\n' "$LIST_OUT" | grep -oE '[0-9]+ 条达标推荐晋升' | grep -oE '^[0-9]+' | head -1 || true)
    if [ "${N_PROMOTE:-0}" -gt 0 ] 2>/dev/null; then
      echo "FLYWHEEL: ${N_PROMOTE} 条晋升候选待人审 $(date '+%F %T') — 见 $LOG" >> "$ALERTS"
    fi
    echo "=== $(date '+%F %T') 飞轮日报: miss 复盘 ==="
    MISS_OUT=$(cd "$CODEDIR" && PYTHONIOENCODING=utf-8 python -X utf8 \
      scripts/restaurant-intent-promote.py --misses 2>&1) || true
    echo "$MISS_OUT"
    N_MISS=$(printf '%s\n' "$MISS_OUT" | grep -c '^共 .* 组 miss' || true)
    if [ "${N_MISS:-0}" -gt 0 ]; then
      MISS_LINE=$(printf '%s\n' "$MISS_OUT" | grep '^共 .* 组 miss' | head -1)
      echo "FLYWHEEL: ${MISS_LINE} 待复盘 $(date '+%F %T') — 见 $LOG" >> "$ALERTS"
    fi
    unset POSTGRES_PASSWORD SMARTBI_DB_PASSWORD DB_PASSWORD JWT_SECRET
  else
    echo "飞轮日报跳过: $CODEDIR/scripts/restaurant-intent-promote.py 不存在 (需 scp 同步)"
  fi

  exit "$rc"
} >> "$LOG" 2>&1
