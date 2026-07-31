-- 盘点差异的金额口径进 Gold (2026-08-01)
--
-- 为什么需要: 「盘点亏了多少」问的是钱, 而 Gold 层此前只物化了**数量**
-- (`stocktaking_shortage_total` = SUM(-difference_qty))。数量不能跨食材相加 ——
-- 实测 DEMO_REST 是 41.45kg + 45.00L, resolver 把它们加成一个不带单位的 "86.45"。
-- 对照: 领料和损耗在本表里 qty 和 cost 都物化了 (`requisition_cost_total` /
-- `wastage_cost_total`), 只有盘点缺金额那一半。
--
-- 数据本来就有: `fact_restaurant_stocktaking.difference_cost` 来自源库
-- `cretas_db.stocktaking_records.difference_amount`, 实测非空率
-- MOCK_REST 650/650、DEMO_REST 201/201。Gold ETL 从头到尾没读过这一列。
--
-- ⛔ 符号必须 ABS —— 这不是防御性写法, 是实测:
--   `difference_amount` 的符号约定在写入侧就不统一。源库里 DEMO_REST / F002 /
--   RES_3101_009 的盘亏行是**正**金额 (14 行), R_XMX_CHAIN 是**负**的 (1 行);
--   而模拟器产出的 MOCK_REST 在 smartbi 侧 650 行**全是负**的。
--   Java 自己也知道: StocktakingRecordRepository:64 写 `SUM(ABS(s.differenceAmount))`,
--   而同一个 repository 的第 96 行 `SUM(s.differenceAmount)` 不带 ABS。
--   Silver 那句 `difference_cost -- |difference_qty| × unit_price` 的注释是错的,
--   ETL 原样搬了带符号的值。
--   直接 SUM 的后果: MOCK_REST 会答「盘亏 -6999.04」—— 而它正是每日能力审计打
--   19/19 的参考租户, DEMO_REST 上却看着完全正常。
--
-- 本次只在**读取侧**归一 (Steve 2026-08-01 拍板): 不改存量数据, 不动 Java 写入侧。

ALTER TABLE agg_restaurant_daily_totals
    ADD COLUMN IF NOT EXISTS stocktaking_shortage_cost NUMERIC(14,2),  -- SUM(ABS(cost)) WHERE qty < 0
    ADD COLUMN IF NOT EXISTS stocktaking_surplus_cost  NUMERIC(14,2);  -- SUM(ABS(cost)) WHERE qty > 0

-- 回填: 与 restaurant_ops_etl._AGG_DAILY_TOTALS_SQL 的盘点分支**逐字同口径** ——
-- 按 difference_qty 的符号分盘亏/盘盈, 且**不过滤 status**。两处口径必须一致,
-- 否则回填值和下一次 ETL 覆盖出来的值会不同, 表现是「跑完 ETL 数字自己变了」。
--
-- ⚠️ 记账(本次不改): 同一份盘点数据在两个地方口径不同 —— 本表(totals)的盘亏数量
-- 不过滤 status, 而按食材的 `_AGG_STOCK_SHORTAGE_SQL` 过滤了 status='COMPLETED'。
-- 于是「总量」和「Top N 之和」本来就对不上, 且未完成/已取消的盘点也计入了总量。
-- 新增的金额列跟随**它同表的数量列**, 不在本次顺手改既有行为。
--
-- RLS: 本表 FORCE ROW LEVEL SECURITY 且 smartbi_user 不 bypass。runner 以
-- `sudo -u postgres` 执行 (apply-smartbi-migrations.sh:94/100), postgres 的
-- rolbypassrls = t, 所以这条 UPDATE 能跨租户生效。换非超级用户跑会**静默 0 行**。
UPDATE agg_restaurant_daily_totals t
   SET stocktaking_shortage_cost = s.shortage_cost,
       stocktaking_surplus_cost  = s.surplus_cost
  FROM (
    SELECT factory_id,
           date,
           SUM(CASE WHEN difference_qty < 0 THEN ABS(COALESCE(difference_cost, 0)) ELSE 0 END) AS shortage_cost,
           SUM(CASE WHEN difference_qty > 0 THEN ABS(COALESCE(difference_cost, 0)) ELSE 0 END) AS surplus_cost
      FROM fact_restaurant_stocktaking
     GROUP BY factory_id, date
  ) s
 WHERE t.factory_id = s.factory_id
   AND t.date = s.date;
