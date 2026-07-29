-- 提高 POS 事实表 / 菜品维度上租户与菜品列的统计采样精度。
--
-- 背景 (2026-07-29 实测): 餐饮问答里的菜品类 resolver 有一段 anchor CTE:
--     SELECT MAX(t2.date)
--       FROM fact_pos_item i2
--       JOIN fact_pos_transaction t2 ON t2.id = i2.transaction_id
--       JOIN dim_product p2 ON p2.product_id = i2.product_id
--                          AND p2.factory_id = i2.factory_id
--      WHERE i2.factory_id = $1 AND t2.factory_id = $1
--
-- 在默认统计精度 (default_statistics_target = 100) 下, planner 对
-- `(factory_id, product_id)` 的选择性估计严重偏低 —— 实测某租户
-- dim_product 估 1 行 (实际 10)、fact_pos_item 每菜品估 133 行
-- (实际约 24,000)。于是选了嵌套循环, 对 24 万条明细逐条回查
-- fact_pos_transaction 主键, 单次查询**跑 15 分钟以上不返回**,
-- 问答请求直接挂死 (curl 240s 超时)。
--
-- 根因是**租户数据高度倾斜**: 全表 590 万行明细跨多个租户, 单个租户占 4%,
-- 但该租户内部只有 10 个菜品, 每个菜品对应约 2.4 万行。默认采样描述不出
-- 这种"跨租户稀疏、租户内密集"的分布。
--
-- ⚠️ 这个问题此前一直被掩盖着: 该租户的 fact_pos_item.product_id 全是 NULL,
--    上面那个 JOIN 空转、秒回。2026-07-29 回填了 24 万个 product_id 之后
--    它第一次真正干活, 病态才暴露出来。
--
-- 改统计精度是零代码风险的做法; 提到 1000 之后同一查询从 240s 超时降到
-- **0.69s**。SET STATISTICS 只改列的元数据, 不改数据也不锁表(仅需短暂
-- ACCESS EXCLUSIVE 改 catalog), 随后的 ANALYZE 才真正重新采样。
--
-- 后续 (不在本 migration 范围): 那段 anchor CTE 其实不需要 JOIN dim_product
-- 就能取到 MAX(date), 去掉这个 JOIN 是更彻底的修法, 但它是多租户共用的
-- resolver, 应单独评估。

ALTER TABLE fact_pos_item        ALTER COLUMN factory_id SET STATISTICS 1000;
ALTER TABLE fact_pos_item        ALTER COLUMN product_id SET STATISTICS 1000;
ALTER TABLE fact_pos_transaction ALTER COLUMN factory_id SET STATISTICS 1000;
ALTER TABLE dim_product          ALTER COLUMN factory_id SET STATISTICS 1000;

-- SET STATISTICS 本身不重新采样, 必须跑 ANALYZE 才生效。
-- 放在 migration 里跑一次, 之后交给 autovacuum。
ANALYZE fact_pos_item;
ANALYZE fact_pos_transaction;
ANALYZE dim_product;
