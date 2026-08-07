#!/usr/bin/env bash
# 餐饮 AI 的 G0 基线 + G1 归宿表测量。
#
# ⚠️ **必须在服务器上跑**(root@47.100.235.168): 它读 .env.prod 取库密码, 且本机
#    直连不到 47 的业务端口(安全组只放行网关 139)。
#      scp scripts/dev/measure-restaurant-g0g1.sh root@47.100.235.168:/tmp/
#      ssh root@47.100.235.168 "bash /tmp/measure-restaurant-g0g1.sh"
#
# 输出每个问句的: 耗时ms / token / LLM 调用次数 / 归宿(A/B/C/D)。
#   A 有答案 · B 诚实缺数据 · C 不在范围 · **D 反问 —— goal 明令禁止的第四种**
#
# token 与 LLM 次数取自 `smart_bi_llm_usage` 表在每次请求**前后的增量**, 不是估算。
# 每题之间 sleep 2 等异步写入落库 —— 去掉会让计数偏小。
#
# 2026-08-07 首测基线: A=2 / B=0 / C=1 / **D=12(80%)**;
#   D 类烧掉 34,113 token / 14 次 LLM 调用却一条答案都没产出
#   (占总量的 77% / 82%)。P95=10,228ms, 中位=4,222ms。
#
# 🔑 改善的判据不是「更快了」, 是 **D 归零**。改完重跑本脚本对比。

set -u
cd /www/wwwroot/cretas
SP=$(grep -oP '(?<=^SMARTBI_DB_PASSWORD=).*' .env.prod)
PSQL="psql -h localhost -U smartbi_user -d smartbi_prod_db -tAc"
PORT=$(ss -lntp 2>/dev/null | grep -oE ':(10010|10020)' | tr -d ':' | sort -u | head -1)
TOK=$(curl -s -X POST "http://127.0.0.1:$PORT/api/mobile/auth/unified-login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"mock_ops","password":"123456","deviceInfo":{"deviceId":"g0g1","deviceModel":"probe","platform":"web","osVersion":"1"}}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["token"])')

Q=(
"最近30天总营收是多少"
"加权毛利率是多少"
"哪个菜卖得最好"
"毛利最低的菜品有哪些"
"外卖和堂食各占多少"
"哪个时段生意最好"
"食材成本占营收多少"
"最近损耗情况怎么样"
"库存有什么要注意的"
"哪个供应商报价最贵"
"员工人效怎么样"
"营收趋势怎么样"
"各门店对比如何"
"折扣力度多大"
"明天天气怎么样"
)

printf '%-24s|%7s|%7s|%6s|%-34s|%s\n' "问句" "耗时ms" "token" "LLM次" "code" "归宿"
for q in "${Q[@]}"; do
  B_CNT=$(PGPASSWORD=$SP $PSQL "SELECT count(*) FROM smart_bi_llm_usage;" | tr -d ' ')
  B_TOK=$(PGPASSWORD=$SP $PSQL "SELECT COALESCE(SUM(total_tokens),0) FROM smart_bi_llm_usage;" | tr -d ' ')
  s=$(date +%s%3N)
  r=$(curl -s -m 200 -X POST "http://127.0.0.1:8083/api/smartbi/gold/restaurant/tiered-answer" \
      -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
      -d "$(python3 -c "import json,sys;print(json.dumps({'factory_id':'MOCK_REST','query':sys.argv[1]}))" "$q")")
  e=$(date +%s%3N)
  sleep 2
  A_CNT=$(PGPASSWORD=$SP $PSQL "SELECT count(*) FROM smart_bi_llm_usage;" | tr -d ' ')
  A_TOK=$(PGPASSWORD=$SP $PSQL "SELECT COALESCE(SUM(total_tokens),0) FROM smart_bi_llm_usage;" | tr -d ' ')
  echo "$r" | python3 -c "
import sys,json
d=json.load(sys.stdin)
kind=d.get('kind'); code=d.get('code') or ''
txt=(d.get('answer_text') or '')
if kind=='clarification' and '不在我的数据范围' not in txt:
    verdict='D-反问(禁止)'
elif code=='RESTAURANT_OPS_OUT_OF_DOMAIN':
    verdict='C-不在范围'
elif d.get('contract_pass') and d.get('kpis') is not None and code:
    verdict='A-有答案'
else:
    verdict='B?-需人看'
print('%s|%s|%s' % (code[:34], verdict, txt[:0]))
" > /tmp/g0g1_verdict.txt 2>/dev/null || echo "PARSE_FAIL||" > /tmp/g0g1_verdict.txt
  IFS='|' read -r CODE VERDICT _ < /tmp/g0g1_verdict.txt
  printf '%-24s|%7s|%7s|%6s|%-34s|%s\n' "${q:0:22}" "$((e-s))" "$((A_TOK-B_TOK))" "$((A_CNT-B_CNT))" "$CODE" "$VERDICT"
done
