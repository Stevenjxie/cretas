-- T145: 原料入库记录「箱数」(粗略统计, 与称重 kg 并存)
-- 称重入库的权威库存数量仍是 kg (receipt_quantity + quantity_unit='kg')。
-- box_count 仅用于粗略统计/展示, 不参与任何库存充足性/可用量/成本/换算计算。
-- 可空 — 未填写时为 NULL, 不从 kg 推导。
ALTER TABLE material_batches ADD COLUMN IF NOT EXISTS box_count INTEGER;

COMMENT ON COLUMN material_batches.box_count IS '粗略统计用箱数, 不参与库存计算';
