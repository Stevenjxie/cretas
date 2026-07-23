#!/usr/bin/env bash
# 餐饮 AI 每日回归评测 — R6→R22 全部问法类的可执行断言 (36 案例)。
# 数据刷新 (refresh-demo-rest, 03:17) 之后跑; 失败追加 alerts 日志。
set -uo pipefail

PYDIR=/www/wwwroot/cretas/code/backend/python
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
  exit "$rc"
} >> "$LOG" 2>&1
