#!/bin/bash
# Re-run 22 rate-limited paths from runs/20260523_140422/ with 12s sleep.
set -e
BASE="${CRETAS_TEST_BASE:-http://139.196.165.140:8097}"
RUN_DIR="C:/Users/Steve/my-prototype-logistics/docs/audits/2026-05-23-sprint12-e2e-framework/runs/20260523_140422"

LOGIN_RESP=$(curl -sS -X POST "$BASE/api/mobile/auth/unified-login" -H "Content-Type: application/json" -d '{"username":"f006_admin","password":"123456","factoryId":"F006"}' --max-time 15)
TOKEN=$(echo "$LOGIN_RESP" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")
echo "TOKEN length: ${#TOKEN}"
echo "RUN_DIR: $RUN_DIR"

run_test() {
  local name="$1" pathId="$2" userInput="$3" intentCode="$4"
  echo "[$name $pathId] $userInput"
  if [ -n "$intentCode" ]; then
    payload=$(python3 -c "import json,sys;print(json.dumps({'userInput':sys.argv[1],'intentCode':sys.argv[2]}))" "$userInput" "$intentCode")
  else
    payload=$(python3 -c "import json,sys;print(json.dumps({'userInput':sys.argv[1]}))" "$userInput")
  fi
  echo "$payload" > /tmp/payload.json
  curl -sS -X POST "$BASE/api/mobile/F006/ai-intents/execute" -H "Content-Type: application/json; charset=utf-8" -H "Authorization: Bearer $TOKEN" -d @/tmp/payload.json --max-time 180 -o "$RUN_DIR/raw-${name}-${pathId}.json" -w "  HTTP %{http_code} | %{time_total}s\n"
  sleep 12
}

# purchaser 10
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

# quality-chief 10
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

# warehouse-keeper 2
run_test "warehouse-keeper" "Bd-cross-fac" "F999的今日入库" "WAREHOUSE_KEEPER_TODAY_TASKS"
run_test "warehouse-keeper" "Bd-vague"     "入库" ""

echo "=== DONE: 22 paths re-captured ==="
