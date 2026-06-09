-- SP6 采购到付款 — suppliers 付款条款字段
-- 添加付款方式类型、信用天数
-- current_balance 列已在 V20260415_99 bootstrap 中（若不存在则补）
-- 幂等: ADD COLUMN IF NOT EXISTS

ALTER TABLE suppliers
    ADD COLUMN IF NOT EXISTS payment_terms_type VARCHAR(32) NULL,
    ADD COLUMN IF NOT EXISTS credit_days        INTEGER     NULL,
    ADD COLUMN IF NOT EXISTS current_balance    NUMERIC(12,2) NOT NULL DEFAULT 0;

COMMENT ON COLUMN suppliers.payment_terms_type IS 'SP6: 付款方式(CASH/NET30/NET60/PREPAID 等)';
COMMENT ON COLUMN suppliers.credit_days        IS 'SP6: 信用账期天数，默认开票催收周期';
COMMENT ON COLUMN suppliers.current_balance    IS 'SP6: 供应商当前应付余额（markPaid 三写原子更新）';
