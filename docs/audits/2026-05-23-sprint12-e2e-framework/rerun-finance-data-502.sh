#!/bin/bash
set -e
BASE="${CRETAS_TEST_BASE:-http://139.196.165.140:8097}"
RUN_DIR="C:/Users/Steve/my-prototype-logistics/docs/audits/2026-05-23-sprint12-e2e-framework/runs/20260529_003750_data"
LOGIN_RESP=$(curl -sS -X POST "$BASE/api/mobile/auth/unified-login" -H "Content-Type: application/json" -d '{"username":"f006_admin","password":"123456","factoryId":"F006"}' --max-time 15)
TOKEN=$(echo "$LOGIN_RESP" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")

run_test() {
  local name="$1" pathId="$2" userInput="$3"
  payload=$(python3 -c "import json,sys;print(json.dumps({'userInput':sys.argv[1]}))" "$userInput")
  echo "$payload" > /tmp/payload.json
  curl -sS -X POST "$BASE/api/mobile/F006/ai-intents/execute" -H "Content-Type: application/json; charset=utf-8" -H "Authorization: Bearer $TOKEN" -d @/tmp/payload.json --max-time 180 -o "$RUN_DIR/raw-${name}-${pathId}.json" -w "[${name} ${pathId}] HTTP %{http_code}\n"
  sleep 18
}

run_test "finance-manager" "rd-alert"     "本月应收账款逾期超过 30 天的客户"
run_test "finance-manager" "rd-cashflow"  "本月现金流入流出对比"
run_test "finance-manager" "rd-cost"      "本月生产成本占比"
run_test "finance-manager" "rd-golden-pass" "本月经营月报已编制了吗"
run_test "finance-manager" "rd-invoice"   "上周开票数量"
run_test "finance-manager" "rd-month"     "本月营收 vs 上月"
run_test "finance-manager" "rd-payable"   "本月应付账款合计"
run_test "finance-manager" "rd-quarter"   "本季度净利润率"

echo "DONE"
