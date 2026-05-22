#!/bin/bash
# Workdesk AI output quality audit — 6 Workdesks × 2 paths = 12 tests
# Run from anywhere; uses absolute paths.

set -e

OUT="C:/Users/Steve/my-prototype-logistics/docs/audits/2026-05-22-workdesk-ai-output-quality"
BASE="http://139.196.165.140:8086"

# Re-login (token TTL 24h but in case)
LOGIN_RESP=$(curl -sS -X POST "$BASE/api/mobile/auth/unified-login" \
  -H "Content-Type: application/json" \
  -d '{"username":"f006_admin","password":"123456","factoryId":"F006"}' --max-time 15)
TOKEN=$(echo "$LOGIN_RESP" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")
echo "TOKEN length: ${#TOKEN}"

run_test() {
  local name="$1"
  local userInput="$2"
  local intentCode="$3"
  local path="$4"

  echo ""
  echo "=== $name $path ==="
  echo "input: $userInput"
  echo "intentCode: ${intentCode:-<none — LLM routed>}"

  if [ -n "$intentCode" ]; then
    payload=$(python3 -c "import json;print(json.dumps({'userInput':'$userInput','intentCode':'$intentCode'}))")
  else
    payload=$(python3 -c "import json;print(json.dumps({'userInput':'$userInput'}))")
  fi

  echo "$payload" > /tmp/payload.json
  curl -sS -X POST "$BASE/api/mobile/F006/ai-intents/execute" \
    -H "Content-Type: application/json; charset=utf-8" \
    -H "Authorization: Bearer $TOKEN" \
    -d @/tmp/payload.json \
    --max-time 120 \
    -o "$OUT/raw-${name}-${path}.json" \
    -w "HTTP %{http_code} | time %{time_total}s\n"
}

# Path A = with intentCode (force) | Path B = without intentCode (LLM-routed synonym)

# 1. sales-owner
run_test "sales-owner" "今天该跟谁?" "DAILY_CUSTOMER_FOLLOWUP" "pathA"
run_test "sales-owner" "今天哪些客户需要拜访" "" "pathB"

# 2. finance-manager (KNOWN BAD CASE — spot-check)
run_test "finance-manager" "本月经营怎么样?" "MONTHLY_FINANCIAL_CLOSE" "pathA"
run_test "finance-manager" "这个月业绩如何" "" "pathB"

# 3. quality-manager
run_test "quality-manager" "今天 HACCP 监控全通过吗?" "FOOD_SAFETY_RECALL" "pathA"
run_test "quality-manager" "今天质量监控有问题吗" "" "pathB"

# 4. warehouse-keeper
run_test "warehouse-keeper" "今天要收什么货?" "WAREHOUSE_KEEPER_TODAY_TASKS" "pathA"
run_test "warehouse-keeper" "今天有什么入库" "" "pathB"

# 5. purchaser
run_test "purchaser" "下周采购什么?" "PURCHASER_WEEKLY_PLAN" "pathA"
run_test "purchaser" "下周需要进货吗" "" "pathB"

# 6. quality-chief
run_test "quality-chief" "今天哪些批次待放行?" "QUALITY_CHIEF_WORKDESK" "pathA"
run_test "quality-chief" "今天有什么批次需要审批放行" "" "pathB"

echo ""
echo "=== ALL DONE ==="
ls -la "$OUT/" | grep raw
