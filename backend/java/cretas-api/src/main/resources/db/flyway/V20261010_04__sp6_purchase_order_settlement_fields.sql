-- SP6 采购到付款 — purchase_orders 结算字段
-- 添加结算类型、合同编号、发票催收天数
-- 幂等: DO NOTHING 可重复执行

ALTER TABLE purchase_orders
    ADD COLUMN IF NOT EXISTS settlement_type       VARCHAR(32)  NULL,
    ADD COLUMN IF NOT EXISTS contract_number       VARCHAR(100) NULL,
    ADD COLUMN IF NOT EXISTS invoice_reminder_days INTEGER      NULL;

COMMENT ON COLUMN purchase_orders.settlement_type       IS 'SP6: 结算方式(CASH/NET30/NET60/PREPAID 等)';
COMMENT ON COLUMN purchase_orders.contract_number       IS 'SP6: 合同编号，关联采购框架合同';
COMMENT ON COLUMN purchase_orders.invoice_reminder_days IS 'SP6: 到期催票天数，覆盖供应商默认值';
