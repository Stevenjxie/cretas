#!/usr/bin/env bash
# 回放等价性每日跑批 —— 拦「事实」，与 CI 那道拦「人」的闸互补。
#
# ## 为什么两道都要
#
# CI 里的 `test_replay_equivalence_gate` 是一道 **forcing function**：
# 路由规则一变指纹就变、闸就红，逼作者停下来去跑等价检查。
# 但它**不跑检查** —— 贴一份旧读数就能满足它。这不是设计缺陷：
# 那 40 条晋升在 prod 数据上，CI 够不到 prod DB，自动化等价检查在 CI 里做不到。
#
# 🔴 而指纹只对**路由规则**敏感。等价性可以因为完全别的原因漂：
#      · 数据变了（新菜品、口径重算、ETL 改了）
#      · resolver 改了（同一个计划、不同的执行）
#      · 渲染改了（同一批数、不同的正文）
#    这些都不动指纹 —— **闸不响，而回放已经和今天不一样了**。
#
# 本脚本不看指纹，直接比产出。它是那个盲区的补丁。
#
# ⛔ 不等价时**不阻断任何东西** —— 它是观测，不是门禁。它的产出是台账 + 告警行，
#    给人看。自动回滚一个「答案变了」的判断是危险的：变了未必是错了。
#
# 本文件由 deploy-smartbi-python 同步到服务器；运行时通过 venv-current
# 原子链接切换，避免定时任务与主服务使用不同 Python。
set -uo pipefail

PYDIR=/www/wwwroot/cretas/code/backend/python
LOG=/www/wwwroot/cretas/logs/replay-equivalence.log
ALERTS=/www/wwwroot/cretas/logs/replay-equivalence-alerts.log
LEDGER=/www/wwwroot/cretas/logs/replay-equivalence-ledger.jsonl

{
  echo "=== $(date '+%F %T') replay equivalence ==="
  cd "$PYDIR" || exit 1
  # shellcheck disable=SC1091
  source "$PYDIR/venv-current/bin/activate"

  # prod 库/密钥只从 .env.prod 注入，绝不落进仓库。
  # ⚠️ `.env.prod` **不含** POSTGRES_DB —— 它只在 systemd unit 里。
  #    所以下面补一次从活服务进程 environ 取值（判据: 量 prod 看活进程）。
  set -a
  # shellcheck disable=SC1091
  source /www/wwwroot/cretas/.env.prod 2>/dev/null
  set +a
  svc_pid=$(systemctl show -p MainPID --value cretas-python.service 2>/dev/null)
  if [ -n "${svc_pid:-}" ] && [ "$svc_pid" != "0" ] && [ -r "/proc/$svc_pid/environ" ]; then
    while IFS= read -r -d '' kv; do
      case "$kv" in POSTGRES_*|INTERNAL_API_SECRET=*) export "${kv?}" ;; esac
    done < "/proc/$svc_pid/environ"
  else
    echo "WARN: 拿不到活服务进程 environ (pid=${svc_pid:-none}) —— 库名可能落到默认值(测试库)"
  fi

  # ⛔ 先删上一次的产出 —— 与 python 侧「早退也写文件」互为两道。
  #    只要有一条路径不写文件, 台账就会把**上一次的计数**配上**这一次的 rc**,
  #    而那种行格式合法、字段齐全、看起来完全正常。
  #    我们靠台账做盖章决定, 一行脏读会让所有基于它的判断打折。
  rm -f /tmp/replay_equivalence.json

  out=$(PROBE_OUT=/tmp/replay_equivalence.json PYTHONIOENCODING=utf-8 \
        python -X utf8 -u -m smartbi.scripts.replay_equivalence_probe 2>&1)
  rc=$?
  echo "$out"
  echo "=== done (rc=$rc) ==="

  # rc: 0 = 全等价; 1 = 有 ②/③; 2 = 仪器问题(阳性对照 0 / 表里 0 行)
  # ⛔ 阳性对照失败要单独喊 —— 那不是「产品没问题」，是「这次没量到东西」。
  if [ "$rc" -eq 2 ]; then
    echo "REPLAY EQUIV INSTRUMENT DEAD $(date '+%F %T') — 阳性对照未通过, 本次读数作废, 见 $LOG" >> "$ALERTS"
  elif [ "$rc" -ne 0 ]; then
    echo "REPLAY EQUIV DRIFT $(date '+%F %T') — 有条目不再等价(指纹**可能没变**), 见 $LOG" >> "$ALERTS"
  fi

  # 台账: 每天一行, 让「哪天开始漂的」可回溯。⛔ 只记计数不记全文, 免得台账变日志。
  if [ -r /tmp/replay_equivalence.json ]; then
    python - "$rc" <<'PY' >> "$LEDGER"
import json, sys, datetime
from collections import Counter
rows = json.load(open('/tmp/replay_equivalence.json', encoding='utf-8'))
c = Counter(r['class'] for r in rows)
print(json.dumps({
    "date": datetime.date.today().isoformat(),
    "rc": int(sys.argv[1]),
    "total": len(rows),
    "positive_control_hits": sum(1 for r in rows if r.get('hit_a')),
    "counts": {k: v for k, v in c.items()},
    "not_equivalent": [r['phrase'] for r in rows if r['class'].startswith(('2', '②', '3', '③'))],
}, ensure_ascii=False))
PY
  fi
} >> "$LOG" 2>&1
