-- D-9 G7: 付款申请继承 PO 结算方式
-- PaymentRequest 新增 settlement_type 字段，由 create() 从 PO 继承。
-- nullable，老数据不背填。

ALTER TABLE payment_requests
    ADD COLUMN IF NOT EXISTS settlement_type VARCHAR(32);

COMMENT ON COLUMN payment_requests.settlement_type
    IS 'D-9 G7: 创建时从 purchase_orders.settlement_type 继承。PREPAID/CREDIT_FIRST/NO_INVOICE/MONTHLY/CREDIT_PERIOD/IMMEDIATE';
