-- SP4 T2: 一物一码补缺 — 原材料类型税率 + 含税单价
-- raw_material_types 新增 tax_rate + tax_included_unit_price。
-- 两列均可空，存量原料不受影响，采购配置页按需填写。
ALTER TABLE raw_material_types
    ADD COLUMN IF NOT EXISTS tax_rate                VARCHAR(10),
    ADD COLUMN IF NOT EXISTS tax_included_unit_price NUMERIC(15, 4);

COMMENT ON COLUMN raw_material_types.tax_rate                IS 'SP4-A8: 采购税率枚举 TAX_9(9%) / TAX_13(13%)，null=未配置';
COMMENT ON COLUMN raw_material_types.tax_included_unit_price IS 'SP4-A8: 含税单价（发票价），service 层按 tax_rate 换算到 unit_price（未税）';
