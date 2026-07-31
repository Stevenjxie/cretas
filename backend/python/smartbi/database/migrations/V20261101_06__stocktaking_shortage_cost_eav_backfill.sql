-- 补 V20261101_05 漏掉的那一半：按食材的金额 EAV 行 (2026-08-01)
--
-- V20261101_05 只回填了标量表 agg_restaurant_daily_totals 的两个金额列, 但
-- resolve_stock_shortage 的 **Top-N 列表**读的是 agg_restaurant_daily_ops 里
-- kpi_kind='stocktaking_shortage_cost' 的行 —— 那些行只有 ETL 才产出。
--
-- 实测 (prod, 合入 #2112 之后): agg_restaurant_daily_ops 里
--   stocktaking_shortage_qty   286 行 / 5 租户
--   stocktaking_shortage_cost  **0 行**
--
-- 只部 #2112 而不补这一条, 线上会拿到一个半对半错的答案:
--   主句「盘亏金额 ¥6999.04」对 (totals 已回填),
--   而下面每一项都是「盘亏 ¥0.00 (26.70 kg)」, 且排序键全 NULL 导致名次任意。
-- 这个状态会持续到下一次 gold-etl (每日 03:30) 跑完为止。
--
-- ⚠️ 为什么 #2112 的干跑没暴露这个: 那次干跑里我**手动执行了 ETL 的
-- _AGG_STOCK_SHORTAGE_COST_SQL**, 于是 resolver 查询有数据可读 ——
-- 把「migration 自己不产这些行」这件事一起掩盖了。
--
-- 本文件与 restaurant_ops_etl._AGG_STOCK_SHORTAGE_COST_SQL **逐字同口径**
-- (status='COMPLETED' + 金额取 ABS + dim_value_id 用 COALESCE(ingredient_id, 0)),
-- 区别只有两处: 不带 $1 租户参数(一次回填全部租户), 且用 ON CONFLICT 幂等。
-- 口径若与 ETL 漂开, 表现是「跑完 ETL 数字自己变了」。
--
-- ABS 的理由见 V20261101_05 与 ETL 中同名注释 —— difference_cost 的符号在写入侧
-- 就不统一, 模拟器产出的 MOCK_REST 650 行全为负, 而它是每日能力审计的参考租户。
--
-- RLS: 两张表都是 FORCE ROW LEVEL SECURITY 且 smartbi_user 不 bypass。runner 以
-- `sudo -u postgres` 执行 (apply-smartbi-migrations.sh:94/100), postgres 的
-- rolbypassrls = t, 所以这条跨租户 INSERT 能生效。换非超级用户跑会静默 0 行。

INSERT INTO agg_restaurant_daily_ops (
    factory_id, date, kpi_kind, dim_value_id, dim_value_str, value_num,
    version, computed_at
)
SELECT factory_id,
       date,
       'stocktaking_shortage_cost',
       COALESCE(ingredient_id, 0),
       '',
       SUM(CASE WHEN difference_qty < 0 THEN ABS(COALESCE(difference_cost, 0)) ELSE 0 END)::NUMERIC(18,4),
       1,
       NOW()
  FROM fact_restaurant_stocktaking
 WHERE status = 'COMPLETED'
 GROUP BY factory_id, date, ingredient_id
ON CONFLICT (factory_id, date, kpi_kind, dim_value_id, dim_value_str) DO UPDATE SET
    value_num = EXCLUDED.value_num,
    version = agg_restaurant_daily_ops.version + 1,
    computed_at = NOW();
