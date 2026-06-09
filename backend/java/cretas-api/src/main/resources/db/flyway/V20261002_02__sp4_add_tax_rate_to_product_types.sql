-- SP4 T2: 一物一码补缺 — 产品类型税率枚举列
-- product_types 已有 tax_included_unit_price; 此次补加 tax_rate (TAX_9 / TAX_13).
-- 两列均可空，存量产品不受影响，产品配置页按需填写。
ALTER TABLE product_types
    ADD COLUMN IF NOT EXISTS tax_rate VARCHAR(10);

COMMENT ON COLUMN product_types.tax_rate IS 'SP4-A8: 出货税率枚举 TAX_9(9%) / TAX_13(13%)，null=未配置';
