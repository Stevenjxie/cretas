-- V20261023_01: 包材规格 — 新增 pack_qty_per_product 列到 raw_material_types
--
-- 语义: 生产 1 个最小成品单位 (outputQuantityPerUnit) 需要此包材多少个/袋/盒。
-- 示例:
--   吸塑盒 "每产品1个"   → pack_qty_per_product = 1.000000
--   气调膜 "每产品1片"   → pack_qty_per_product = 1.000000
--   外箱   "每箱20盒成品" → pack_qty_per_product = 0.050000
--
-- NULL = 未配置规格 (历史包材记录保持现状), 需在 BOM 行手填 standardQuantity.
-- 向后兼容: NULL 时 BOM 保存逻辑退回原手填路径 (与 PR #751 不冲突).

ALTER TABLE raw_material_types
    ADD COLUMN IF NOT EXISTS pack_qty_per_product NUMERIC(15, 6) NULL;

COMMENT ON COLUMN raw_material_types.pack_qty_per_product IS
    '包材每产品单位用量 (个/袋/盒 per outputQuantityPerUnit); NULL=未配置, BOM 行手填; 仅 category=PACKAGING 有意义';
