#!/bin/bash
# Robust combined sequential runner (post-#308). NO set -e: survives backend restarts/timeouts.
# 16s spacing every call (Aliyun rate-limit HARD rule) + inline retry-on-pollution (empty/None/502).
BASE="${CRETAS_TEST_BASE:-http://139.196.165.140:8097}"
ROOT="C:/Users/Steve/my-prototype-logistics/docs/audits/2026-05-23-sprint12-e2e-framework/runs"
TS=$(date +%Y%m%d_%H%M%S)
BDIR="$ROOT/${TS}_post308"
DDIR="$ROOT/${TS}_post308_data"
mkdir -p "$BDIR" "$DDIR"
echo "BDIR=$BDIR"
echo "DDIR=$DDIR"

login() {
  for i in 1 2 3; do
    TOKEN=$(curl -sS -X POST "$BASE/api/mobile/auth/unified-login" -H "Content-Type: application/json"       -d '{"username":"f006_admin","password":"123456","factoryId":"F006"}' --max-time 15       | python3 -c "import sys,json;print(json.load(sys.stdin).get('data',{}).get('token',''))" 2>/dev/null)
    if [ -n "$TOKEN" ]; then echo "TOKEN len ${#TOKEN}"; return; fi
    echo "login retry $i (backend down?)"; sleep 20
  done
}
login

# valid = file parses + data.status is not null
isvalid() { python3 -c "import json,sys;d=json.load(open(sys.argv[1],encoding='utf-8'));sys.exit(0 if d.get('data',{}).get('status') is not None else 1)" "$1" 2>/dev/null; }

rt() { local dir="$1" name="$2" pathId="$3" input="$4" intent="$5"
  if [ -n "$intent" ]; then payload=$(python3 -c "import json,sys;print(json.dumps({'userInput':sys.argv[1],'intentCode':sys.argv[2]}))" "$input" "$intent")
  else payload=$(python3 -c "import json,sys;print(json.dumps({'userInput':sys.argv[1]}))" "$input"); fi
  echo "$payload" > /tmp/cmb.json
  local out="$dir/raw-${name}-${pathId}.json" tries=0 code
  while [ $tries -lt 3 ]; do
    code=$(curl -sS -X POST "$BASE/api/mobile/F006/ai-intents/execute" -H "Content-Type: application/json; charset=utf-8"       -H "Authorization: Bearer $TOKEN" -d @/tmp/cmb.json --max-time 120 -o "$out" -w "%{http_code}" 2>/dev/null || echo "000")
    if [ "$code" = "200" ] && isvalid "$out"; then echo "[$name-$pathId] OK http=$code (try $((tries+1)))"; break; fi
    tries=$((tries+1)); echo "[$name-$pathId] RETRY http=$code (try $tries) — re-login+wait"; sleep 22; login
  done
  sleep 16
}

rt "$BDIR" "sales-owner" "A-base"        "今天该跟谁?" "DAILY_CUSTOMER_FOLLOWUP"
rt "$BDIR" "sales-owner" "B-base"        "今天哪些客户需要拜访" ""
rt "$BDIR" "sales-owner" "B-syn1"        "今日要跟进哪些客户" ""
rt "$BDIR" "sales-owner" "B-syn2"        "本周客户拜访计划" ""
rt "$BDIR" "sales-owner" "B-syn3"        "今日重点客户列表" ""
rt "$BDIR" "sales-owner" "Bd-empty"      "1900年1月1日有什么客户" ""
rt "$BDIR" "sales-owner" "Bd-large"      "近三年所有客户的所有跟进记录" ""
rt "$BDIR" "sales-owner" "Bd-period"     "上季度跟进了哪些客户" ""
rt "$BDIR" "sales-owner" "Bd-cross-fac"  "F999工厂今天跟谁?" "DAILY_CUSTOMER_FOLLOWUP"
rt "$BDIR" "sales-owner" "Bd-vague"      "客户" ""
rt "$BDIR" "finance-manager" "A-base"       "本月经营怎么样?" "MONTHLY_FINANCIAL_CLOSE"
rt "$BDIR" "finance-manager" "B-base"       "这个月业绩如何" ""
rt "$BDIR" "finance-manager" "B-syn1"       "本月财务汇报" ""
rt "$BDIR" "finance-manager" "B-syn2"       "经营月度收尾" ""
rt "$BDIR" "finance-manager" "B-syn3"       "上月业绩总结" ""
rt "$BDIR" "finance-manager" "Bd-empty"     "未来 2099 年经营如何" ""
rt "$BDIR" "finance-manager" "Bd-large"     "近十年财务数据" ""
rt "$BDIR" "finance-manager" "Bd-period"    "上季度营收" ""
rt "$BDIR" "finance-manager" "Bd-cross-fac" "F999本月经营" "MONTHLY_FINANCIAL_CLOSE"
rt "$BDIR" "finance-manager" "Bd-vague"     "财务" ""
rt "$BDIR" "quality-manager" "A-base"       "今天 HACCP 监控全通过吗?" "FOOD_SAFETY_RECALL"
rt "$BDIR" "quality-manager" "B-base"       "今天质量监控有问题吗" ""
rt "$BDIR" "quality-manager" "B-syn1"       "今日 HACCP 状态" ""
rt "$BDIR" "quality-manager" "B-syn2"       "本周食安告警" ""
rt "$BDIR" "quality-manager" "B-syn3"       "今天质检风险" ""
rt "$BDIR" "quality-manager" "Bd-empty"     "未来一周食安告警" ""
rt "$BDIR" "quality-manager" "Bd-large"     "近三年所有 HACCP 监控" ""
rt "$BDIR" "quality-manager" "Bd-period"    "上月质量监控汇总" ""
rt "$BDIR" "quality-manager" "Bd-cross-fac" "F999的HACCP" "FOOD_SAFETY_RECALL"
rt "$BDIR" "quality-manager" "Bd-vague"     "质量" ""
rt "$BDIR" "warehouse-keeper" "A-base"       "今天要收什么货?" "WAREHOUSE_KEEPER_TODAY_TASKS"
rt "$BDIR" "warehouse-keeper" "B-base"       "今天有什么入库" ""
rt "$BDIR" "warehouse-keeper" "B-syn1"       "今日收货清单" ""
rt "$BDIR" "warehouse-keeper" "B-syn2"       "今天有什么入库单" ""
rt "$BDIR" "warehouse-keeper" "B-syn3"       "本日待入库" ""
rt "$BDIR" "warehouse-keeper" "Bd-empty"     "未来一年入库计划" ""
rt "$BDIR" "warehouse-keeper" "Bd-large"     "近三年入库流水" ""
rt "$BDIR" "warehouse-keeper" "Bd-period"    "上月入库统计" ""
rt "$BDIR" "warehouse-keeper" "Bd-cross-fac" "F999的今日入库" "WAREHOUSE_KEEPER_TODAY_TASKS"
rt "$BDIR" "warehouse-keeper" "Bd-vague"     "入库" ""
rt "$BDIR" "purchaser" "A-base"       "下周采购什么?" "PURCHASER_WEEKLY_PLAN"
rt "$BDIR" "purchaser" "B-base"       "下周需要进货吗" ""
rt "$BDIR" "purchaser" "B-syn1"       "下周采购建议" ""
rt "$BDIR" "purchaser" "B-syn2"       "本周缺料分析" ""
rt "$BDIR" "purchaser" "B-syn3"       "下周补货清单" ""
rt "$BDIR" "purchaser" "Bd-empty"     "未来一年采购计划" ""
rt "$BDIR" "purchaser" "Bd-large"     "近三年所有采购历史" ""
rt "$BDIR" "purchaser" "Bd-period"    "上季度采购汇总" ""
rt "$BDIR" "purchaser" "Bd-cross-fac" "F999下周采购" "PURCHASER_WEEKLY_PLAN"
rt "$BDIR" "purchaser" "Bd-vague"     "采购" ""
rt "$BDIR" "quality-chief" "A-base"       "今天哪些批次待放行?" "QUALITY_CHIEF_WORKDESK"
rt "$BDIR" "quality-chief" "B-base"       "今天有什么批次需要审批放行" ""
rt "$BDIR" "quality-chief" "B-syn1"       "今日待放行批次" ""
rt "$BDIR" "quality-chief" "B-syn2"       "本日批次审批列表" ""
rt "$BDIR" "quality-chief" "B-syn3"       "今日待审批批次" ""
rt "$BDIR" "quality-chief" "Bd-empty"     "未来一年待放行" ""
rt "$BDIR" "quality-chief" "Bd-large"     "近三年所有放行批次" ""
rt "$BDIR" "quality-chief" "Bd-period"    "上月放行批次" ""
rt "$BDIR" "quality-chief" "Bd-cross-fac" "F999今日批次" "QUALITY_CHIEF_WORKDESK"
rt "$BDIR" "quality-chief" "Bd-vague"     "批次" ""

echo "=== BASELINE 60 DONE ==="

rt "$DDIR" "sales-owner" "rd-today"        "今天该跟谁拜访" "DAILY_CUSTOMER_FOLLOWUP"
rt "$DDIR" "sales-owner" "rd-month"        "本月销售业绩排名前 5 的客户" ""
rt "$DDIR" "sales-owner" "rd-quarter"      "本季度新签客户列表" ""
rt "$DDIR" "sales-owner" "rd-alert"        "今天有逾期未跟进的客户吗" ""
rt "$DDIR" "sales-owner" "rd-golden-pass"  "六腾门科技这个客户最近怎么样" ""
rt "$DDIR" "sales-owner" "rd-named"        "客户张总最近 7 天的通话记录" ""
rt "$DDIR" "sales-owner" "rd-opp"          "本周新增商机数量" ""
rt "$DDIR" "sales-owner" "rd-renew"        "下月到期续约的客户" ""
rt "$DDIR" "sales-owner" "rd-rank"         "本月销售冠军是谁" ""
rt "$DDIR" "sales-owner" "rd-stuck"        "停滞超过 30 天的商机" ""
rt "$DDIR" "finance-manager" "rd-today"        "今日营收金额" ""
rt "$DDIR" "finance-manager" "rd-month"        "本月营收 vs 上月" "MONTHLY_FINANCIAL_CLOSE"
rt "$DDIR" "finance-manager" "rd-quarter"      "本季度净利润率" ""
rt "$DDIR" "finance-manager" "rd-alert"        "本月应收账款逾期超过 30 天的客户" ""
rt "$DDIR" "finance-manager" "rd-golden-pass"  "本月经营月报已编制了吗" "MONTHLY_FINANCIAL_CLOSE"
rt "$DDIR" "finance-manager" "rd-payable"      "本月应付账款合计" ""
rt "$DDIR" "finance-manager" "rd-invoice"      "上周开票数量" ""
rt "$DDIR" "finance-manager" "rd-cashflow"     "本月现金流入流出对比" ""
rt "$DDIR" "finance-manager" "rd-cost"         "本月生产成本占比" ""
rt "$DDIR" "finance-manager" "rd-margin"       "本月毛利率"  ""
rt "$DDIR" "quality-manager" "rd-today"        "今天 HACCP 全部通过吗" "FOOD_SAFETY_RECALL"
rt "$DDIR" "quality-manager" "rd-month"        "本月不合格批次数量" ""
rt "$DDIR" "quality-manager" "rd-quarter"      "本季度质检合格率" ""
rt "$DDIR" "quality-manager" "rd-alert"        "今天有食安告警吗" ""
rt "$DDIR" "quality-manager" "rd-golden-pass"  "本月有没有触发召回流程" "FOOD_SAFETY_RECALL"
rt "$DDIR" "quality-manager" "rd-trace"        "批次 B20260501 的质检结果" ""
rt "$DDIR" "quality-manager" "rd-supplier"     "本月供应商不合格率排名" ""
rt "$DDIR" "quality-manager" "rd-defect"       "本月主要缺陷类型分布" ""
rt "$DDIR" "quality-manager" "rd-rework"       "本月返工批次数" ""
rt "$DDIR" "quality-manager" "rd-cert"         "即将到期的食品安全证书" ""
rt "$DDIR" "warehouse-keeper" "rd-today"        "今天要收什么货" "WAREHOUSE_KEEPER_TODAY_TASKS"
rt "$DDIR" "warehouse-keeper" "rd-month"        "本月入库总量" ""
rt "$DDIR" "warehouse-keeper" "rd-quarter"      "本季度入库 vs 出库" ""
rt "$DDIR" "warehouse-keeper" "rd-alert"        "今天有超期未入库的采购单吗" ""
rt "$DDIR" "warehouse-keeper" "rd-golden-pass"  "今日待入库清单" "WAREHOUSE_KEEPER_TODAY_TASKS"
rt "$DDIR" "warehouse-keeper" "rd-inventory"    "原料 X 当前库存量" ""
rt "$DDIR" "warehouse-keeper" "rd-aging"        "库龄超过 60 天的原料" ""
rt "$DDIR" "warehouse-keeper" "rd-loss"         "本月盘亏数量" ""
rt "$DDIR" "warehouse-keeper" "rd-pending"      "待出库订单清单" ""
rt "$DDIR" "warehouse-keeper" "rd-supplier"     "本月入库供应商排名" ""
rt "$DDIR" "purchaser" "rd-today"        "今天有什么需要下单的" ""
rt "$DDIR" "purchaser" "rd-month"        "本月采购金额" ""
rt "$DDIR" "purchaser" "rd-quarter"      "本季度采购 vs 上季度" ""
rt "$DDIR" "purchaser" "rd-alert"        "原料缺料告警" ""
rt "$DDIR" "purchaser" "rd-golden-pass"  "下周采购清单" "PURCHASER_WEEKLY_PLAN"
rt "$DDIR" "purchaser" "rd-supplier"     "本月供应商交付准时率" ""
rt "$DDIR" "purchaser" "rd-price"        "原料 X 最近一次采购价" ""
rt "$DDIR" "purchaser" "rd-savings"      "本月采购节约金额" ""
rt "$DDIR" "purchaser" "rd-overdue"      "供应商交付逾期清单" ""
rt "$DDIR" "purchaser" "rd-trend"        "本月采购价格趋势" ""
rt "$DDIR" "quality-chief" "rd-today"        "今天哪些批次待放行" "QUALITY_CHIEF_WORKDESK"
rt "$DDIR" "quality-chief" "rd-month"        "本月已放行批次数" ""
rt "$DDIR" "quality-chief" "rd-quarter"      "本季度放行通过率" ""
rt "$DDIR" "quality-chief" "rd-alert"        "今天有紧急批次待放行吗" ""
rt "$DDIR" "quality-chief" "rd-golden-pass"  "今日待放行批次详情" "QUALITY_CHIEF_WORKDESK"
rt "$DDIR" "quality-chief" "rd-haccp"        "本月 HACCP CCP 监控完成率" ""
rt "$DDIR" "quality-chief" "rd-supplier"     "本月供应商质量评分" ""
rt "$DDIR" "quality-chief" "rd-deviation"    "本月偏差报告数量" ""
rt "$DDIR" "quality-chief" "rd-corrective"   "本月待关闭的纠正措施" ""
rt "$DDIR" "quality-chief" "rd-audit"        "本季度内审进度" ""

echo "=== ALL 120 DONE ==="
echo "BDIR=$BDIR"
echo "DDIR=$DDIR"
