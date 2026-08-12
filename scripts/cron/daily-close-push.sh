#!/usr/bin/env bash
# 打烊触发 —— 每天营业结束后跑一次日结那一屏, 推给店长。
#
# ## 为什么是 cron 而不是「打烊按钮」
#
# 打烊时间各店不同, 而「谁来点那个按钮」本身就是个没人负责的活。
# 固定时刻跑 = 不依赖任何人记得。⚠️ 时刻取 23:40: 晚市基本收摊, 又还没跨日 ——
# `date.today()` 必须落在**营业当天**, 跨过 0 点就成了推「昨天」。
#
# ## 幂等
#
# 防重键是 `(factory_id, YYYY-MM-DD, role)`, 存在
# `restaurant_value_notifications_log`(迁移 V20261101_13 把那一列加宽到 16)。
# 所以 cron 重试 / 手工补跑都只推一次。
#
# ⛔ 推送失败**不写**防重日志 —— 下次可重试。这是 value_notifier 原有的行为,
#    日结沿用, 没有另写一套。
#
# ## 租户名单
#
# `DAILY_CLOSE_FACTORIES` 在 `/www/wwwroot/cretas/.env.daily-close` 里配。
# ⛔ 脚本**不给默认租户** —— 名单空就 rc=2 告警, 而不是猜一个。
#    猜错的方向是「把别人家的经营数字推给另一家店长」。
set -uo pipefail

PYDIR=/www/wwwroot/cretas/code/backend/python
LOG=/www/wwwroot/cretas/logs/daily-close-push.log
ALERTS=/www/wwwroot/cretas/logs/daily-close-alerts.log
LEDGER=/www/wwwroot/cretas/logs/daily-close-ledger.jsonl

{
  echo "=== $(date '+%F %T') daily-close push ==="
  cd "$PYDIR" || exit 1
  # shellcheck disable=SC1091
  source "$PYDIR/venv-current/bin/activate"

  set -a
  # shellcheck disable=SC1091
  source /www/wwwroot/cretas/.env.prod 2>/dev/null
  # shellcheck disable=SC1091
  source /www/wwwroot/cretas/.env.daily-close 2>/dev/null
  set +a

  # ⚠️ `.env.prod` **不含** POSTGRES_DB —— 它只在 systemd unit 里。
  #    不补这一步会静默落到默认值(测试库), 于是「推了」但推的是测试库的数。
  svc_pid=$(systemctl show -p MainPID --value cretas-python.service 2>/dev/null)
  if [ -n "${svc_pid:-}" ] && [ "$svc_pid" != "0" ] && [ -r "/proc/$svc_pid/environ" ]; then
    while IFS= read -r -d '' kv; do
      case "$kv" in POSTGRES_*|INTERNAL_API_SECRET=*) export "${kv?}" ;; esac
    done < "/proc/$svc_pid/environ"
  else
    echo "WARN: 拿不到活服务进程 environ (pid=${svc_pid:-none}) —— 库名可能落到默认值(测试库)"
  fi

  out=$(DAILY_CLOSE_OUT=/tmp/daily_close_push.json PYTHONIOENCODING=utf-8 \
        python -X utf8 -u -m smartbi.scripts.daily_close_push 2>&1)
  rc=$?
  echo "$out"
  echo "=== done (rc=$rc) ==="

  # rc: 0 = 推成功/已推过; 1 = 有租户失败; 2 = 仪器问题(没轮到租户/一段都没算出来)
  # ⛔ rc=2 单独喊 —— 「一个都没推」和「都推过了」计数上都是 notified=0,
  #    混在一起报的话, 静默失效看起来跟正常一模一样。
  if [ "$rc" -eq 2 ]; then
    echo "DAILY CLOSE INSTRUMENT DEAD $(date '+%F %T') — 没轮到租户或一段都没算出来, 见 $LOG" >> "$ALERTS"
  elif [ "$rc" -ne 0 ]; then
    echo "DAILY CLOSE PUSH FAILED $(date '+%F %T') — 有租户推送失败(下次会重试), 见 $LOG" >> "$ALERTS"
  fi

  # 台账: 每天一行。⛔ 只记计数不记正文 —— 台账不该把经营数字抄进日志文件。
  if [ -r /tmp/daily_close_push.json ]; then
    DAILY_CLOSE_RC="$rc" python -X utf8 -u -m smartbi.scripts.daily_close_ledger >> "$LEDGER"
  fi
} >> "$LOG" 2>&1
