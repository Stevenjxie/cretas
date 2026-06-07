-- T123: 产品规格两级单位 + 产品名称分离
-- 复用 box_conversion_coefficient = 一级单位数量(一筐=N个二级单位)
-- 复用 unit = 二级单位 (现有字段)
-- 复用 grams_per_unit = 二级单位克重 (现有字段)
-- 新增: level1_unit (一级单位名称, 如"筐"/"箱") + base_product_name (产品基础名, 名称分离)

ALTER TABLE product_types
    ADD COLUMN IF NOT EXISTS level1_unit VARCHAR(20),
    ADD COLUMN IF NOT EXISTS base_product_name VARCHAR(200);

COMMENT ON COLUMN product_types.level1_unit IS '一级单位 (如: 筐, 箱, 件, 袋, 桶, 盒) — 与 box_conversion_coefficient 联用, 1 level1_unit = box_conversion_coefficient 个 unit(二级单位)';
COMMENT ON COLUMN product_types.base_product_name IS '产品基础名 (名称分离, 如"好食光卤猪蹄"), 供 RN 展示优先使用; 无则 fallback 到 name';
