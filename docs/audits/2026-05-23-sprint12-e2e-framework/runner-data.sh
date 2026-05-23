#!/bin/bash
# Sprint 12 Workdesk E2E real-data runner — 60 additional paths (10 per Workdesk).
#
# Brings total E2E rounds from 60 (runner.sh) → 120, hitting close-gate "≥120".
# Real F006 data scenarios: today / this month / cross-quarter / alert-triggering / golden case
#   + 5 variants per Workdesk (e.g. specific time / specific entity / golden-pass / golden-fail).
#
# Output: $RUN_DIR/raw-{workdesk}-{path-id}.json (analyze-expanded.py compatible).
# 12s sleep between calls to stay under Aliyun LLM rate limit.
#
# Auth: f006_admin / 123456 / F006.
# Test URL: 139.196.165.140:8097.

set -e

OUT="C:/Users/Steve/my-prototype-logistics/docs/audits/2026-05-23-sprint12-e2e-framework"
BASE="${CRETAS_TEST_BASE:-http://139.196.165.140:8097}"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
RUN_DIR="$OUT/runs/${TIMESTAMP}_data"
mkdir -p "$RUN_DIR"

# Login
LOGIN_RESP=$(curl -sS -X POST "$BASE/api/mobile/auth/unified-login" \
  -H "Content-Type: application/json" \
  -d '{"username":"f006_admin","password":"123456","factoryId":"F006"}' --max-time 15)
TOKEN=$(echo "$LOGIN_RESP" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('data',{}).get('token','NO_TOKEN'))")
[ "$TOKEN" = "NO_TOKEN" ] && { echo "FAIL: login no token"; echo "$LOGIN_RESP" | head -c 300; exit 1; }
echo "TOKEN length: ${#TOKEN}"
echo "RUN_DIR: $RUN_DIR"
echo

run_test() {
  local name="$1" pathId="$2" userInput="$3" intentCode="$4"
  echo "[$name $pathId] $userInput"
  if [ -n "$intentCode" ]; then
    payload=$(python3 -c "import json,sys;print(json.dumps({'userInput':sys.argv[1],'intentCode':sys.argv[2]}))" "$userInput" "$intentCode")
  else
    payload=$(python3 -c "import json,sys;print(json.dumps({'userInput':sys.argv[1]}))" "$userInput")
  fi
  echo "$payload" > /tmp/payload.json
  curl -sS -X POST "$BASE/api/mobile/F006/ai-intents/execute" \
    -H "Content-Type: application/json; charset=utf-8" \
    -H "Authorization: Bearer $TOKEN" \
    -d @/tmp/payload.json --max-time 180 \
    -o "$RUN_DIR/raw-${name}-${pathId}.json" \
    -w "  HTTP %{http_code} | %{time_total}s\n"
  sleep 12
}

# ==================== 6 Workdesks × 10 real-data paths each = 60 paths ====================

# --- sales-owner real-data ---
run_test "sales-owner" "rd-today"        "今天该跟谁拜访" "DAILY_CUSTOMER_FOLLOWUP"
run_test "sales-owner" "rd-month"        "本月销售业绩排名前 5 的客户" ""
run_test "sales-owner" "rd-quarter"      "本季度新签客户列表" ""
run_test "sales-owner" "rd-alert"        "今天有逾期未跟进的客户吗" ""
run_test "sales-owner" "rd-golden-pass"  "六腾门科技这个客户最近怎么样" ""
run_test "sales-owner" "rd-named"        "客户张总最近 7 天的通话记录" ""
run_test "sales-owner" "rd-opp"          "本周新增商机数量" ""
run_test "sales-owner" "rd-renew"        "下月到期续约的客户" ""
run_test "sales-owner" "rd-rank"         "本月销售冠军是谁" ""
run_test "sales-owner" "rd-stuck"        "停滞超过 30 天的商机" ""

# --- finance-manager real-data ---
run_test "finance-manager" "rd-today"        "今日营收金额" ""
run_test "finance-manager" "rd-month"        "本月营收 vs 上月" "MONTHLY_FINANCIAL_CLOSE"
run_test "finance-manager" "rd-quarter"      "本季度净利润率" ""
run_test "finance-manager" "rd-alert"        "本月应收账款逾期超过 30 天的客户" ""
run_test "finance-manager" "rd-golden-pass"  "本月经营月报已编制了吗" "MONTHLY_FINANCIAL_CLOSE"
run_test "finance-manager" "rd-payable"      "本月应付账款合计" ""
run_test "finance-manager" "rd-invoice"      "上周开票数量" ""
run_test "finance-manager" "rd-cashflow"     "本月现金流入流出对比" ""
run_test "finance-manager" "rd-cost"         "本月生产成本占比" ""
run_test "finance-manager" "rd-margin"       "本月毛利率"  ""

# --- quality-manager real-data ---
run_test "quality-manager" "rd-today"        "今天 HACCP 全部通过吗" "FOOD_SAFETY_RECALL"
run_test "quality-manager" "rd-month"        "本月不合格批次数量" ""
run_test "quality-manager" "rd-quarter"      "本季度质检合格率" ""
run_test "quality-manager" "rd-alert"        "今天有食安告警吗" ""
run_test "quality-manager" "rd-golden-pass"  "本月有没有触发召回流程" "FOOD_SAFETY_RECALL"
run_test "quality-manager" "rd-trace"        "批次 B20260501 的质检结果" ""
run_test "quality-manager" "rd-supplier"     "本月供应商不合格率排名" ""
run_test "quality-manager" "rd-defect"       "本月主要缺陷类型分布" ""
run_test "quality-manager" "rd-rework"       "本月返工批次数" ""
run_test "quality-manager" "rd-cert"         "即将到期的食品安全证书" ""

# --- warehouse-keeper real-data ---
run_test "warehouse-keeper" "rd-today"        "今天要收什么货" "WAREHOUSE_KEEPER_TODAY_TASKS"
run_test "warehouse-keeper" "rd-month"        "本月入库总量" ""
run_test "warehouse-keeper" "rd-quarter"      "本季度入库 vs 出库" ""
run_test "warehouse-keeper" "rd-alert"        "今天有超期未入库的采购单吗" ""
run_test "warehouse-keeper" "rd-golden-pass"  "今日待入库清单" "WAREHOUSE_KEEPER_TODAY_TASKS"
run_test "warehouse-keeper" "rd-inventory"    "原料 X 当前库存量" ""
run_test "warehouse-keeper" "rd-aging"        "库龄超过 60 天的原料" ""
run_test "warehouse-keeper" "rd-loss"         "本月盘亏数量" ""
run_test "warehouse-keeper" "rd-pending"      "待出库订单清单" ""
run_test "warehouse-keeper" "rd-supplier"     "本月入库供应商排名" ""

# --- purchaser real-data ---
run_test "purchaser" "rd-today"        "今天有什么需要下单的" ""
run_test "purchaser" "rd-month"        "本月采购金额" ""
run_test "purchaser" "rd-quarter"      "本季度采购 vs 上季度" ""
run_test "purchaser" "rd-alert"        "原料缺料告警" ""
run_test "purchaser" "rd-golden-pass"  "下周采购清单" "PURCHASER_WEEKLY_PLAN"
run_test "purchaser" "rd-supplier"     "本月供应商交付准时率" ""
run_test "purchaser" "rd-price"        "原料 X 最近一次采购价" ""
run_test "purchaser" "rd-savings"      "本月采购节约金额" ""
run_test "purchaser" "rd-overdue"      "供应商交付逾期清单" ""
run_test "purchaser" "rd-trend"        "本月采购价格趋势" ""

# --- quality-chief real-data ---
run_test "quality-chief" "rd-today"        "今天哪些批次待放行" "QUALITY_CHIEF_WORKDESK"
run_test "quality-chief" "rd-month"        "本月已放行批次数" ""
run_test "quality-chief" "rd-quarter"      "本季度放行通过率" ""
run_test "quality-chief" "rd-alert"        "今天有紧急批次待放行吗" ""
run_test "quality-chief" "rd-golden-pass"  "今日待放行批次详情" "QUALITY_CHIEF_WORKDESK"
run_test "quality-chief" "rd-haccp"        "本月 HACCP CCP 监控完成率" ""
run_test "quality-chief" "rd-supplier"     "本月供应商质量评分" ""
run_test "quality-chief" "rd-deviation"    "本月偏差报告数量" ""
run_test "quality-chief" "rd-corrective"   "本月待关闭的纠正措施" ""
run_test "quality-chief" "rd-audit"        "本季度内审进度" ""

echo
echo "=== DONE: 60 real-data paths captured in $RUN_DIR ==="
ls -1 "$RUN_DIR/" | wc -l
echo "Run analyze: PYTHONIOENCODING=utf-8 python3 $OUT/analyze-expanded.py $RUN_DIR"
