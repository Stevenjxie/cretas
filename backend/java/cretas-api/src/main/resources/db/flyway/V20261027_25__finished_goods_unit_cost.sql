-- G3 SFI 成本传导 — 成品批次成本列 (unit_cost)
--
-- 背景: 库存生产小结 (createFinishedGoodsForInterim) 生成成品批次时, 此前只写 unit_price
--   (售价, 来自 product_types 主数据), 从不写成本。SFI-投料成品道 (半成品直接产成品) 的真实成本
--   = ProductionBatch.total_cost (原料+调料+人工) + Σ(SFI 投料 feedKg × 输入 SFI.unit_cost),
--   此前无处落地 → 成品成本为空。
--
-- 本列存成品批次单位成本 (小结时按 outputTotalCost / 入库量 算, 与 SFI 移动均价同源)。
--   🔴 诚实 null: 任一投入 (SFI 投料的 unit_cost / 批次成本) 未知 → unit_cost 留 null (不伪造 ¥0)。
--   additive nullable — 存量行 + 非 SFI-投料成品 行为不变 (null = 无成本数据, 与半成品一致)。
ALTER TABLE finished_goods_batches
    ADD COLUMN IF NOT EXISTS unit_cost NUMERIC(15, 4);

COMMENT ON COLUMN finished_goods_batches.unit_cost IS
    '成品单位成本 (小结成本传导写入; = (批次原料+调料+人工成本 + Σ SFI投料 feedKg×SFI.unit_cost) / 入库量); null = 成本未知(诚实null, 不伪造0)。区别于 unit_price(售价)';
