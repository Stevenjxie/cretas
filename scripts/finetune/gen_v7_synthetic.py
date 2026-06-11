#!/usr/bin/env python3
"""V7 synthetic data generation for 37 new labels."""
import json, os, time, random, re
from openai import OpenAI

client = OpenAI(
    api_key=os.environ.get('DASHSCOPE_API_KEY'),
    base_url='https://dashscope.aliyuncs.com/compatible-mode/v1'
)

import psycopg2
conn = psycopg2.connect(host='localhost', dbname='cretas_prod_db', user='cretas_user', password='cretas123')
cur = conn.cursor()

new_labels = [
    'APPROVAL_SUBMIT','CONTEXT_CONTINUE','INVENTORY_OUTBOUND','INVENTORY_SUMMARY_QUERY',
    'MATERIAL_BATCH_DELETE','ORDER_APPROVAL','OUT_OF_DOMAIN','PRODUCTION_CONFIRM_WORKERS_PRESENT',
    'PRODUCTION_LINE_START','PROFIT_TREND_ANALYSIS','QUALITY_CHECK_CREATE','QUERY_APPROVAL_RECORD',
    'QUERY_EQUIPMENT_STATUS_BY_NAME','QUERY_INVENTORY_QUANTITY','QUERY_INVENTORY_TOTAL',
    'QUERY_MATERIAL_STOCK_SUMMARY','QUERY_ORDER_PENDING_MATERIAL_QUANTITY',
    'QUERY_PROCESSING_BATCH_SUPERVISOR','QUERY_PROCESSING_STEP','QUERY_TRANSPORT_LINE',
    'REPORT_AI_QUALITY','REPORT_WORKSHOP_DAILY','RESTAURANT_DAILY_REVENUE',
    'RESTAURANT_DISH_SALES_RANKING','RESTAURANT_INGREDIENT_EXPIRY_ALERT',
    'RESTAURANT_ORDER_STATISTICS','RESTAURANT_PROCUREMENT_SUGGESTION','RESTAURANT_REVENUE_TREND',
    'SCHEDULING_RUN_TOMORROW','SHIPMENT_EXPEDITE','SHIPMENT_NOTIFY_WAREHOUSE_PREPARE',
    'SUPPLIER_CREATE','SUPPLIER_PRICE_COMPARISON','TASK_ASSIGN_BY_NAME','TASK_PROGRESS_QUERY',
    'WORKER_ARRIVAL_CONFIRM','WORKER_IN_SHOP_REALTIME_COUNT'
]

intent_info = {}
for code in new_labels:
    cur.execute("SELECT intent_name, description::text, keywords::text FROM ai_intent_configs WHERE intent_code = %s", (code,))
    row = cur.fetchone()
    if row:
        intent_info[code] = dict(name=row[0] or code, desc=row[1] or '', keywords=row[2] or '')
    else:
        intent_info[code] = dict(name=code, desc='', keywords='')
conn.close()

existing = {}
with open('/www/wwwroot/python-services/models/chinese-roberta-wwm-ext-classifier/merged_training_data.jsonl') as f:
    for line in f:
        d = json.loads(line)
        if d['label'] in new_labels:
            existing.setdefault(d['label'], []).append(d['text'])

results = []
target = 35

for i, code in enumerate(new_labels):
    info = intent_info[code]
    ex = existing.get(code, [])[:5]
    ex_str = '\n'.join('- ' + e for e in ex) if ex else '(none)'
    kw = info['keywords'][:200] if info['keywords'] else 'none'

    prompt = (
        '你是食品工厂管理系统的用户查询生成器。'
        '请为意图 "' + info['name'] + '" 生成 ' + str(target) + ' 条多样化的中文查询句子。\n\n'
        '意图代码: ' + code + '\n'
        '意图描述: ' + info['desc'] + '\n'
        '关键词: ' + kw + '\n\n'
        '现有示例:\n' + ex_str + '\n\n'
        '要求:\n'
        '1. 每条查询独立一行，前面加序号\n'
        '2. 多样化表达: 正式语体、口语化、简短2到5字、带参数如日期名称数字、反问句、祈使句\n'
        '3. 融入工厂场景: 包含具体物料名如猪肉牛肉面粉、设备名如1号线、人名如张三李四、日期如今天本周上月\n'
        '4. 避免与现有示例重复\n'
        '5. 包含5条容易与其他意图混淆的边界表达\n'
        '6. 长度分布: 30%极短2到6字, 40%中等7到15字, 30%较长16到30字\n\n'
        '直接输出 ' + str(target) + ' 条查询，不要其他解释。'
    )

    try:
        resp = client.chat.completions.create(
            model='qwen3.7-max-2026-06-08',  # free quota ⚠️ (qwen3-max-2026-01-23 was PAID — billing incident 2026-06-11)
            messages=[dict(role='user', content=prompt)],
            max_tokens=2000,
            temperature=0.9,
            extra_body=dict(enable_thinking=False)
        )
        text = resp.choices[0].message.content.strip()
        lines = []
        for line in text.split('\n'):
            line = line.strip()
            if not line:
                continue
            cleaned = re.sub(r'^\d+[.、)\s]+', '', line).strip()
            if cleaned and 2 <= len(cleaned) <= 60:
                lines.append(cleaned)
        for t in lines:
            results.append(dict(text=t, label=code))
        print(f'[{i+1}/{len(new_labels)}] {code}: {len(lines)} samples')
        time.sleep(0.3)
    except Exception as e:
        print(f'[{i+1}/{len(new_labels)}] {code}: ERROR - {e}')
        time.sleep(1)

out_path = '/www/wwwroot/cretas/code/scripts/finetune/data/v7_synthetic_data.jsonl'
with open(out_path, 'w', encoding='utf-8') as f:
    for r in results:
        f.write(json.dumps(r, ensure_ascii=False) + '\n')
print(f'\nTotal: {len(results)} samples saved to {out_path}')
