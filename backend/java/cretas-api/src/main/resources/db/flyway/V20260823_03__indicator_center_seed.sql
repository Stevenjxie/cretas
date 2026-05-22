-- =====================================================================
-- Canvas Indicator Center — Additional Seed (Phase A subagent #3)
--
-- 在 Phase 1 Day 2 (V20260821_03) 7 个 F006 指标基础上，
-- 增加跨工厂分类指标 + 指标树父子关系 + 一个 YELLOW/RED 阈值示例，
-- 让 Canvas 指标中心 Tab 在 UI 层有足够数据展示 8 个子功能。
--
-- 增加内容:
--   1. 5 个跨分类指标 (FINANCE / INVENTORY / SALES / QUALITY / FOOD_SAFETY)
--   2. YELLOW / RED 阈值补充 (Phase 1 只 seed 了 GREEN)
--   3. 指标树 5 条父子边 (用于 vue-flow DAG demo)
--   4. 5 条 IndicatorVersion 历史 snapshot (让 measurements/趋势 Tab 有数据)
--
-- Idempotency: ON CONFLICT DO NOTHING + 固定 ID。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 5 个跨分类指标 (F006)
-- ---------------------------------------------------------------------
INSERT INTO indicators (id, factory_id, code, name, description, category, unit,
                        is_active, compute_strategy, cache_ttl_seconds, display_order, config)
VALUES
    ('ind-f006-finv', 'F006', 'FINANCE_INVENTORY_VALUE',
     '库存总价值',
     '当前所有可用批次的成本 × 数量汇总 (元)。供应链财务核心指标。',
     'FINANCE', '元',
     TRUE, 'CACHED', 600, 200, '{}'::jsonb),

    ('ind-f006-itr', 'F006', 'INVENTORY_TURNOVER_RATE',
     '库存周转率',
     '出库总量 / 平均库存量。反映库存运转效率。',
     'INVENTORY', '次/月',
     TRUE, 'CACHED', 600, 210, '{}'::jsonb),

    ('ind-f006-srm', 'F006', 'SALES_MONTHLY_REVENUE',
     '月度销售额',
     '当月已开票订单金额总和 (元)。',
     'SALES', '元',
     TRUE, 'CACHED', 300, 220, '{}'::jsonb),

    ('ind-f006-qrt', 'F006', 'QUALITY_REJECT_RATE',
     '质检不合格率',
     '不合格批次数 / 总质检批次数 × 100%。',
     'QUALITY', '%',
     TRUE, 'CACHED', 300, 230, '{}'::jsonb),

    ('ind-f006-fshv', 'F006', 'FOOD_SAFETY_HACCP_VIOLATIONS',
     'HACCP 违规次数',
     '本月内 CCP 检查点违规事件总数。任何 > 0 都需立即整改。',
     'FOOD_SAFETY', '次',
     TRUE, 'CACHED', 600, 240, '{}'::jsonb)
ON CONFLICT (factory_id, code) DO NOTHING;

-- ---------------------------------------------------------------------
-- 2. 完整 GREEN/YELLOW/RED 三色阈值 (Phase 1 只 seed 了 GREEN)
-- ---------------------------------------------------------------------
INSERT INTO indicator_thresholds (id, factory_id, indicator_id, alert_level, operator,
                                   threshold_value, threshold_value_upper, is_active)
VALUES
    -- RESTAURANT_WASTAGE_RATE: GREEN ≤3 / YELLOW 3-5 / RED >5
    ('thr-f006-rwr-y', 'F006', 'ind-f006-rwr', 'YELLOW', 'BETWEEN', 3.0001, 5.0000, TRUE),
    ('thr-f006-rwr-r', 'F006', 'ind-f006-rwr', 'RED',    'GT',      5.0000, NULL,   TRUE),

    -- RESTAURANT_TABLE_TURNOVER: GREEN ≥4 / YELLOW 2-4 / RED <2
    ('thr-f006-rtt-y', 'F006', 'ind-f006-rtt', 'YELLOW', 'BETWEEN', 2.0000, 3.9999, TRUE),
    ('thr-f006-rtt-r', 'F006', 'ind-f006-rtt', 'RED',    'LT',      2.0000, NULL,   TRUE),

    -- FACTORY_YIELD_RATE: GREEN ≥95 / YELLOW 90-94.99 / RED <90
    ('thr-f006-fyr-y', 'F006', 'ind-f006-fyr', 'YELLOW', 'BETWEEN', 90.0000, 94.9999, TRUE),
    ('thr-f006-fyr-r', 'F006', 'ind-f006-fyr', 'RED',    'LT',      90.0000, NULL,    TRUE),

    -- QUALITY_REJECT_RATE: GREEN ≤1 / YELLOW 1-3 / RED >3
    ('thr-f006-qrt-g', 'F006', 'ind-f006-qrt', 'GREEN',  'LTE',     1.0000, NULL,    TRUE),
    ('thr-f006-qrt-y', 'F006', 'ind-f006-qrt', 'YELLOW', 'BETWEEN', 1.0001, 3.0000,  TRUE),
    ('thr-f006-qrt-r', 'F006', 'ind-f006-qrt', 'RED',    'GT',      3.0000, NULL,    TRUE),

    -- FOOD_SAFETY_HACCP_VIOLATIONS: GREEN =0 / RED ≥1 (任何违规即红色)
    ('thr-f006-fshv-g', 'F006', 'ind-f006-fshv', 'GREEN', 'EQ', 0.0000, NULL, TRUE),
    ('thr-f006-fshv-r', 'F006', 'ind-f006-fshv', 'RED',   'GTE', 1.0000, NULL, TRUE)
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------------
-- 3. 指标树父子关系 — 展示 Indicator DAG
--
--    良品率 是顶层 KPI; 它依赖 质检不合格率(取反向) + 计划达成率;
--    财务上 库存总价值 → 库存周转率 → 月度销售额 形成链条 (用于 forward/backward 演示)。
-- ---------------------------------------------------------------------
INSERT INTO indicator_tree_nodes (id, factory_id, indicator_id, parent_id, weight,
                                   depth, materialized_path, display_order)
VALUES
    -- 根节点: 综合良品率 (FACTORY_YIELD_RATE)
    ('itn-f006-fyr-root', 'F006', 'ind-f006-fyr', NULL, NULL,
     0, 'ind-f006-fyr', 1),

    -- 综合良品率 子: 质检不合格率
    ('itn-f006-qrt', 'F006', 'ind-f006-qrt', 'ind-f006-fyr', 0.5,
     1, 'ind-f006-fyr/ind-f006-qrt', 1),

    -- 综合良品率 子: 计划达成率
    ('itn-f006-fpar', 'F006', 'ind-f006-fpar', 'ind-f006-fyr', 0.5,
     1, 'ind-f006-fyr/ind-f006-fpar', 2),

    -- 库存价值 → 库存周转
    ('itn-f006-finv-root', 'F006', 'ind-f006-finv', NULL, NULL,
     0, 'ind-f006-finv', 2),
    ('itn-f006-itr', 'F006', 'ind-f006-itr', 'ind-f006-finv', 1.0,
     1, 'ind-f006-finv/ind-f006-itr', 1),

    -- 库存周转 → 月度销售额
    ('itn-f006-srm', 'F006', 'ind-f006-srm', 'ind-f006-itr', 1.0,
     2, 'ind-f006-finv/ind-f006-itr/ind-f006-srm', 1)
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------------
-- 4. 5 条历史 snapshot — 让趋势 Tab 有图可看
-- ---------------------------------------------------------------------
INSERT INTO indicator_versions (id, factory_id, indicator_id, period_start, period_end,
                                 value, computed_at, alert_level, compute_source)
VALUES
    ('iv-f006-fyr-1', 'F006', 'ind-f006-fyr',
     CURRENT_DATE - INTERVAL '7 days', CURRENT_DATE - INTERVAL '7 days',
     93.5000, NOW() - INTERVAL '7 days', 'YELLOW', 'SEED_DEMO'),
    ('iv-f006-fyr-2', 'F006', 'ind-f006-fyr',
     CURRENT_DATE - INTERVAL '3 days', CURRENT_DATE - INTERVAL '3 days',
     95.2000, NOW() - INTERVAL '3 days', 'GREEN',  'SEED_DEMO'),
    ('iv-f006-fyr-3', 'F006', 'ind-f006-fyr',
     CURRENT_DATE, CURRENT_DATE,
     96.1000, NOW(),                       'GREEN',  'SEED_DEMO'),

    ('iv-f006-rwr-1', 'F006', 'ind-f006-rwr',
     CURRENT_DATE - INTERVAL '7 days', CURRENT_DATE - INTERVAL '7 days',
     4.2000, NOW() - INTERVAL '7 days', 'YELLOW', 'SEED_DEMO'),
    ('iv-f006-rwr-2', 'F006', 'ind-f006-rwr',
     CURRENT_DATE, CURRENT_DATE,
     2.8000, NOW(),                       'GREEN',  'SEED_DEMO')
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------------
-- 5. Last value cache 同步
-- ---------------------------------------------------------------------
UPDATE indicators SET last_value = 96.1000, last_computed_at = NOW()
 WHERE id = 'ind-f006-fyr';
UPDATE indicators SET last_value = 2.8000, last_computed_at = NOW()
 WHERE id = 'ind-f006-rwr';
