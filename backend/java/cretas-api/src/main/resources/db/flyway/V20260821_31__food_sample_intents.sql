-- V20260821_31__food_sample_intents.sql
--
-- Sprint 9 P2.A 食品行业法定 P0 — 留样追踪意图绑定 (2026-05-21)
-- 法定: GB 31654-2021 餐饮服务从业人员食品安全管理规范 (48h 冷藏 >= 125g)
--
-- 注册 1 Workdesk-level Skill intent + 4 Tool-level intents.
--
-- Workdesk-level intent (tool_name=NULL, AI 直接 ask 路由到 SkillRegistry):
--   FOOD_SAMPLE_LIFECYCLE → food-sample-lifecycle (concept, 由 LLM tool-router 编排)
--
-- 4 Tool-level intents (LLM 直接绑 tool_name 走 Tool 直接执行):
--   FOOD_SAMPLE_CREATE                 → food_sample_create (WRITE + Preview)
--   FOOD_SAMPLE_QUERY_PENDING_DISPOSE  → food_sample_query_pending_dispose
--   FOOD_SAMPLE_DISPOSE                → food_sample_dispose (WRITE + Preview BLOCKING)
--   FOOD_SAMPLE_TRACE                  → food_sample_trace
--
-- Pattern mirrors V20260820_07 (Sprint 8 P3 food safety intents). ON CONFLICT idempotent.
--
-- HJ 0 留样能力 → Cretas 食品垂直 V2 差异化护城河 +1.

-- ===== 1. Workdesk-level Skill intent =====

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    created_at, updated_at
)
VALUES (
    gen_random_uuid(), 'FOOD_SAMPLE_LIFECYCLE', '食品留样全生命周期', 'WORKDESK',
    NULL, 'MEDIUM',
    '["留样","食品留样","48小时留样","留样追踪","留样管理","留样巡检","今天哪些留样要销毁","留样到期","留样过期","GB 31654","125克","冷藏留样","留样还在吗","查留样"]',
    'Sprint 9 P2.A 食品行业法定 P0 — 留样全生命周期 (GB 31654-2021 48h 冷藏 >= 125g). ' ||
    '串 4 Tool 完成: 留样 (food_sample_create) / 待销毁查询 (food_sample_query_pending_dispose) / ' ||
    '销毁/送检 (food_sample_dispose) / 追溯 (food_sample_trace). ' ||
    'tool_name=NULL → AI 直接 ask 时走 SkillRegistry/LLM 动态编排. ' ||
    'Cretas vs HJ 食品垂直差异化护城河 +1 (HJ 0 留样能力).',
    40, true, NOW(), NOW()
)
ON CONFLICT (intent_code) DO UPDATE SET
    tool_name = EXCLUDED.tool_name,
    intent_name = EXCLUDED.intent_name,
    intent_category = EXCLUDED.intent_category,
    sensitivity_level = EXCLUDED.sensitivity_level,
    keywords = EXCLUDED.keywords,
    description = EXCLUDED.description,
    priority = EXCLUDED.priority,
    is_active = EXCLUDED.is_active,
    updated_at = NOW();

-- ===== 2. 4 Tool-level intents =====

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    created_at, updated_at
)
VALUES
    (gen_random_uuid(), 'FOOD_SAMPLE_CREATE', '创建食品留样', 'DATA_OP',
     'food_sample_create', 'MEDIUM',
     '["留样","创建留样","新建留样","留样这批","今天这批留样","卤猪蹄留样","留样 130g","记录留样","存留样"]',
     'GB 31654-2021 创建食品留样记录 — 自动算 expireTime = now+48h, ' ||
     '默认冷藏 <= 8.0 ℃, 法规 sampleQuantity >= 125g. WRITE + Preview + R4 5min dedup.',
     70, true, NOW(), NOW()),

    (gen_random_uuid(), 'FOOD_SAMPLE_QUERY_PENDING_DISPOSE', '查待销毁留样', 'QUERY',
     'food_sample_query_pending_dispose', 'LOW',
     '["待销毁留样","过期留样","48小时到了","48h 到了的留样","今天哪些留样要销毁","留样过期列表","巡检留样","逾期留样","到期留样"]',
     '查 status=RETAINED + expire_time < now 的留样 (已过 48h 应销毁). ' ||
     '工作台每日巡检 / AI 定时提醒. read-only.',
     80, true, NOW(), NOW()),

    (gen_random_uuid(), 'FOOD_SAMPLE_DISPOSE', '销毁/送检留样', 'DATA_OP',
     'food_sample_dispose', 'HIGH',
     '["销毁留样","留样销毁","标记销毁","已销毁","留样送检","拿去送检","客户投诉送检","用于检测","送疾控检测","INSPECTION","CONTAMINATED"]',
     '把留样标记为 DISPOSED / USED_FOR_INSPECTION. ' ||
     'reason enum: SCHEDULED (按期销毁) / INSPECTION (送检) / CONTAMINATED (污染). ' ||
     'WRITE + Preview BLOCKING + R4 幂等.',
     70, true, NOW(), NOW()),

    (gen_random_uuid(), 'FOOD_SAMPLE_TRACE', '按批次追溯留样', 'QUERY',
     'food_sample_trace', 'LOW',
     '["留样追溯","B- 留样还在吗","查 B- 的留样","客户投诉找留样","批次留样","留样查询","这批留样"]',
     '客户投诉场景: 按 batchNumber 列出该批次全部留样 + status. ' ||
     '判断是否有 RETAINED 留样可送检. read-only.',
     80, true, NOW(), NOW())

ON CONFLICT (intent_code) DO UPDATE SET
    tool_name = EXCLUDED.tool_name,
    intent_name = EXCLUDED.intent_name,
    intent_category = EXCLUDED.intent_category,
    sensitivity_level = EXCLUDED.sensitivity_level,
    keywords = EXCLUDED.keywords,
    description = EXCLUDED.description,
    priority = EXCLUDED.priority,
    is_active = EXCLUDED.is_active,
    updated_at = NOW();
