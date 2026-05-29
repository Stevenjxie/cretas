#!/bin/bash
set -e
BASE="${CRETAS_TEST_BASE:-http://139.196.165.140:8097}"
RUN_DIR="C:/Users/Steve/my-prototype-logistics/docs/audits/2026-05-23-sprint12-e2e-framework/runs/20260529_003737"

LOGIN_RESP=$(curl -sS -X POST "$BASE/api/mobile/auth/unified-login" -H "Content-Type: application/json" -d '{"username":"f006_admin","password":"123456","factoryId":"F006"}' --max-time 15)
TOKEN=$(echo "$LOGIN_RESP" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")

run_test() {
  local name="$1" pathId="$2" userInput="$3" intentCode="$4"
  if [ -n "$intentCode" ]; then
    payload=$(python3 -c "import json,sys;print(json.dumps({'userInput':sys.argv[1],'intentCode':sys.argv[2]}))" "$userInput" "$intentCode")
  else
    payload=$(python3 -c "import json,sys;print(json.dumps({'userInput':sys.argv[1]}))" "$userInput")
  fi
  echo "$payload" > /tmp/payload.json
  curl -sS -X POST "$BASE/api/mobile/F006/ai-intents/execute" -H "Content-Type: application/json; charset=utf-8" -H "Authorization: Bearer $TOKEN" -d @/tmp/payload.json --max-time 180 -o "$RUN_DIR/raw-${name}-${pathId}.json" -w "[${name} ${pathId}] HTTP %{http_code}\n"
  sleep 20
}

run_test "purchaser" "Bd-cross-fac" "F999下周采购" "PURCHASER_WEEKLY_PLAN"
run_test "purchaser" "Bd-large"     "近三年所有采购历史" ""
run_test "purchaser" "Bd-period"    "上季度采购汇总" ""
run_test "purchaser" "Bd-vague"     "采购" ""

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

echo "DONE"
