-- 执行跟踪(排线确认后的执行阶段): 门店配送订单加「送达/异常」状态 + 异常原因/处置/备注。
-- additive: 全部默认 PENDING, 不影响既有数据。planning 态 status(IMPORTED/PLANNED/CONFIRMED/CANCELLED)不变,
-- delivery_status 是独立的执行态维度。

ALTER TABLE logistics_delivery_orders
    ADD COLUMN IF NOT EXISTS delivery_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS delivered_at TIMESTAMP NULL,
    ADD COLUMN IF NOT EXISTS exception_reason VARCHAR(32) NULL,
    ADD COLUMN IF NOT EXISTS exception_disposition VARCHAR(32) NULL,
    ADD COLUMN IF NOT EXISTS exception_note VARCHAR(500) NULL;

-- CHECK 含全部枚举值(additive DROP+ADD)
ALTER TABLE logistics_delivery_orders DROP CONSTRAINT IF EXISTS ck_ldo_delivery_status;
ALTER TABLE logistics_delivery_orders
    ADD CONSTRAINT ck_ldo_delivery_status CHECK (delivery_status IN ('PENDING','DELIVERED','EXCEPTION'));

CREATE INDEX IF NOT EXISTS idx_ldo_delivery_status ON logistics_delivery_orders (batch_id, delivery_status);
