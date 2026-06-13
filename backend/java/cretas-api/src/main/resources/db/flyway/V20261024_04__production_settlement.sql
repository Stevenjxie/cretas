CREATE TABLE IF NOT EXISTS production_settlements (
    id VARCHAR(191) PRIMARY KEY,
    factory_id VARCHAR(50) NOT NULL,
    production_plan_id VARCHAR(191) NOT NULL,
    plan_number VARCHAR(50) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    planned_quantity NUMERIC(12, 2) NOT NULL,
    actual_finished_quantity NUMERIC(12, 2) NOT NULL,
    actual_semi_finished_quantity NUMERIC(12, 2) NOT NULL DEFAULT 0,
    quantity_unit VARCHAR(20),
    quantity_variance_reason VARCHAR(64),
    quantity_variance_note TEXT,
    material_variance_reason VARCHAR(64),
    material_variance_note TEXT,
    labor_deferred_reason VARCHAR(64),
    plan_status_after VARCHAR(30) NOT NULL,
    posting_status VARCHAR(30) NOT NULL DEFAULT 'PENDING_POSTING',
    posting_message TEXT,
    settled_by BIGINT,
    settled_at TIMESTAMP NOT NULL DEFAULT NOW(),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP,
    CONSTRAINT uq_prod_settlement_plan UNIQUE (factory_id, production_plan_id),
    CONSTRAINT uq_prod_settlement_idem UNIQUE (factory_id, production_plan_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_prod_settlement_factory_plan
    ON production_settlements(factory_id, production_plan_id);
CREATE INDEX IF NOT EXISTS idx_prod_settlement_status
    ON production_settlements(factory_id, posting_status);

CREATE TABLE IF NOT EXISTS production_settlement_consumptions (
    id BIGSERIAL PRIMARY KEY,
    settlement_id VARCHAR(191) NOT NULL,
    factory_id VARCHAR(50) NOT NULL,
    production_plan_id VARCHAR(191) NOT NULL,
    source_type VARCHAR(30) NOT NULL,
    material_batch_id VARCHAR(191),
    semi_finished_inventory_id BIGINT,
    material_type_id VARCHAR(191),
    batch_number VARCHAR(100),
    quantity NUMERIC(12, 2) NOT NULL,
    unit VARCHAR(20),
    available_before NUMERIC(12, 2),
    warehouse_id VARCHAR(64),
    note TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP,
    CONSTRAINT chk_prod_settlement_cons_source
        CHECK (source_type IN ('RAW_MATERIAL', 'SEMI_FINISHED', 'AUXILIARY'))
);

CREATE INDEX IF NOT EXISTS idx_prod_settlement_cons_settlement
    ON production_settlement_consumptions(settlement_id);
CREATE INDEX IF NOT EXISTS idx_prod_settlement_cons_plan
    ON production_settlement_consumptions(factory_id, production_plan_id);

CREATE TABLE IF NOT EXISTS production_settlement_labor (
    id BIGSERIAL PRIMARY KEY,
    settlement_id VARCHAR(191) NOT NULL,
    factory_id VARCHAR(50) NOT NULL,
    production_plan_id VARCHAR(191) NOT NULL,
    worker_id BIGINT,
    worker_name VARCHAR(100),
    work_type VARCHAR(64),
    minutes INTEGER NOT NULL,
    headcount INTEGER NOT NULL DEFAULT 1,
    hourly_rate NUMERIC(12, 2),
    labor_cost NUMERIC(12, 2),
    note TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_prod_settlement_labor_settlement
    ON production_settlement_labor(settlement_id);
CREATE INDEX IF NOT EXISTS idx_prod_settlement_labor_plan
    ON production_settlement_labor(factory_id, production_plan_id);
