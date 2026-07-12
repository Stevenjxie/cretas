-- ============================================================================
-- DEMO_REST 演示数据打磨 — 2026-07-12 (记录 / 可复现)
--
-- 这些是 headed 走查餐饮 demo 后, 对 DEMO_REST 演示租户 prod 数据做的一次性
-- 打磨修复 (让 demo 观感可信). 不是 schema 变更, 是数据 UPDATE. 记录在此以便:
--   (1) 有痕迹知道改过什么;
--   (2) demo 库若重建, 可参照重新应用.
--
-- ⚠️ 分两个库执行 (见每段标注):
--   - cretas_prod_db (Java 操作库, 无 RLS): 供应商名 / 领料 / 配方
--   - smartbi_prod_db (Python 分析库, 有 RLS!): agg_supplier_price
--     smartbi 表带 RLS, 必须先 `SELECT set_config('app.factory_id','DEMO_REST',false)`
--     否则裸查/改看不到行 (返 0 = 假象). 见 memory feedback_smartbi_rls_set_guc_before_query.
--
-- 相关代码改动 (已在 main): 每日 cron 刷新损耗/盘点 (scripts/cron/refresh-demo-rest.sh
-- + seed_demo_rest_ops.py 幂等前缀 DELETE) commit 38c633d88; 价格异常趋势曲线
-- (price-anomaly/index.vue + getSupplierPriceTrend) commit 6dbc9001c.
-- ============================================================================


-- ══════════════════════════════════════════════════════════════════════════
-- 库 1: cretas_prod_db  (psql -U cretas_user -d cretas_prod_db)
-- ══════════════════════════════════════════════════════════════════════════
BEGIN;

-- 1) 供应商名: 通用 IT 公司名 (取错种子池, 餐饮 demo 一眼假) → 真实 F&B 供应商
--    denormalize 在多表, 全改一致 (agg_supplier_price 在 smartbi 库, 见库2)
UPDATE suppliers SET name = CASE name
    WHEN '四通信息有限公司'     THEN '蜀海供应链有限公司'
    WHEN '网新恒天科技有限公司' THEN '鲜合汇食材配送有限公司'
    WHEN '通际名联信息有限公司' THEN '蜀味源调味食品有限公司'
    WHEN '华成育卓网络有限公司' THEN '川鲜农产品配送有限公司'
    ELSE name END
WHERE factory_id='DEMO_REST'
  AND name IN ('四通信息有限公司','网新恒天科技有限公司','通际名联信息有限公司','华成育卓网络有限公司');

UPDATE supplier_delivery_notes SET supplier_name = CASE supplier_name
    WHEN '四通信息有限公司'     THEN '蜀海供应链有限公司'
    WHEN '网新恒天科技有限公司' THEN '鲜合汇食材配送有限公司'
    WHEN '通际名联信息有限公司' THEN '蜀味源调味食品有限公司'
    WHEN '华成育卓网络有限公司' THEN '川鲜农产品配送有限公司'
    ELSE supplier_name END
WHERE factory_id='DEMO_REST'
  AND supplier_name IN ('四通信息有限公司','网新恒天科技有限公司','通际名联信息有限公司','华成育卓网络有限公司');

UPDATE restaurant_supplier_monthly_reconciliations SET supplier_name = CASE supplier_name
    WHEN '四通信息有限公司'     THEN '蜀海供应链有限公司'
    WHEN '网新恒天科技有限公司' THEN '鲜合汇食材配送有限公司'
    WHEN '通际名联信息有限公司' THEN '蜀味源调味食品有限公司'
    WHEN '华成育卓网络有限公司' THEN '川鲜农产品配送有限公司'
    ELSE supplier_name END
WHERE factory_id='DEMO_REST'
  AND supplier_name IN ('四通信息有限公司','网新恒天科技有限公司','通际名联信息有限公司','华成育卓网络有限公司');

-- 2) 领料: requested/actual_quantity 全 8.0 (uniform seed, Top10 全"8"假) → 按 id
--    序号派生变化数量 (1.5~13.4kg), 实发偶尔略少; 备注去掉泄露的内部串.
WITH r AS (
  SELECT id, (regexp_replace(id,'\D','','g'))::int AS seq
    FROM material_requisitions
   WHERE factory_id='DEMO_REST' AND id LIKE 'req_demo_rest_%'
), calc AS (
  SELECT id, seq, round((1.5 + (seq % 12) + ((seq*3) % 10)/10.0)::numeric, 1) AS req FROM r
)
UPDATE material_requisitions m SET
  requested_quantity = c.req,
  actual_quantity = CASE WHEN c.seq % 5 = 0
                         THEN round((c.req - (0.2 + (c.seq % 3)*0.3))::numeric, 1)
                         ELSE c.req END,
  notes = (ARRAY['', '厨房日常领用', '补货', '备货加工', '周末备量'])[1 + (c.seq % 5)]
FROM calc c WHERE m.id = c.id;

-- 3) 配方 notes 泄露内部种子标记 "PLAN_C_DEMO_SEED_2026_04_25" → 清空.
--    (中餐 BOM/配方本就难精确, 非 demo 重点; 只清泄露串, 不美化.)
UPDATE recipes SET notes = NULL
 WHERE factory_id='DEMO_REST' AND (notes LIKE '%DEMO_SEED%' OR notes LIKE '%PLAN_C%');

COMMIT;


-- ══════════════════════════════════════════════════════════════════════════
-- 库 2: smartbi_prod_db  (psql -U smartbi_user -d smartbi_prod_db)
-- ⚠️ 有 RLS — 必须先 set_config, 否则看不到/改不到行
-- ══════════════════════════════════════════════════════════════════════════
-- SELECT set_config('app.factory_id','DEMO_REST',false);
-- BEGIN;
--
-- -- 4) agg_supplier_price (价格异常预警真源, 110 行): 同供应商名映射.
-- UPDATE agg_supplier_price SET supplier_name = CASE supplier_name
--     WHEN '四通信息有限公司'     THEN '蜀海供应链有限公司'
--     WHEN '网新恒天科技有限公司' THEN '鲜合汇食材配送有限公司'
--     WHEN '通际名联信息有限公司' THEN '蜀味源调味食品有限公司'
--     WHEN '华成育卓网络有限公司' THEN '川鲜农产品配送有限公司'
--     ELSE supplier_name END
-- WHERE factory_id='DEMO_REST'
--   AND supplier_name IN ('四通信息有限公司','网新恒天科技有限公司','通际名联信息有限公司','华成育卓网络有限公司');
--
-- -- 5) 价格异常曲线离谱低价点 (鲈鱼/藤椒各有 ¥10 点, 中位数 ¥36/¥38): 把
-- --    unit_price < 40% 中位数的点抬到中位数附近 (带日期派生自然浮动), 不碰最近
-- --    高价异常点 (epsilon 5% 下真实小波动仍触发异常, 曲线变干净).
-- WITH med AS (
--   SELECT normalized_name,
--          percentile_cont(0.5) WITHIN GROUP (ORDER BY unit_price) AS m
--     FROM agg_supplier_price WHERE factory_id='DEMO_REST' GROUP BY normalized_name
-- )
-- UPDATE agg_supplier_price a
--    SET unit_price  = round((med.m * (0.90 + 0.12 * ((extract(day FROM a.delivery_date)::int % 7) / 6.0)))::numeric, 2),
--        line_amount = round((med.m * (0.90 + 0.12 * ((extract(day FROM a.delivery_date)::int % 7) / 6.0)) * COALESCE(a.quantity,1))::numeric, 2)
--   FROM med
--  WHERE a.factory_id='DEMO_REST' AND a.normalized_name = med.normalized_name
--    AND a.unit_price < 0.4 * med.m;
--
-- COMMIT;
-- (库2 段落注释掉以防在 cretas_prod_db 误跑; 用 smartbi_user 连 smartbi_prod_db 时
--  取消注释并连同 set_config 一起执行. 已于 2026-07-12 手动应用.)
