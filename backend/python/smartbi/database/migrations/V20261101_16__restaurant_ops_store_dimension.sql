-- 后厨三张事实表补 `store_id` —— 门店一路带到写库前，被最后一层丢掉了。
--
-- ## 缺陷（2026-08-17 逐层实测，⛔ 不是从代码推的）
--
-- 老板问「哪家店损耗最多 / 哪家店缺货最严重 / 按门店看领用趋势」，
-- 全部撞维度闸拒答。我一度把它判成「数据层没有，做不到」——**判错了**。
-- 逐层查下来，门店信息**每一层都在**，只有最后一跳没接：
--
--   餐饮平台模拟器 `wastage.store_id  NOT NULL REFERENCES store(id)`   ✅ 有
--                 `requisition.store_id / stocktaking.store_id`        ✅ 有
--   平台 payload   `shopCode`                                          ✅ 有
--   归一化模型     `NormalizedWastage.store_code`（`_require_text` 必填）✅ 有
--   写库 ops_writer.py 的 INSERT 列清单                                🔴 一个字没接
--   fact_restaurant_*                                                  🔴 没有这一列
--
-- ⇒ 形态 B 第 7 例（投影丢失）：产出端有、归一化层有、**消费端收不到**。
--   这与「数据缺失」的修法完全相反 —— 前者接线，后者补数据，搞混会去
--   补一份根本不缺的数据。
--
-- ## 为什么可空
--
-- 存量行是在这一列存在之前写进来的，没有门店可填。⛔ 不给默认值 ——
-- 编一个 store_id 会让「按门店看损耗」把所有历史损耗算到某一家店头上，
-- 那比拒答糟得多（本仓：读不到就炸，比读到一个假的 0 好）。
-- 回填靠重跑 ingestion：writer 是 `ON CONFLICT (factory_id, source_pk) DO UPDATE`，
-- 重放同一批单据会把 store_id 补上，⛔ 不需要手写回填 SQL。
--
-- ## 索引
--
-- 与既有命名一致（`idx_fact_<x>_factory_<dim>`）。按门店分组的查询一律
-- 带 `factory_id`（RLS 也按它过滤），所以复合索引首列是 factory_id。

ALTER TABLE fact_restaurant_wastage      ADD COLUMN IF NOT EXISTS store_id BIGINT;
ALTER TABLE fact_restaurant_requisition  ADD COLUMN IF NOT EXISTS store_id BIGINT;
ALTER TABLE fact_restaurant_stocktaking  ADD COLUMN IF NOT EXISTS store_id BIGINT;

COMMENT ON COLUMN fact_restaurant_wastage.store_id IS
    '门店（dim_store.store_id）。可空：本列 2026-08-17 才加，存量行没有门店可填；'
    '⛔ 不许兜底成某一家店。重跑 ingestion 会 UPSERT 补上。';
COMMENT ON COLUMN fact_restaurant_requisition.store_id IS
    '门店（dim_store.store_id）。可空，理由同 fact_restaurant_wastage.store_id。';
COMMENT ON COLUMN fact_restaurant_stocktaking.store_id IS
    '门店（dim_store.store_id）。可空，理由同 fact_restaurant_wastage.store_id。';

CREATE INDEX IF NOT EXISTS idx_fact_wastage_factory_store
    ON fact_restaurant_wastage (factory_id, store_id, date);
CREATE INDEX IF NOT EXISTS idx_fact_req_factory_store
    ON fact_restaurant_requisition (factory_id, store_id, date);
CREATE INDEX IF NOT EXISTS idx_fact_stock_factory_store
    ON fact_restaurant_stocktaking (factory_id, store_id, date);
