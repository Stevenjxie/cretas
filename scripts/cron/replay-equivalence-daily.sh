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
  rm -f /tmp/replay_equivalence.json /tmp/replay_equivalence.alert

  out=$(PROBE_OUT=/tmp/replay_equivalence.json \
        PROBE_ALERT_OUT=/tmp/replay_equivalence.alert PYTHONIOENCODING=utf-8 \
        python -X utf8 -u -m smartbi.scripts.replay_equivalence_probe 2>&1)
  rc=$?
  echo "$out"
  echo "=== done (rc=$rc) ==="

  # ── 告警：**判定在 Python 里**(`alert_for`, 可单测)，这里只负责把非空的那行追加。
  #
  # ⚠️ rc 三态(硬约束 4)。rc=2 是「**这次没量到东西**」，不是「量到了但不合格」，
  #    而它的三个成因处置完全不同：
  #      (a) eligible_stored=0  存量按设计全部失效(旧格式，等人逐条盖章)
  #          → **不是故障**，⛔ **不告警**(owner 2026-08-15 裁定 ①)。它照常落台账。
  #      (b) eligible_stored>0 却 0 条回放 → **仪器坏了**(A 遍撬棍失效) → 告警
  #      (c) positive_control=0 / 表里 0 行 → 格式门坏 / plan_version 对不上 → 告警
  #
  # 🔴 为什么 (a) 不喊：08-13 起每天落的都是 (a)，而告警一律喊「阳性对照未通过」——
  #    positive_control 明明是 1。**一个天天误报的告警最终会被忽略**，
  #    而它拖下水的是**所有**告警的可信度(形态 E: 完备性与存活是矛盾的)。
  #
  # ⛔ 判定别再写回 shell —— 写在这里就没法单测，而它正是被误报咬了三天的那一段。
  if [ -s /tmp/replay_equivalence.alert ]; then
    while IFS= read -r line; do
      [ -n "$line" ] && echo "$line $(date '+%F %T') — 见 $LOG" >> "$ALERTS"
    done < /tmp/replay_equivalence.alert
  fi

  # 台账: 每天一行, 让「哪天开始漂的」可回溯。⛔ 只记计数不记全文, 免得台账变日志。
  if [ -r /tmp/replay_equivalence.json ]; then
    python - "$rc" <<'PY' >> "$LEDGER"
import json, sys, datetime
from collections import Counter
payload = json.load(open('/tmp/replay_equivalence.json', encoding='utf-8'))
# 🔴 2026-08-13 拆两行: 产出从「一个 list」变成「带两个数的 dict」。
#    兼容旧格式(list), 免得升级当天的那一行读不出来。
rows = payload if isinstance(payload, list) else payload.get('rows', [])
meta = {} if isinstance(payload, list) else payload
c = Counter(r['class'] for r in rows)
print(json.dumps({
    "date": datetime.date.today().isoformat(),
    "rc": int(sys.argv[1]),
    "total": len(rows),
    # ⚠️ 三个数, 各自回答一个问题, ⛔ 不许再压成一个:
    #   positive_control  机制今天还能不能开火(恒 1, 为 0 = 仪器坏了)
    #   eligible_stored   存量里今天真的会回放几条(为 0 是**事实**不是故障)
    #   replay_hits       这一轮实际命中了几条(旧口径, 保留可比性)
    # 🔴 2026-08-15: `counts` 的 ⓪ 标签**没跟上拆两行** —— 它写着
    #    「阳性对照未命中」, 而阳性对照(positive_control)明明是 1。
    #    同一行里两个字段打架, 实测连着三天。已在探针侧把 ⓪ 拆成
    #    「存量格式过期(按设计不回放)」/「合格却没回放(仪器问题)」两类。
    # ⛔ **历史行一个字不改** —— 台账只追加。对齐的是今后写入的那一份。
    "positive_control": meta.get('positive_control'),
    "eligible_stored": meta.get('eligible_stored'),
    "stored_total": meta.get('stored_total'),
    "replay_hits": sum(1 for r in rows if r.get('hit_a')),
    "counts": {k: v for k, v in c.items()},
    "not_equivalent": [r['phrase'] for r in rows if r['class'].startswith(('2', '②', '3', '③'))],
}, ensure_ascii=False))
PY
  fi
} >> "$LOG" 2>&1
