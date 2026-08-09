#!/usr/bin/env bash
# probe-llm-registry.sh — 每日全量探针, 把 llm_router._SAFE_MODELS 与现实对账。
#
# WHY: llm_router 的三层拆分(事实表 _SAFE_MODELS / 资格层 _SLOT_POOLS / 排序
# _build_chain)只有在"注册表跟得上现实"时才成立 —— 而发现"现实漂移了"的唯一
# 机制是 backend/python/scripts/probe_llm_registry.py。2026-08-09 之前这套机制
# 只是一个可以手动跑的脚本, 没有 cron 包装、没有 crontab 条目、没有告警通道,
# 相当于"这个分支存在的理由"从没真正跑起来过。本文件补上这三样, 抄
# restaurant-ai-eval.sh 的形状(venv-current 原子链接、日志、alerts 文件)。
#
# 2026-08-10 补记: 本文件首次真跑就在生产凭证下抓到 3 条 08-09 当天还 OK 的
# 条目 24 小时内变成 403 —— 机制第一次跑就抓到了真实漂移, 见
# llm_router.py `_SAFE_MODELS` 后面的 "2026-08-10 探针复审剔除" 段落。
#
# 本文件由 deploy-smartbi-python.sh 的 3d 步同步到服务器; 运行时通过
# venv-current 原子链接切换, 避免定时任务与主服务使用不同 Python(与
# restaurant-ai-eval.sh / refresh-demo-rest.sh 同一约定)。
#
# 退出码语义(见 probe_llm_registry.main() 的实现与注释):
#   0 = 无「注册表说活、实测不可用」的条目(已过期的条目算「已过期, 待清理」,
#       不算 dead, 不翻转退出码 —— 否则每次批量到期后会连续多天必炸)。
#   1 = 有 dead 条目, 需要人核对控制台余量后更新 _SAFE_MODELS。
#
# Install (on server 47, as root):
#   crontab -e
# 建议在餐饮日报评测(见 restaurant-ai-eval.sh, 无固定 crontab 时间戳可查, 按
# 惯例排在业务低峰)之外找一个不冲突的时段, 例如凌晨:
#   17 3 * * *  /www/wwwroot/cretas/code/scripts/cron/probe-llm-registry.sh
#
set -uo pipefail

PYDIR=/www/wwwroot/cretas/code/backend/python
ENV_FILE=/www/wwwroot/cretas/.env.prod
LOG=/www/wwwroot/cretas/logs/probe-llm-registry.log
ALERTS=/www/wwwroot/cretas/logs/probe-llm-registry-alerts.log

mkdir -p "$(dirname "$LOG")"

{
  echo "=== $(date '+%F %T') probe_llm_registry ==="
  cd "$PYDIR"
  # shellcheck disable=SC1091
  source "$PYDIR/venv-current/bin/activate"

  # 凭证只从 .env.prod 注入, 绝不落进仓库(与 refresh-demo-rest.sh 同一规范)。
  [[ -r "$ENV_FILE" ]] || { echo "Required server environment file is unavailable" >&2; exit 1; }
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a

  PYTHONIOENCODING=utf-8 python -X utf8 -m scripts.probe_llm_registry
  rc=$?
  echo "=== done (rc=$rc) ==="

  # 只有「dead」(注册表说活、实测不可用)才翻转 rc(见 probe_llm_registry.main()
  # 内的判据), 所以这里 rc!=0 就是真实漂移, 值得每天都告警一次 —— 与
  # restaurant-ai-eval.sh 的 alerts 约定一致: 报告是 best-effort, 告警文件
  # 只在真失败时追加, 不会因为"7 天内到期"或"已过期待清理"这类预期内的信号
  # 而天天写, 不会重蹈飞轮日报"天天炸=没人看"的覆辙。
  if [ "$rc" -ne 0 ]; then
    echo "PROBE LLM REGISTRY FOUND DEAD ENTRIES rc=$rc $(date '+%F %T') — 见 $LOG" >> "$ALERTS"
  fi

  exit "$rc"
} >> "$LOG" 2>&1
