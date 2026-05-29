-- Sprint 12 Phase C — Seed 5 卤味业态 (FACTORY_LU_*) real-business indicators for F006
-- (Issue #269 — Steve §3 KPI draft 2026-05-29)
--
-- Domain prefix FACTORY_LU_* 区别 RESTAURANT_* / B2B_* / generic FACTORY_* (per brief).
-- 全 5 个 ratio/average strategy → null-preserve (无数据返 null → UI "—", 等 F006 录数据自动填).
--
-- Steve §3 决策: ship 5 (列名对得上), skip 2 (列名/category 缺):
--   - SKIP 卤汁损耗率: wastage_records.type = EXPIRED/SPOILED/PROCESSING/DAMAGED, 无卤汁 category
--     (Steve 后续定 category 枚举再 ship — 不 ship 假 KPI per Rule 21)
--   - SKIP 准时交货率: sales_orders 无 actual_delivery_date 列 (Steve 后续加字段)
--
-- F006 真实数据现状 (test cretas_db 2026-05-29):
--   - production_batches: 0 行 → 出品率/单位成本/日均产量 = null "—"
--   - material_batches: 8 ACTIVE 但 used_quantity 多为 0 → 周转天数大概率 null "—"
--   - quality_inspections: 0 行 + 无 VACUUM_PACKING mode → 真空包装合格率 null "—"
--   全 null 是诚实的 — 等老板录入生产/质检数据后自动填真值.
--
-- Idempotent: deterministic IDs + ON CONFLICT DO NOTHING.

-- ============================================================================
-- Step 1: 5 卤味 indicators (CACHED ttl=15min) + actionHint in config jsonb
-- ============================================================================
INSERT INTO indicators (id, factory_id, code, name, description, category, unit,
                        is_active, compute_strategy, cache_ttl_seconds, display_order, config)
VALUES
    ('ind-f006-lu-yield', 'F006', 'FACTORY_LU_YIELD_RATE',
     '出品率',
     '卤味出品率 = SUM(good_quantity) * 100 / SUM(planned_quantity) over COMPLETED 生产批次. ' ||
     '反映"计划投产 vs 良品产出". 真正"成品/投料原料重量"需 material 消耗 join, 后续微调.',
     'FACTORY', '%',
     TRUE, 'CACHED', 900, 210,
     '{"actionHint": {"label": "查看生产批次", "route": "/production/batches"}}'::jsonb),

    ('ind-f006-lu-cost', 'F006', 'FACTORY_LU_UNIT_COST',
     '单位成本',
     '卤味单位成本 = SUM(total_cost) / SUM(actual_quantity) over COMPLETED 生产批次. ' ||
     '每单位成品综合成本 (材料+人工+设备+其他).',
     'FINANCE', '元/单位',
     TRUE, 'CACHED', 900, 211,
     '{"actionHint": {"label": "查看生产成本", "route": "/production/batches"}}'::jsonb),

    ('ind-f006-lu-output', 'F006', 'FACTORY_LU_DAILY_OUTPUT',
     '日均产量',
     '卤味日均产量 = SUM(actual_quantity) / 有生产的天数 over COMPLETED 生产批次. ' ||
     '用 DISTINCT 生产日做分母避免空闲日拉低均值.',
     'FACTORY', '单位/天',
     TRUE, 'CACHED', 900, 212,
     '{"actionHint": {"label": "查看生产批次", "route": "/production/batches"}}'::jsonb),

    ('ind-f006-lu-turnover', 'F006', 'FACTORY_LU_MATERIAL_TURNOVER_DAYS',
     '原料周转天数',
     '卤味原料周转天数 = 当前在库量 * 30 / 累计消耗量. 即"当前库存够用多少天". ' ||
     '无消耗记录返 null (周转无意义) → UI "—".',
     'INVENTORY', '天',
     TRUE, 'CACHED', 900, 213,
     '{"actionHint": {"label": "查看库存明细", "route": "/inventory/material-batches?status=ACTIVE"}}'::jsonb),

    ('ind-f006-lu-vacuum', 'F006', 'FACTORY_LU_VACUUM_PACK_PASS_RATE',
     '真空包装合格率',
     '卤味真空包装合格率 = SUM(pass_count) * 100 / SUM(pass+fail) WHERE inspection_mode=VACUUM_PACKING. ' ||
     '只统计真空包装质检, 不混全局质检. 无真空包装质检数据返 null → UI "—".',
     'QUALITY', '%',
     TRUE, 'CACHED', 900, 214,
     '{"actionHint": {"label": "查看质检记录", "route": "/quality/inspections"}}'::jsonb)
ON CONFLICT (factory_id, code) DO NOTHING;

-- ============================================================================
-- Step 2: 5 indicator_computations (JPA_AGGREGATE → FactoryLu*Strategy)
-- ============================================================================
INSERT INTO indicator_computations (id, indicator_id, compute_type, compute_source, params,
                                     is_active, priority)
VALUES
    ('cmp-f006-lu-yield', 'ind-f006-lu-yield', 'JPA_AGGREGATE',
     'java:FactoryLuYieldRateStrategy',
     '{"unit":"%","scale":2,"real_business":true,"source_table":"production_batches","null_when_empty":true}'::jsonb,
     TRUE, 1),
    ('cmp-f006-lu-cost', 'ind-f006-lu-cost', 'JPA_AGGREGATE',
     'java:FactoryLuUnitCostStrategy',
     '{"unit":"CNY","scale":2,"real_business":true,"source_table":"production_batches","null_when_empty":true}'::jsonb,
     TRUE, 1),
    ('cmp-f006-lu-output', 'ind-f006-lu-output', 'JPA_AGGREGATE',
     'java:FactoryLuDailyOutputStrategy',
     '{"unit":"count","scale":2,"real_business":true,"source_table":"production_batches","null_when_empty":true}'::jsonb,
     TRUE, 1),
    ('cmp-f006-lu-turnover', 'ind-f006-lu-turnover', 'JPA_AGGREGATE',
     'java:FactoryLuMaterialTurnoverDaysStrategy',
     '{"unit":"days","scale":1,"real_business":true,"source_table":"material_batches","null_when_no_usage":true}'::jsonb,
     TRUE, 1),
    ('cmp-f006-lu-vacuum', 'ind-f006-lu-vacuum', 'JPA_AGGREGATE',
     'java:FactoryLuVacuumPackPassRateStrategy',
     '{"unit":"%","scale":2,"real_business":true,"source_table":"quality_inspections","filter":"inspection_mode=VACUUM_PACKING","null_when_empty":true}'::jsonb,
     TRUE, 1)
ON CONFLICT (id) DO NOTHING;

-- ============================================================================
-- Step 3: Verify
-- ============================================================================
DO $$
DECLARE
    lu_indicators INT;
    lu_computations INT;
    lu_action_hints INT;
BEGIN
    SELECT count(*) INTO lu_indicators
      FROM indicators WHERE factory_id='F006' AND code LIKE 'FACTORY_LU_%';
    SELECT count(*) INTO lu_computations
      FROM indicator_computations c JOIN indicators i ON c.indicator_id=i.id
     WHERE i.factory_id='F006' AND i.code LIKE 'FACTORY_LU_%'
       AND c.compute_type='JPA_AGGREGATE' AND c.is_active=TRUE;
    SELECT count(*) INTO lu_action_hints
      FROM indicators WHERE factory_id='F006' AND code LIKE 'FACTORY_LU_%'
       AND config->'actionHint'->>'label' IS NOT NULL;

    RAISE NOTICE 'Sprint 12 Phase C: 卤味 indicators: %/5 / computations: %/5 / actionHints: %/5',
        lu_indicators, lu_computations, lu_action_hints;

    IF lu_indicators < 5 THEN
        RAISE EXCEPTION 'Phase C FAIL: expected 5 卤味 indicators, got %', lu_indicators;
    END IF;
    IF lu_computations < 5 THEN
        RAISE EXCEPTION 'Phase C FAIL: expected 5 computations, got %', lu_computations;
    END IF;
    IF lu_action_hints < 5 THEN
        RAISE EXCEPTION 'Phase C FAIL: expected 5 actionHints, got %', lu_action_hints;
    END IF;
END $$;
