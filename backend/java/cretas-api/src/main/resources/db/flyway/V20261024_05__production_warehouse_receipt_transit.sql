ALTER TABLE production_settlements
    ADD COLUMN IF NOT EXISTS warehouse_receipt_idempotency_key VARCHAR(128),
    ADD COLUMN IF NOT EXISTS warehouse_received_quantity NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS warehouse_variance_quantity NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS warehouse_variance_reason VARCHAR(64),
    ADD COLUMN IF NOT EXISTS warehouse_responsibility_side VARCHAR(30),
    ADD COLUMN IF NOT EXISTS warehouse_variance_note TEXT,
    ADD COLUMN IF NOT EXISTS finished_goods_batch_id VARCHAR(191),
    ADD COLUMN IF NOT EXISTS transit_ledger_id VARCHAR(191),
    ADD COLUMN IF NOT EXISTS warehouse_received_by BIGINT,
    ADD COLUMN IF NOT EXISTS warehouse_received_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_prod_settlement_receipt_status
    ON production_settlements(factory_id, posting_status, warehouse_received_at);

CREATE TABLE IF NOT EXISTS production_transit_ledgers (
    id VARCHAR(191) PRIMARY KEY,
    factory_id VARCHAR(50) NOT NULL,
    settlement_id VARCHAR(191) NOT NULL,
    production_plan_id VARCHAR(191) NOT NULL,
    plan_number VARCHAR(50) NOT NULL,
    ledger_type VARCHAR(40) NOT NULL,
    reported_quantity NUMERIC(12, 2) NOT NULL,
    confirmed_quantity NUMERIC(12, 2) NOT NULL,
    variance_quantity NUMERIC(12, 2) NOT NULL,
    tolerance_quantity NUMERIC(12, 2) NOT NULL,
    quantity_unit VARCHAR(20),
    variance_reason VARCHAR(64) NOT NULL,
    responsibility_side VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    note TEXT,
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP,
    CONSTRAINT chk_prod_transit_ledger_type
        CHECK (ledger_type IN ('FINISHED_GOODS_RECEIPT', 'RAW_MATERIAL_ISSUE', 'SEMI_FINISHED_ISSUE')),
    CONSTRAINT chk_prod_transit_responsibility
        CHECK (responsibility_side IN ('PENDING', 'PRODUCTION', 'WAREHOUSE', 'WEIGHING_ERROR')),
    CONSTRAINT chk_prod_transit_status
        CHECK (status IN ('OPEN', 'RESOLVED', 'VOIDED'))
);

CREATE INDEX IF NOT EXISTS idx_prod_transit_factory_status
    ON production_transit_ledgers(factory_id, status);
CREATE INDEX IF NOT EXISTS idx_prod_transit_plan
    ON production_transit_ledgers(factory_id, production_plan_id);
CREATE INDEX IF NOT EXISTS idx_prod_transit_settlement
    ON production_transit_ledgers(settlement_id);
