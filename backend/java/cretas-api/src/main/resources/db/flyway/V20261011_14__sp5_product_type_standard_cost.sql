-- SP5 gap-fill: SP3 未在 product_types 上添加标准成本字段
-- standard_cost: 标准单位成本 (price-sensitive, 用于毛利红线计算)
-- target_gross_margin: 产品级目标毛利率 override (NULL = 回退到工厂全局配置)
ALTER TABLE product_types
    ADD COLUMN IF NOT EXISTS standard_cost        NUMERIC(12,4) NULL,
    ADD COLUMN IF NOT EXISTS target_gross_margin  NUMERIC(6,4)  NULL;

COMMENT ON COLUMN product_types.standard_cost       IS 'SP5 标准单位成本 (price-sensitive)';
COMMENT ON COLUMN product_types.target_gross_margin IS 'SP5 产品级目标毛利率 override (NULL = 回退工厂全局)';
