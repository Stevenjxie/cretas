-- SP5: 销售订单添加提成预览字段
-- commission_preview: 当前订单按毛利红线估算的提成金额预览 (仅展示，不影响结算)
-- commission_rate_pct: 套用的提成费率 (百分比, 0-100)
ALTER TABLE sales_orders
    ADD COLUMN IF NOT EXISTS commission_preview   NUMERIC(12,2) NULL,
    ADD COLUMN IF NOT EXISTS commission_rate_pct  NUMERIC(6,4) NULL;

COMMENT ON COLUMN sales_orders.commission_preview  IS 'SP5 提成预览金额 (price-sensitive)';
COMMENT ON COLUMN sales_orders.commission_rate_pct IS 'SP5 套用提成费率 %';
