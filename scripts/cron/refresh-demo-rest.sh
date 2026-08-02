#!/bin/bash
#
# refresh-demo-rest.sh — daily refresh of allowlisted restaurant demo data.
#
# WHY: backend stats/"this month"/"today" endpoints default their date range to
# LocalDate.now() (current month/day). The demo's operational seed data
# (wastage / stocktaking) is stamped at fixed past dates, so once the calendar
# rolls past that window the headline aggregates collapse to ¥0 / 0 even though
# the detail rows still exist (损耗总额 ¥0.00, 盘盈/盘亏次数 0, 首页本月损耗 0).
#
# This re-runs the ops seeder with --end=today so wastage/stocktaking always
# land inside the current month. The seeder is a full-replace (deletes its own
# demo_rest_wst_% / demo_rest_stk_% rows first), so reruns never accumulate
# orphans. Scoped to DEMO_REST only — never touches real tenant data.
#
# After that, the reserved-version aggregate refresh extends demo
# sales through yesterday, the latest complete business day. It is idempotent,
# auditable and reversible through the seed version owned by the Python module.
# The source and target are hard-confirmed demo identities; real tenant data is
# never selected by this cron entry.
#
# Install (on server 47, as root):
#   crontab -e
# Run just after midnight so "昨天" is available before the first demo query:
#   5 0 * * *  /www/wwwroot/cretas/code/scripts/cron/refresh-demo-rest.sh
#
set -euo pipefail

PYDIR=/www/wwwroot/cretas/code/backend/python
ENV_FILE=/www/wwwroot/cretas/.env.prod
LOG=/www/wwwroot/cretas/logs/demo-rest-refresh.log

mkdir -p "$(dirname "$LOG")"

# Load secrets from the server env file (never hardcode or print them).
[[ -r "$ENV_FILE" ]] || { echo "Required server environment file is unavailable" >&2; exit 1; }
# shellcheck disable=SC1090
set -a; source "$ENV_FILE"; set +a
: "${DB_PASSWORD:?DB_PASSWORD not found in $ENV_FILE}"
: "${SMARTBI_DB_NAME:?SMARTBI_DB_NAME not found in $ENV_FILE}"
: "${SMARTBI_DB_PASSWORD:?SMARTBI_DB_PASSWORD not found in $ENV_FILE}"

# The aggregate refresher reads POSTGRES_* only. Map the existing SmartBI
# production variables explicitly and fail closed before invoking Python.
export POSTGRES_HOST=localhost
export POSTGRES_PORT=5432
export POSTGRES_DB="$SMARTBI_DB_NAME"
export POSTGRES_USER=smartbi_user
export POSTGRES_PASSWORD="$SMARTBI_DB_PASSWORD"
: "${POSTGRES_HOST:?POSTGRES_HOST is required}"
: "${POSTGRES_PORT:?POSTGRES_PORT is required}"
: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}"

DSN="dbname=cretas_prod_db user=cretas_user password=${DB_PASSWORD} host=localhost"
TODAY=$(date +%F)
YESTERDAY=$(date -d yesterday +%F)

# 六个步骤服务的**不是同一个租户**, 所以一步失败不能连坐后面的。
#
# 2026-08-02 事故: DEMO_REST 的 fact_pos_transaction 是一次性种到 2026-07-31 的,
# 8 月 1 日起就没有源数据了。于是旧五步链的第 3 步(从 DEMO_REST 自己的 POS 粒度聚合)
# 诚实地拒绝谎报成功:
#
#     RuntimeError: agg_daily coverage still ends at 2026-07-31, expected 2026-08-01
#
# 它拒绝得对 —— 错的是 `set -e` 让整条链就此中止, **旧第 4、5 步再也没跑过**。
# 而旧第 5 步服务的是 RES_3101_009, 它明明还能继续滚(实测当天 synth_bills_needed
# = 2112, 补跑后 joined_max_date 到 2026-08-01)。一个租户的数据到头, 把另一个
# 还活着的租户一起拖停了 —— 后者会以每天一天的速度变陈旧。
#
# 症状极不显眼: 日志末尾既没有 traceback 之外的东西, 也**没有 `done (rc=)`**,
# 而当天餐饮 eval 通过率从 87% 掉到 58% 看着像是模型变差了。
#
# 顺带修掉一个假信号: 原来收尾写的是 `done (rc=$?)`, 而 `$?` 取的是上一条 echo
# 的退出码 —— **恒为 0**, 从来不携带任何信息(有 set -e 在, 真失败根本走不到那行)。
#
# 2026-08-02 双轨决策: DEMO_REST 继续维护为备用/回归租户, 但主演示只使用
# MOCK_REST + RES_3101_009。维护闭环必须严格按以下顺序:
#   固定自身历史模板滚 agg -> 按 agg 合成 POS/菜品 -> 从自身 POS 重算/核验 agg。
# 先跑“自身 POS 聚合”会重现 7/31 覆盖中止; 只跑 dish roller 又会因为 agg 同样
# 停在 7/31 而得到 synth_bills_needed=0。三段缺一不可, 顺序也不可交换。
FAILED_STEPS=()
TOTAL_STEPS=6

run_step() {
    local label="$1"
    shift
    echo "=== $(date '+%F %T') $label ==="
    # `cmd || rc=$?` 里 `||` 左侧同样临时关掉 errexit, 失败不会中止脚本。
    #
    # ⚠️ 这里**不能**写成 `if "$@"; then return 0; fi; local rc=$?` ——
    # 那个 `$?` 取的是 **if 语句本身**的状态(没走 then 分支时是 0), 不是命令的,
    # 于是每次失败都打印 "exit 0", 看着像成功。写这段时第一版就是这么错的,
    # 靠喂一个必定失败的假步骤才发现 —— 和本文件原来那个恒为 0 的
    # `done (rc=$?)` 是同一个 bash 陷阱。
    local rc=0
    "$@" || rc=$?
    if [ "$rc" -eq 0 ]; then
        return 0
    fi
    echo "!!! 步骤失败 (exit $rc): $label"
    echo "!!! 继续跑后面的步骤 —— 它们服务别的租户, 不该被这一步连坐。"
    FAILED_STEPS+=("$label")
    return 0
}

{
  cd "$PYDIR"
  # shellcheck disable=SC1091
  source "$PYDIR/venv-current/bin/activate"

  run_step "refresh DEMO_REST ops (end=$TODAY)" \
    python smartbi/scripts/seed_demo_rest_ops.py --dsn "$DSN" --end "$TODAY"

  run_step "refresh RES_3101_009 sales aggregate (end=$YESTERDAY)" \
    python -m smartbi.scripts.refresh_qhj_demo_recent_agg \
      --factory RES_3101_009 \
      --apply --confirm RES_3101_009 --end "$YESTERDAY"

  run_step "refresh DEMO_REST sales aggregate from own fixed template (end=$YESTERDAY)" \
    python -m smartbi.scripts.refresh_qhj_demo_recent_agg \
      --factory DEMO_REST \
      --apply --confirm DEMO_REST --end "$YESTERDAY"

  run_step "refresh DEMO_REST dish-level POS items (end=$YESTERDAY)" \
    python -m smartbi.scripts.refresh_demo_rest_dish_facts \
      --apply --confirm DEMO_REST --end "$YESTERDAY"

  run_step "verify DEMO_REST agg_daily from own POS grain (end=$YESTERDAY)" \
    python -m smartbi.scripts.refresh_demo_rest_agg_daily \
      --apply --confirm DEMO_REST --end "$YESTERDAY"

  run_step "refresh RES_3101_009 dish-level POS grain (end=$YESTERDAY)" \
    python -m smartbi.scripts.refresh_demo_rest_dish_facts \
      --factory RES_3101_009 --apply --confirm RES_3101_009 --end "$YESTERDAY"

  if [ ${#FAILED_STEPS[@]} -gt 0 ]; then
      echo "=== done (rc=1, ${#FAILED_STEPS[@]}/$TOTAL_STEPS 步失败) ==="
      for step in "${FAILED_STEPS[@]}"; do
          echo "    ✗ $step"
      done
      exit 1
  fi
  echo "=== done (rc=0, $TOTAL_STEPS/$TOTAL_STEPS 步成功) ==="
} >> "$LOG" 2>&1
