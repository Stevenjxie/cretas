#!/bin/bash
# Sprint 12 Workdesk E2E framework — expanded from baseline 12 paths to 60 paths.
#
# Per Sprint 12 close-gate "E2E total rounds ≥120 (6×20)":
#   - This runner: 60 paths (10 per Workdesk: 2 base + 3 synonyms + 5 boundary)
#   - LLM fault-injection (5 more per Workdesk) handled by separate runner-fault.sh
#   - Real-data scenarios (5 more per Workdesk) handled by separate runner-data.sh
#
# Output: raw-{workdesk}-{path-id}.json files in $OUT, analyzed by analyze.py
#
# Auth: f006_admin / 123456 / F006 per existing audit account.
# Test URL: 139.196.165.140:8097 (test env) per Steve HARD rule.

set -e

OUT="C:/Users/Steve/my-prototype-logistics/docs/audits/2026-05-23-sprint12-e2e-framework"
BASE="${CRETAS_TEST_BASE:-http://139.196.165.140:8097}"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
RUN_DIR="$OUT/runs/$TIMESTAMP"
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
  local name="$1" pathId="$2" userInput="$3" intentCode="$4" headers="${5:-}"
  echo "[$name $pathId] $userInput"
  if [ -n "$intentCode" ]; then
    payload=$(python3 -c "import json,sys;print(json.dumps({'userInput':sys.argv[1],'intentCode':sys.argv[2]}))" "$userInput" "$intentCode")
  else
    payload=$(python3 -c "import json,sys;print(json.dumps({'userInput':sys.argv[1]}))" "$userInput")
  fi
  echo "$payload" > /tmp/payload.json
  local hdr_args=""
  if [ -n "$headers" ]; then hdr_args="-H $headers"; fi
  curl -sS -X POST "$BASE/api/mobile/F006/ai-intents/execute" \
    -H "Content-Type: application/json; charset=utf-8" \
    -H "Authorization: Bearer $TOKEN" \
    $hdr_args \
    -d @/tmp/payload.json --max-time 180 \
    -o "$RUN_DIR/raw-${name}-${pathId}.json" \
    -w "  HTTP %{http_code} | %{time_total}s\n"
  # Sprint 12: 12s sleep keeps us under Aliyun LLM rate limit (~30 req/min).
  # Without this, burst mode (60 paths in <60s) trips rate limit for ~20 paths.
  sleep 12
}

# ==================== 6 Workdesks × 10 paths each = 60 paths ====================

# --- sales-owner ---
run_test "sales-owner" "A-base"        "今天该跟谁?" "DAILY_CUSTOMER_FOLLOWUP"
run_test "sales-owner" "B-base"        "今天哪些客户需要拜访" ""
run_test "sales-owner" "B-syn1"        "今日要跟进哪些客户" ""
run_test "sales-owner" "B-syn2"        "本周客户拜访计划" ""
run_test "sales-owner" "B-syn3"        "今日重点客户列表" ""
run_test "sales-owner" "Bd-empty"      "1900年1月1日有什么客户" ""
run_test "sales-owner" "Bd-large"      "近三年所有客户的所有跟进记录" ""
run_test "sales-owner" "Bd-period"     "上季度跟进了哪些客户" ""
run_test "sales-owner" "Bd-cross-fac"  "F999工厂今天跟谁?" "DAILY_CUSTOMER_FOLLOWUP"
run_test "sales-owner" "Bd-vague"      "客户" ""

# --- finance-manager ---
run_test "finance-manager" "A-base"       "本月经营怎么样?" "MONTHLY_FINANCIAL_CLOSE"
run_test "finance-manager" "B-base"       "这个月业绩如何" ""
run_test "finance-manager" "B-syn1"       "本月财务汇报" ""
run_test "finance-manager" "B-syn2"       "经营月度收尾" ""
run_test "finance-manager" "B-syn3"       "上月业绩总结" ""
run_test "finance-manager" "Bd-empty"     "未来 2099 年经营如何" ""
run_test "finance-manager" "Bd-large"     "近十年财务数据" ""
run_test "finance-manager" "Bd-period"    "上季度营收" ""
run_test "finance-manager" "Bd-cross-fac" "F999本月经营" "MONTHLY_FINANCIAL_CLOSE"
run_test "finance-manager" "Bd-vague"     "财务" ""

# --- quality-manager ---
run_test "quality-manager" "A-base"       "今天 HACCP 监控全通过吗?" "FOOD_SAFETY_RECALL"
run_test "quality-manager" "B-base"       "今天质量监控有问题吗" ""
run_test "quality-manager" "B-syn1"       "今日 HACCP 状态" ""
run_test "quality-manager" "B-syn2"       "本周食安告警" ""
run_test "quality-manager" "B-syn3"       "今天质检风险" ""
run_test "quality-manager" "Bd-empty"     "未来一周食安告警" ""
run_test "quality-manager" "Bd-large"     "近三年所有 HACCP 监控" ""
run_test "quality-manager" "Bd-period"    "上月质量监控汇总" ""
run_test "quality-manager" "Bd-cross-fac" "F999的HACCP" "FOOD_SAFETY_RECALL"
run_test "quality-manager" "Bd-vague"     "质量" ""

# --- warehouse-keeper ---
run_test "warehouse-keeper" "A-base"       "今天要收什么货?" "WAREHOUSE_KEEPER_TODAY_TASKS"
run_test "warehouse-keeper" "B-base"       "今天有什么入库" ""
run_test "warehouse-keeper" "B-syn1"       "今日收货清单" ""
run_test "warehouse-keeper" "B-syn2"       "今天有什么入库单" ""
run_test "warehouse-keeper" "B-syn3"       "本日待入库" ""
run_test "warehouse-keeper" "Bd-empty"     "未来一年入库计划" ""
run_test "warehouse-keeper" "Bd-large"     "近三年入库流水" ""
run_test "warehouse-keeper" "Bd-period"    "上月入库统计" ""
run_test "warehouse-keeper" "Bd-cross-fac" "F999的今日入库" "WAREHOUSE_KEEPER_TODAY_TASKS"
run_test "warehouse-keeper" "Bd-vague"     "入库" ""

# --- purchaser ---
run_test "purchaser" "A-base"       "下周采购什么?" "PURCHASER_WEEKLY_PLAN"
run_test "purchaser" "B-base"       "下周需要进货吗" ""
run_test "purchaser" "B-syn1"       "下周采购建议" ""
run_test "purchaser" "B-syn2"       "本周缺料分析" ""
run_test "purchaser" "B-syn3"       "下周补货清单" ""
run_test "purchaser" "Bd-empty"     "未来一年采购计划" ""
run_test "purchaser" "Bd-large"     "近三年所有采购历史" ""
run_test "purchaser" "Bd-period"    "上季度采购汇总" ""
run_test "purchaser" "Bd-cross-fac" "F999下周采购" "PURCHASER_WEEKLY_PLAN"
run_test "purchaser" "Bd-vague"     "采购" ""

# --- quality-chief ---
run_test "quality-chief" "A-base"       "今天哪些批次待放行?" "QUALITY_CHIEF_WORKDESK"
run_test "quality-chief" "B-base"       "今天有什么批次需要审批放行" ""
run_test "quality-chief" "B-syn1"       "今日待放行批次" ""
run_test "quality-chief" "B-syn2"       "本日批次审批列表" ""
run_test "quality-chief" "B-syn3"       "今日待审批批次" ""
run_test "quality-chief" "Bd-empty"     "未来一年待放行" ""
run_test "quality-chief" "Bd-large"     "近三年所有放行批次" ""
run_test "quality-chief" "Bd-period"    "上月放行批次" ""
run_test "quality-chief" "Bd-cross-fac" "F999今日批次" "QUALITY_CHIEF_WORKDESK"
run_test "quality-chief" "Bd-vague"     "批次" ""

echo
echo "=== DONE: 60 paths captured ==="
ls -1 "$RUN_DIR/" | wc -l
echo "Run analyze: PYTHONIOENCODING=utf-8 python3 $OUT/analyze-expanded.py $RUN_DIR"
