#!/bin/bash
# Re-run only the 11 rate-limited paths from runs/20260523_125132/ with 12s sleep.
# Aliyun LLM rate limit ~30 req/min so 12s = 5 req/min safe margin.
# Replaces those raw-*.json files in-place so analyze-expanded.py can pick them up.

set -e

OUT="C:/Users/Steve/my-prototype-logistics/docs/audits/2026-05-23-sprint12-e2e-framework"
BASE="${CRETAS_TEST_BASE:-http://139.196.165.140:8097}"
RUN_DIR="$OUT/runs/20260523_125132"

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
  sleep 12  # rate-limit cushion
}

# 11 rate-limited paths from prior run:
run_test "purchaser" "A-base"        "下周采购什么?" "PURCHASER_WEEKLY_PLAN"
run_test "purchaser" "B-base"        "下周需要进货吗" ""
run_test "purchaser" "B-syn1"        "下周采购建议" ""
run_test "purchaser" "B-syn2"        "本周缺料分析" ""
run_test "purchaser" "B-syn3"        "下周补货清单" ""
run_test "purchaser" "Bd-empty"      "未来一年采购计划" ""
run_test "purchaser" "Bd-large"      "近三年所有采购历史" ""
run_test "warehouse-keeper" "Bd-cross-fac" "F999的今日入库" "WAREHOUSE_KEEPER_TODAY_TASKS"
run_test "warehouse-keeper" "Bd-large"     "近三年入库流水" ""
run_test "warehouse-keeper" "Bd-period"    "上月入库统计" ""
run_test "warehouse-keeper" "Bd-vague"     "入库" ""

echo
echo "=== DONE: 11 paths re-captured in $RUN_DIR ==="
echo "Run analyze: PYTHONIOENCODING=utf-8 python3 $OUT/analyze-expanded.py $RUN_DIR"
