# scripts/qa-multiturn-context-probe.sh
# 餐饮 chat 多轮上下文继承 (X1 Part B) prod 验证探针。
# 用法 (在服务器 localhost 跑, 绕国内 ISP 的 SSH stream RST):
#   CRETAS_JWT=<qhj_prod token> ROUND1="营收趋势" ROUND2="上个月呢" \
#     ssh root@47.100.235.168 'bash -s' < scripts/qa-multiturn-context-probe.sh
# JWT 由操作者提供 (qhj_prod, role=factory_super_admin, factoryId 在 token payload):
#   通过 /api/mobile/auth/* 登录 qhj_prod 账号获取, 或复用现有有效 token。凭证不写进脚本/repo。
# Java 后端 10010/10020 仅对 nginx 网关开放, 故本探针在服务器 localhost 命中活跃蓝绿端口。
set -u
JWT="${CRETAS_JWT:?need CRETAS_JWT (qhj_prod factory_super_admin token)}"
R1="${ROUND1:-营收趋势}"
R2="${ROUND2:-上个月呢}"
FACTORY="RES_3101_009"

# 蓝绿端口探测: 优先 10020(green), 回退 10010(blue)
if curl -s -o /dev/null -w '%{http_code}' "http://localhost:10020/api/mobile/health" | grep -q 200; then
  PORT=10020
else
  PORT=10010
fi
SID="mt-verify-$(date +%s)"
echo "PORT=$PORT  SID=$SID"
EXEC="http://localhost:$PORT/api/mobile/$FACTORY/ai-intents/execute"

post() {  # $1 = userInput
  curl -s -X POST "$EXEC" \
    -H "Authorization: Bearer $JWT" -H "Content-Type: application/json" \
    -d "{\"userInput\":\"$1\",\"sessionId\":\"$SID\"}"
}
dbcheck() {
  PGPASSWORD=cretas123 psql -h 127.0.0.1 -U cretas_user -d cretas_prod_db -tAc \
    "SELECT session_id, last_intent_code FROM conversation_memory WHERE session_id='$SID';"
}

echo "=== ROUND 1: $R1 ==="; post "$R1" | head -c 600; echo
echo "=== DB after round 1 (expect last_intent_code populated AFTER fix; null/empty BEFORE fix) ==="; dbcheck
echo "=== ROUND 2: $R2 ==="; post "$R2" | head -c 600; echo
echo "=== logs (X1-Continuation marker appears only AFTER fix) ==="
grep -aE "X1-Continuation|$SID" /www/wwwroot/cretas/logs/cretas-backend.log | tail -30
