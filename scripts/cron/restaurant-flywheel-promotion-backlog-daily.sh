#!/usr/bin/env bash
# 飞轮 A 晋升候选积压告警(飞轮 A 第 10 项, 2026-08-18)。
#
# 只报, 不写。⛔ 绝不自动 --apply —— 晋升是永久零 token 回放, 錯了比答錯一次
# 更严重, 必须有人核对「当时的真实回答」才敢按 (见对应设计卡
# docs/decisions/2026-08-18-飞轮A晋升谁来按-设计卡.md 的反目标)。这个脚本
# 只解决"候选攒了很久没人看"这一件事 —— 与 --apply 本身的人审门槛无关。
#
# 🔴 三态(硬约束 4): rc=0 没问题 / rc=1 达标候选超阈值 / rc=2 这次没量到。
#    ⛔ 两态会把"没量到"折叠进"没问题" —— 一个连不上库的跑批会安静地
#    天天绿, 而它一个候选都没看过。三态判据与
#    scripts/cron/time-phrase-backlog-daily.sh 同一套, 复用同一份纪律。
#
# ⚙️ 路径/阈值全部可覆盖(与 time-phrase-backlog-daily.sh 同一裁定): 生产
#    默认值不变(cron 零配置照跑), 本地/测试用环境变量覆盖来构造三态。
set -uo pipefail

CODE_DIR="${RESTAURANT_PROMO_CODE_DIR:-/www/wwwroot/cretas/code}"
VENV_ACTIVATE="${RESTAURANT_PROMO_VENV_ACTIVATE:-/www/wwwroot/cretas/code/backend/python/venv-current/bin/activate}"
ALERTS="${RESTAURANT_PROMO_ALERTS:-/www/wwwroot/cretas/logs/restaurant-flywheel-promotion-backlog-alerts.log}"
# 阈值定在"值得停下来审一次"的量级 —— 2026-08-18 探针实测 recommended=179,
# 早已远超任何合理阈值; 50 是"每次审 Top-50"里那个 50, 不是随便挑的整数。
THRESHOLD="${RESTAURANT_PROMO_THRESHOLD:-50}"
FACTORY="${RESTAURANT_PROMO_FACTORY:-MOCK_REST}"

OUT=$(cd "$CODE_DIR" \
      && source "$VENV_ACTIVATE" \
      && python scripts/restaurant-intent-promote.py --counts --factory "$FACTORY" 2>&1)
rc=$?

if [ "$rc" -ne 0 ] || echo "$OUT" | grep -q "counts_unavailable=true"; then
    echo "XXX INSTRUMENT DEAD $(date '+%F %T') — 连不上库/CLI 异常, 本次读数作废" >> "$ALERTS"
    echo "$OUT" >> "$ALERTS"
    exit 2
fi

recommended=$(echo "$OUT" | grep -oP 'recommended=\K[0-9]+')

# ⚠️ 解析不出数字也是"没量到", ⛔ 不是 0 —— 同源判据见
#    time-phrase-backlog-daily.sh 里对同一类问题的注释(多行 stderr 混进
#    grep -oP 会让变量变成非法值, -eq/-gt 直接报错、被 if 读成 false)。
if ! [[ "$recommended" =~ ^[0-9]+$ ]]; then
    echo "XXX INSTRUMENT DEAD $(date '+%F %T') — 读数解析失败: $OUT" >> "$ALERTS"
    exit 2
fi

if [ "$recommended" -gt "$THRESHOLD" ]; then
    echo "XXX BACKLOG $(date '+%F %T') — 达标候选 $recommended 条待审 (阈值 $THRESHOLD)，" \
         "跑 python scripts/restaurant-intent-promote.py --list 看详情" >> "$ALERTS"
    exit 1
fi
exit 0
