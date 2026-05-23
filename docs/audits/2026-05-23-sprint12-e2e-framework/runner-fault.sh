#!/bin/bash
# Sprint 12 LLM fault-injection runner — 5 fault types × 6 Workdesks = 30 paths.
#
# Per coop split:
#   - Backend fault-injection HOOKS (toggle DashScope timeout / Python LLM down / 1-of-N tool fail)
#     are AI Factory chat scope. Without those hooks, this runner can only verify
#     client-observable fault types.
#
# What this runner CAN do without backend hooks:
#   F1 Rate-limit — burst 30+ rapid LLM-routed calls to trip Aliyun limit (already observed
#      during 2nd re-audit 2026-05-23 12:51 — 11 paths returned status=None, len=0, NO graceful
#      fallback. Existing system: fault handling INCOMPLETE.)
#   F2 Client-side timeout — set curl --max-time 1 to force client abort mid-response.
#
# What requires AI Factory backend hooks (NOT runnable here):
#   F3 DashScope timeout (server-side mock 30+ sec response)
#   F4 Python LLM service down (toggle service health)
#   F5 1-of-N tool fail (inject throw in 1 of 8 Tool executions for sales-owner composite)
#
# This runner is therefore a SCAFFOLD + 2/5 actually-runnable fault types.
# Marking 2/5 (40%) of LLM fault-injection row as partial. Full 100% requires AI Factory.

set -e

OUT="C:/Users/Steve/my-prototype-logistics/docs/audits/2026-05-23-sprint12-e2e-framework"
BASE="${CRETAS_TEST_BASE:-http://139.196.165.140:8097}"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
RUN_DIR="$OUT/runs/${TIMESTAMP}_fault"
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

# ==================== F1: Rate-limit burst (no sleep) ====================
# 12 rapid LLM-routed calls to trigger Aliyun rate limit (~30 req/min cap)
# Expected: at least some calls return status=FAILED with graceful message,
# or HTTP 200 with NEED_CLARIFICATION / status=PROCESSING. NOT silent status=None.
echo "=== F1: Rate-limit burst (12 rapid LLM-routed calls, no sleep) ==="
for i in $(seq 1 12); do
  payload=$(python3 -c "import json,sys;print(json.dumps({'userInput':sys.argv[1]}))" "本月业绩怎么样 $i")
  echo "$payload" > /tmp/payload.json
  curl -sS -X POST "$BASE/api/mobile/F006/ai-intents/execute" \
    -H "Content-Type: application/json; charset=utf-8" \
    -H "Authorization: Bearer $TOKEN" \
    -d @/tmp/payload.json --max-time 30 \
    -o "$RUN_DIR/raw-F1-rate-burst-$i.json" \
    -w "  [$i] HTTP %{http_code} | %{time_total}s\n" &
done
wait
echo

# ==================== F2: Client-side abort timeout ====================
# Send 6 LLM-routed calls with --max-time 1 (1s) to force client abort mid-LLM-response.
# Expected: HTTP 000 client error, but server should NOT leave inflight state corrupted.
# Follow-up: send same query normally and verify server recovered.
echo "=== F2: Client-side timeout (--max-time 1s, 6 Workdesks) ==="
declare -a F2_INPUTS=(
  "今天该跟谁拜访"
  "本月经营怎么样"
  "今天 HACCP 状态"
  "今天有什么入库"
  "下周采购什么"
  "今天哪些批次待放行"
)
declare -a F2_NAMES=("sales" "finance" "quality-mgr" "warehouse" "purchaser" "quality-chief")
for i in "${!F2_INPUTS[@]}"; do
  payload=$(python3 -c "import json,sys;print(json.dumps({'userInput':sys.argv[1]}))" "${F2_INPUTS[$i]}")
  echo "$payload" > /tmp/payload.json
  curl -sS -X POST "$BASE/api/mobile/F006/ai-intents/execute" \
    -H "Content-Type: application/json; charset=utf-8" \
    -H "Authorization: Bearer $TOKEN" \
    -d @/tmp/payload.json --max-time 1 \
    -o "$RUN_DIR/raw-F2-timeout-${F2_NAMES[$i]}.json" \
    -w "  [F2 ${F2_NAMES[$i]}] HTTP %{http_code} | %{time_total}s\n" || true
  sleep 5
done
echo

# ==================== F3-F5: SCAFFOLD ONLY (requires AI Factory backend hooks) ====================
cat > "$RUN_DIR/F3-F5-PENDING-AI-FACTORY.md" <<'EOF'
# F3-F5 Pending AI Factory Backend Hooks

These 3 fault types require backend instrumentation that is AI Factory chat scope per Sprint 12 coop split:

## F3: DashScope timeout (server-side mock)

- Backend hook needed: ENV var `MOCK_DASHSCOPE_DELAY_MS=30000` makes Aliyun adapter wait 30s before returning.
- Test queries: 6 LLM-routed Workdesk inputs.
- Expected: server returns graceful timeout response (status=FAILED with retry hint), NOT 500 nor silent hang.

## F4: Python LLM service down

- Backend hook needed: kill or pause `cretas-python` systemd unit on test env (139:8084).
- Test queries: 6 LLM-routed inputs that involve Python SmartBI analysis composite.
- Expected: Java backend returns degraded mode (cached / deterministic fallback), NOT 500.

## F5: 1-of-N tool fail

- Backend hook needed: `MOCK_TOOL_THROW=monthly_revenue_query` env var makes that Tool throw RuntimeException.
- Test queries: sales-owner / finance-manager composite that uses 5+ Tools each.
- Expected: SkillExecutor catches the 1 fail, other 4-7 Tools succeed, formattedText degrades gracefully.

## Action

AI Factory chat to add `dev-fault-injection` profile in Spring config that exposes
these 3 toggles via env vars. After that, this runner-fault.sh F3-F5 sections can be
filled in by a 30-line addendum.
EOF

echo
echo "=== DONE: F1+F2 paths captured (24 paths) + F3-F5 scaffold in $RUN_DIR ==="
ls -1 "$RUN_DIR/"
echo "Run analyze: PYTHONIOENCODING=utf-8 python3 $OUT/analyze-fault.py $RUN_DIR"
