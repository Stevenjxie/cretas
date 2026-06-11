-- SP7 F11: 盐化仓独立扣量记录表
-- 客户转录 [91:31]-[91:42]: "盐化要的是单独扣" × 4次强调 + "走盐化的报表指标"
-- 盐化(代加工)出量不混入正常销售报表，独立口径追踪。

CREATE TABLE IF NOT EXISTS salted_warehouse_deductions (
    id              VARCHAR(64)      NOT NULL,
    factory_id      VARCHAR(64)      NOT NULL,
    warehouse_id    VARCHAR(64)      NOT NULL,   -- FK factory_warehouses.id (type=SALTED)
    material_batch_id VARCHAR(191)   NOT NULL,   -- FK material_batches.id
    order_number    VARCHAR(64)      NOT NULL,   -- 盐化供单号 SD-yyyyMMdd-seq
    deduction_date  DATE             NOT NULL,
    quantity        NUMERIC(12, 3)   NOT NULL,
    quantity_unit   VARCHAR(20)      NOT NULL,
    unit_price      NUMERIC(10, 4),              -- 可选；期中出库盘账勾稽依据
    total_amount    NUMERIC(15, 2),
    customer_name   VARCHAR(200),
    operator_id     BIGINT,
    remarks         TEXT,
    created_at      TIMESTAMP        NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP        NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMP,

    CONSTRAINT pk_swd PRIMARY KEY (id),
    CONSTRAINT chk_swd_quantity CHECK (quantity > 0)
);

-- Indexes (mirroring entity @Index annotations)
CREATE INDEX IF NOT EXISTS idx_swd_factory_date ON salted_warehouse_deductions(factory_id, deduction_date);
CREATE INDEX IF NOT EXISTS idx_swd_warehouse    ON salted_warehouse_deductions(warehouse_id);
CREATE INDEX IF NOT EXISTS idx_swd_batch        ON salted_warehouse_deductions(material_batch_id);
CREATE INDEX IF NOT EXISTS idx_swd_order_no     ON salted_warehouse_deductions(factory_id, order_number);

-- updated_at trigger (standard project pattern)
CREATE OR REPLACE FUNCTION update_salted_warehouse_deductions_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_swd_updated_at ON salted_warehouse_deductions;
CREATE TRIGGER trg_swd_updated_at
    BEFORE UPDATE ON salted_warehouse_deductions
    FOR EACH ROW EXECUTE FUNCTION update_salted_warehouse_deductions_updated_at();

COMMENT ON TABLE  salted_warehouse_deductions                 IS 'SP7 F11: 盐化仓独立扣量记录 — 代加工出量独立口径，不混入销售报表';
COMMENT ON COLUMN salted_warehouse_deductions.warehouse_id    IS 'FK factory_warehouses.id; 必须是 type=SALTED 仓库';
COMMENT ON COLUMN salted_warehouse_deductions.order_number    IS '盐化供单号; 谁盐化谁建, 权限下放给销售';
COMMENT ON COLUMN salted_warehouse_deductions.unit_price      IS '可选; 期中出库盘账勾稽: 数量+单价对=账平';
