-- V20260822_05__production_loop_intents.sql
--
-- Sprint 10 Loop 5 — 生产任务创建 AI 闭环 (2026-05-21).
-- 注册 1 WORKDESK 级 intent + 2 Tool-level intents.
--
-- WORKDESK intent (tool_name=NULL, SkillRegistry/AI 编排路径, per Sprint 9 P0.1 Strategy A):
--   PRODUCTION_DEMAND_ANALYSIS    → production-demand-analysis Skill (or AI route fallback)
--                                   (intent_code → kebab skill name 转换由
--                                    DynamicToolSelectionService.intentCodeToSkillName 处理)
--
-- 2 Tool-level intents (LLM 直接绑 tool_name 走 Tool 直接执行):
--   PRODUCTION_DEMAND_QUERY         → production_demand_query     (READ)
--   PRODUCTION_BATCH_CREATE         → production_batch_create     (WRITE + Preview, R1+R2+R4)
--
-- 防呆灵魂 Tool: production_batch_create — R1 max=待产量 + R2 品名+SO + R3 生产线 dropdown +
-- R4 idem (sales_order_id + product_type_id + scheduled_date + 5min window).
--
-- Pattern mirrors V20260820_07/08/09/10 (Sprint 8 P3-P4 proven). 3-strike preflight (per
-- feedback_3_strike_comprehensive_audit_over_reactive_patching HARD):
--   - NO `||` in COMMENT clauses (single string literal per CONCAT-rules audit)
--   - JSONB literals via cast/Type (here only TEXT description, no JSONB)
--   - WORKDESK intent tool_name = NULL (per Sprint 9 P0.1)

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    created_at, updated_at
)
VALUES
    -- ===== 1. WORKDESK-level Skill intent =====
    (gen_random_uuid(), 'PRODUCTION_DEMAND_ANALYSIS', '订单缺产分析与排产建议', 'WORKDESK',
     NULL, 'MEDIUM',
     '["订单缺产","今天要起产什么","排产建议","什么订单缺货要做","该生产什么","今天该排产什么","今日排产","起产建议","缺货生产","订单缺货要做","缺货排产"]',
     'Sprint 10 Loop 5 生产经理 Workdesk 入口 — AI 分析待产订单 (SO 总量 - 已发已产) > 0 的 productId 列表 + 推荐起产量 + 推荐生产线. 路由到 production-demand-analysis Skill (或 AI 兜底). 触发: 订单缺产 / 今天要起产什么 / 排产建议 / 什么订单缺货要做 / 该生产什么. Cretas vs HJ 差异化 — HJ 仅列订单, 不主动推 起产 建议.',
     40, true, NOW(), NOW()),

    -- ===== 2. Tool-level: 缺产订单查询 (READ) =====
    (gen_random_uuid(), 'PRODUCTION_DEMAND_QUERY', '缺产订单查询', 'QUERY',
     'production_demand_query', 'LOW',
     '["缺产订单","哪些订单缺货","待产订单","订单还有多少没生产","待生产清单","未发完订单","SO 待产"]',
     'Sprint 10 Loop 5 — 查工厂当前所有 (SO 总量 - 已发货 - 在产) > 0 的订单行项目, 按 productId 聚合 + 计算推荐起产量 + 推荐生产线. 不写库. R2 context — 每行带 productName + orderNumber + 缺量.',
     75, true, NOW(), NOW()),

    -- ===== 3. Tool-level: 创建生产批次 (WRITE + Preview) =====
    (gen_random_uuid(), 'PRODUCTION_BATCH_CREATE', '创建生产批次', 'DATA_OP',
     'production_batch_create', 'MEDIUM',
     '["起产","创建生产任务","新建批次","开始生产","下生产单","下生产任务","创建批次","起产 N 件","建生产计划"]',
     'Sprint 10 Loop 5 灵魂 Tool — 创建 ProductionBatch (status=PLANNED) 并锁定 SO 行项目. WRITE + Preview. 防呆 R1 max=待产量 + R2 品名+SO + R3 productionLine dropdown (DEDICATED_LINE_A/DEDICATED_LINE_B/SHARED_LINE/OTHER) + R4 idempotent (salesOrderId+productTypeId+scheduledDate+5min). 标记 ai_invocation_metadata = {source: sprint-10-loop-5, testRun, createdAt}.',
     70, true, NOW(), NOW())

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
