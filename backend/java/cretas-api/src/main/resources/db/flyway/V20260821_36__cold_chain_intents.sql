-- V20260821_36__cold_chain_intents.sql
--
-- Sprint 9 P2.D — 冷链温控 + 偏离报警 (2026-05-21).
-- 注册 1 Workdesk-level intent + 4 Tool-level intents.
--
-- Workdesk intent (tool_name=NULL, SkillRegistry/AI 编排 + Sprint 9 P0.1 WORKDESK route):
--   COLD_CHAIN_MONITORING → AI 编排 cold_chain_temp_query + cold_chain_alert_active 输出 24h 温控状态
--
-- Tool intents:
--   cold_chain_equipment_register    → 注册冷链设备
--   cold_chain_temp_query            → 24h 温度曲线 + 偏离 mark
--   cold_chain_alert_active          → 当前 OPEN/ACKNOWLEDGED 报警
--   cold_chain_alert_acknowledge     → 确认报警 (status OPEN → ACKNOWLEDGED)
--
-- 2026-05-21 hotfix: dropped `examples` column reference (PR #141).
-- ai_intent_configs schema (per V20260415_99 bootstrap + V20260416_09 ALTER):
-- supported columns are intent_code/intent_name/intent_category/tool_name/keywords/
-- sensitivity_level/is_active/description/priority/created_at/updated_at/+entity-side adds.
-- There is NO `examples` column in this table (entity AIIntentConfig.java uses
-- `example_queries`, not `examples`). Example AI queries moved to inline SQL comments above.
--
-- 2026-05-21 hotfix #2: keywords column is jsonb — added `::jsonb` cast to all 5 sites.

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    keywords, sensitivity_level, is_active, description, created_at, updated_at
) VALUES
  -- examples: "冷库今天温度怎么样" / "冷藏车有没有温控偏离" / "冷链监测情况"
  (gen_random_uuid(), 'COLD_CHAIN_MONITORING', '冷链温控监测', 'WORKDESK',
   NULL,
   '["冷链","温控","冷藏","冷冻","冰柜","冷库","冷藏车","温度","偏离","报警"]'::jsonb,
   'LOW', true,
   '冷链温控 Workdesk — AI 编排 24h 温度状态 + 报警 (per GB 14881)',
   NOW(), NOW()),

  -- examples: "注册一个新的冷藏车" / "加一个冷柜"
  (gen_random_uuid(), 'COLD_CHAIN_EQUIPMENT_REGISTER', '注册冷链设备', 'WRITE',
   'cold_chain_equipment_register',
   '["注册冷链设备","新增冰柜","新增冷藏车","添加冷库"]'::jsonb,
   'MEDIUM', true,
   '注册新冷链设备 (冰柜/冷藏车/陈列柜等), 设温控阈值',
   NOW(), NOW()),

  -- examples: "查冷柜 A 的 24h 温度" / "昨天冷藏车温度怎么样"
  (gen_random_uuid(), 'COLD_CHAIN_TEMP_QUERY', '查询温度记录', 'QUERY',
   'cold_chain_temp_query',
   '["温度记录","温度曲线","24h 温度","冷链温度"]'::jsonb,
   'LOW', true,
   '查询冷链设备 24h 温度曲线 + 偏离 mark',
   NOW(), NOW()),

  -- examples: "现在有冷链报警吗" / "当前几个温控报警"
  (gen_random_uuid(), 'COLD_CHAIN_ALERT_ACTIVE', '查当前冷链报警', 'QUERY',
   'cold_chain_alert_active',
   '["冷链报警","温控报警","当前报警","活跃报警"]'::jsonb,
   'LOW', true,
   '查询当前 OPEN/ACKNOWLEDGED 状态的冷链温控报警',
   NOW(), NOW()),

  -- examples: "确认冷柜 A 的温控报警" / "报警处理已联系维修"
  (gen_random_uuid(), 'COLD_CHAIN_ALERT_ACKNOWLEDGE', '确认冷链报警', 'WRITE',
   'cold_chain_alert_acknowledge',
   '["确认报警","报警处理","ack 报警","冷链 ack"]'::jsonb,
   'MEDIUM', true,
   '确认冷链温控报警, status OPEN → ACKNOWLEDGED + ack 原因',
   NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET
    intent_name = EXCLUDED.intent_name,
    keywords = EXCLUDED.keywords,
    description = EXCLUDED.description,
    updated_at = NOW();
