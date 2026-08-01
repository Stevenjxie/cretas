-- 盘点总量口径对齐: 只算 COMPLETED (2026-08-01)
--
-- 问题: 同一份盘点数据在两个地方口径不同 ——
--   按食材 (_AGG_STOCK_SHORTAGE_SQL / _AGG_STOCK_SHORTAGE_COST_SQL): 过滤 COMPLETED
--   本表   (_AGG_DAILY_TOTALS_SQL 的盘点子查询):                     **不过滤**
-- 于是「盘亏总量」与「Top N 之和」天然对不上, 而且**未完成/已取消的盘点单被算成
-- 损失** —— 一张作废的盘点单不该产生亏损。
--
-- prod 实测 (2026-08-01):
--   R_XMX_CHAIN  盘亏总量 1.50, 其中 COMPLETED 侧为 **0.00** —— 全部来自一张未完成单
--   F002         3 张未完成单, 但都没有负差异, 不影响金额
--
-- ETL 已在同一个 PR 里加上 status 过滤; 本文件只负责把**存量**立刻对齐, 否则要等到
-- 次日 03:30 gold-etl 跑完才一致。口径与 ETL 逐字相同, 两边漂开的表现是
-- 「跑完 ETL 数字自己变了」。
--
-- RLS: 目标表 FORCE ROW LEVEL SECURITY 且 smartbi_user 不 bypass; runner 以
-- `sudo -u postgres` 执行 (apply-smartbi-migrations.sh:94/100), postgres 的
-- rolbypassrls = t, 所以这条跨租户 UPDATE 能生效。换非超级用户跑会静默 0 行。

UPDATE agg_restaurant_daily_totals t
   SET stocktaking_count          = COALESCE(s.cnt, 0),
       stocktaking_shortage_total = COALESCE(s.shortage, 0),
       stocktaking_surplus_total  = COALESCE(s.surplus, 0),
       stocktaking_shortage_cost  = COALESCE(s.shortage_cost, 0),
       stocktaking_surplus_cost   = COALESCE(s.surplus_cost, 0)
  FROM (
    SELECT factory_id,
           date,
           COUNT(*) AS cnt,
           SUM(CASE WHEN difference_qty < 0 THEN -difference_qty ELSE 0 END) AS shortage,
           SUM(CASE WHEN difference_qty > 0 THEN  difference_qty ELSE 0 END) AS surplus,
           SUM(CASE WHEN difference_qty < 0 THEN ABS(COALESCE(difference_cost, 0)) ELSE 0 END) AS shortage_cost,
           SUM(CASE WHEN difference_qty > 0 THEN ABS(COALESCE(difference_cost, 0)) ELSE 0 END) AS surplus_cost
      FROM fact_restaurant_stocktaking
     WHERE status = 'COMPLETED'
     GROUP BY factory_id, date
  ) s
 WHERE t.factory_id = s.factory_id
   AND t.date = s.date;

-- 该日期原本有盘点行、但过滤后一张 COMPLETED 都不剩 → 必须归零, 否则旧值留在表里
-- (上面的 UPDATE ... FROM 匹配不到这些行)。R_XMX_CHAIN 就是这个情况。
UPDATE agg_restaurant_daily_totals t
   SET stocktaking_count          = 0,
       stocktaking_shortage_total = 0,
       stocktaking_surplus_total  = 0,
       stocktaking_shortage_cost  = 0,
       stocktaking_surplus_cost   = 0
 WHERE EXISTS (
         SELECT 1 FROM fact_restaurant_stocktaking f
          WHERE f.factory_id = t.factory_id AND f.date = t.date
       )
   AND NOT EXISTS (
         SELECT 1 FROM fact_restaurant_stocktaking f
          WHERE f.factory_id = t.factory_id AND f.date = t.date
            AND f.status = 'COMPLETED'
       );
