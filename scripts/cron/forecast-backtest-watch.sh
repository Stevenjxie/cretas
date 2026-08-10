#!/usr/bin/env bash
# 预测回测闸 — 每日验证「线上预测是否仍然强过朴素基线」。
#
# 为什么需要定时跑 (2026-08-10 审计结论):
#   这道闸当天发现了三件事 —— 目标测算预测缺周内项(在所有跨度上输给「上周同日」)、
#   LLM 在 h=1 是全场最好、以及预测该自带历史误差。但它**只在人手敲命令时存在**,
#   不接 cron 的话, 明天没人会跑它, 于是「预测还准不准」重新变成无人知晓。
#   判据: **一道只在人手敲命令时存在的闸, 等于一次性的调查, 不是闸。**
#
# ⛔ 这道闸的第一职责不是「算法准不准」, 而是**拒绝在没有信号的序列上发绿灯**:
#   MOCK_REST(回归电池的默认租户)日单量恒定 1986~2000、CV 1.60%, 在那种序列上
#   「预测=均值」就有 ~1.3% MAPE, 任何算法满分 —— 闸绿且什么都没证明。
#   所以这里跑的是 RES_3101_009 / DEMO_REST 的**平稳窗口**, 且脚本自身会在信号
#   不足 / 有断层时返回 REFUSE(退出码 2), 那**不是通过**。
#
# 退出码语义 (来自 smartbi.scripts.forecast_backtest):
#   0 = PASS    算法显著优于最好的朴素基线
#   1 = FAIL    赢不了基线 → 预测入口不该开 → 写 alerts
#   2 = REFUSE  数据不配当验证集 → 也写 alerts(说明验证窗口需要人调整)
#
# 本文件由 deploy-smartbi-python 同步到服务器; 运行时用 venv-current 原子链接,
# 避免定时任务与主服务使用不同 Python。
set -uo pipefail

PYDIR=/www/wwwroot/cretas/code/backend/python
LOG=/www/wwwroot/cretas/logs/forecast-backtest.log
ALERTS=/www/wwwroot/cretas/logs/forecast-backtest-alerts.log

# 租户 × 窗口 × 跨度 —— 窗口刻意用**平稳段**, 不是「最近 N 天」:
# 最近段被反复灌数据切成了多个体制(实测 90 日窗内 3.0× 与 5.9× 两处突变),
# 在那上面回测量的是灌数据留下的台阶, 不是预测能力。
CASES=(
  "RES_3101_009 2026-01-01 2026-04-30 7"
  "RES_3101_009 2026-01-01 2026-04-30 30"
  "DEMO_REST    2026-01-01 2026-04-30 7"
)

mkdir -p "$(dirname "$LOG")"
{
  echo "=== $(date '+%F %T') forecast backtest ==="
  cd "$PYDIR" || exit 1
  # shellcheck disable=SC1091
  source "$PYDIR/venv-current/bin/activate"
  set -a
  # shellcheck disable=SC1091
  source /www/wwwroot/cretas/.env.prod 2>/dev/null
  set +a

  worst=0
  for case in "${CASES[@]}"; do
    read -r tenant start end horizon <<<"$case"
    echo "--- $tenant $start~$end h=$horizon"
    # ⚠️ PYTHONPATH 必须同时含 . 与 ./smartbi —— smartbi/services/__init__.py 里
    #    `from services.xxx import ...` 需要 smartbi/ 本身在 path 上(app 就是这么跑的)。
    #    2026-08-10 首次写这个脚本时漏了, 表现是
    #    `ModuleNotFoundError: No module named 'services'`。
    #    判据: **写完立刻在目标环境真跑一次** —— 只写不跑的脚本, 上线那天才发现不能用。
    PYTHONPATH=.:./smartbi PYTHONIOENCODING=utf-8 python -X utf8 \
      -m smartbi.scripts.forecast_backtest \
      --factory-id "$tenant" --start "$start" --end "$end" --horizon "$horizon"
    rc=$?
    # ⚠️ 取**最差**的那个退出码, 不是最后一个 —— 否则最后一格通过就把前面的红盖掉了。
    [ "$rc" -gt "$worst" ] && worst=$rc
    case "$rc" in
      0) echo "    → PASS" ;;
      1) # ⚠️ 退出码 1 有两种来源: 业务判红(赢不了基线) 与 python 自身异常
         #    (import 失败/连不上库)。只看 rc 会把后者写成前者 —— 2026-08-10 首跑
         #    时就发生了: ModuleNotFoundError 被记成「预测赢不了朴素基线」, 一条
         #    **误导的 alert**。判据: **退出码是分类不够细的信号, 要配合输出里的
         #    判定串确认。**
         if grep -q "BACKTEST FAIL" "$LOG" 2>/dev/null &&             tail -40 "$LOG" | grep -q "BACKTEST FAIL"; then
           echo "    → FAIL (赢不了朴素基线)"
           echo "$(date '+%F %T') FAIL $tenant h=$horizon 预测赢不了朴素基线" >>"$ALERTS"
         else
           echo "    → 脚本自身出错(非业务判红), 见上方 traceback"
           echo "$(date '+%F %T') ERROR $tenant h=$horizon 回测脚本自身出错(非业务判红)" >>"$ALERTS"
         fi ;;
      2) echo "    → REFUSE (数据不配当验证集)"
         echo "$(date '+%F %T') REFUSE $tenant $start~$end 验证窗口需要人调整" >>"$ALERTS" ;;
      *) echo "    → 异常退出 rc=$rc"
         echo "$(date '+%F %T') ERROR $tenant h=$horizon rc=$rc" >>"$ALERTS" ;;
    esac
  done

  echo "=== worst rc = $worst ==="
  exit "$worst"
} >>"$LOG" 2>&1
