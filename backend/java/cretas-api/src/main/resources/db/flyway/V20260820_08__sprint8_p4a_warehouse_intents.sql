-- V20260820_08__sprint8_p4a_warehouse_intents.sql
--
-- Sprint 8 P4a — 仓管员 Workdesk (2026-05-20)
-- 注册 1 Workdesk 顶层 intent + 5 Tool-level intents.
--
-- Workdesk 触发 (Workdesk auto-trigger entry point):
--   WAREHOUSE_KEEPER_TODAY_TASKS → material_today_receiving_query Tool
--   (单 Tool 走 Tool 直接执行, 当前不串多 Tool; Sprint 9 可升级为 Skill 编排)
--
-- 5 Tool intents (LLM 绑 tool_name 走 Tool 直接执行分支):
--   MATERIAL_TODAY_RECEIVING_QUERY  → material_today_receiving_query  (READ)
--   MATERIAL_DISPOSAL_RECOMMENDATION → material_disposal_recommendation (READ + AI rule)
--   RECEIVE_WITH_LIMIT              → receive_with_limit              (WRITE + Preview, R1 灵魂)
--   RECEIVE_QUALITY_CHECK_TODAY     → receive_quality_check_today     (READ)
--   PDA_SCAN_TASK_GENERATE          → pda_scan_task_generate          (GENERATE)
--
-- 客户原话证据 (F006 张权-昆山): "做仓管的他年纪都比较大文化素质很低, 你不能太依赖他们,
-- 最好的方法就是你告诉他这个东西你要收多少就行了" — 触发关键词强调"告诉/收/今天/快速".
--
-- Pattern mirrors V20260820_01/02/07 (P1/P2/P3 proven). ON CONFLICT DO UPDATE for idempotent re-run.

-- ===== 1. Workdesk-level entry intent =====

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    created_at, updated_at
)
VALUES (
    gen_random_uuid(),
    'WAREHOUSE_KEEPER_TODAY_TASKS',
    '仓管员今日任务',
    'WORKDESK',
    'material_today_receiving_query',  -- 默认走 today_receiving (Sprint 9 改 Skill 编排聚合 5 Tool)
    'LOW',
    '["今天要收什么货","今日到货","今天到什么货","待收货清单","今天有什么货","今日入库任务","今天有几个供应商来送货","今日待收","仓管员工作台","今天该收什么","明天到什么货","待收清单","仓管今日任务"]',
    'Sprint 8 P4a 仓管员 Workdesk 入口 — 当前 default 走 material_today_receiving_query Tool 输出今日待收清单. ' ||
    '客户原话 (F006 张权): "做仓管的年纪大文化素质低, 最好告诉他要收多少就行". ' ||
    'Cretas vs HJ 防呆护城河 — HJ 5 屏 5 分钟 vs Cretas 1 屏 30 秒.',
    45,
    true,
    NOW(),
    NOW()
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

-- ===== 2. 5 Tool-level intents =====

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    created_at, updated_at
)
VALUES
    (gen_random_uuid(), 'MATERIAL_TODAY_RECEIVING_QUERY', '今日待收货清单', 'QUERY',
     'material_today_receiving_query', 'LOW',
     '["今日待收","待收货","今天要收","今日到货清单","展开待收行","待收明细","今日入库清单"]',
     '今日 + 明日 expected delivery date 的 PO 行项目, 每行带 R1 max 边界 (已订/已收/还可入). read-only.',
     80, true, NOW(), NOW()),

    (gen_random_uuid(), 'MATERIAL_DISPOSAL_RECOMMENDATION', '临期物料处置建议', 'ANALYSIS',
     'material_disposal_recommendation', 'LOW',
     '["快过期的怎么处理","临期物料","过期原料","保质期紧张","怎么处理临期","建议处置","马上到期","到期建议"]',
     '对 7 天内临期 + 已过期批次输出处置建议 (调拨/降价/退货/报废). read-only — 不实际执行.',
     75, true, NOW(), NOW()),

    (gen_random_uuid(), 'RECEIVE_WITH_LIMIT', '快速入库 (R1 max)', 'DATA_OP',
     'receive_with_limit', 'MEDIUM',
     '["快速入库","收货","入库","签收到货","入 N 件","入 N 公斤","收 N kg"]',
     '仓管员快速入库, preview 显已订/已收/可入 (含 30% 超收上限) + 超限阻止. WRITE + Preview. ' ||
     '防呆 R1 灵魂 Tool — 直接告诉仓管员要收多少, 不让他算账.',
     70, true, NOW(), NOW()),

    (gen_random_uuid(), 'RECEIVE_QUALITY_CHECK_TODAY', '今日待质检批次', 'QUERY',
     'receive_quality_check_today', 'LOW',
     '["今天哪些要质检","待质检批次","今日 QC","哪些等质量主管","质检待办","质检列表","INSPECTING 批次"]',
     '查 INSPECTING 状态批次 + 今日新入库批次清单, 提醒仓管员通知质量主管. read-only.',
     75, true, NOW(), NOW()),

    (gen_random_uuid(), 'PDA_SCAN_TASK_GENERATE', 'PDA 扫码任务生成', 'DATA_OP',
     'pda_scan_task_generate', 'LOW',
     '["一键扫码","扫码任务","PDA 任务","二维码扫码","给我个二维码","准备扫码","PDA 入库","扫码入库"]',
     '生成 PDA / 手机扫码任务 (token + items 数组 + qrPayload). GENERATE — 不写库.',
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
