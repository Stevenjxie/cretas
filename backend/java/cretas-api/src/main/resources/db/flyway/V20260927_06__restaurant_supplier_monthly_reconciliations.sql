-- Restaurant supplier monthly reconciliation (P2P-F) and cost attribution substrate.
-- One immutable-confirmable snapshot per factory + supplier + month.
-- Guard: material_requisitions / wastage_records / stocktaking_records are Hibernate entity tables.

DO $$
BEGIN
    IF to_regclass('public.material_requisitions') IS NULL THEN
        RAISE NOTICE 'V20260927_06 skipped: material_requisitions not yet created (fresh-DB Flyway-before-Hibernate)';
        RETURN;
    END IF;

    ALTER TABLE material_requisitions
        ADD COLUMN IF NOT EXISTS section_code VARCHAR(32),
        ADD COLUMN IF NOT EXISTS operator_id BIGINT,
        ADD COLUMN IF NOT EXISTS stall_code VARCHAR(64),
        ADD COLUMN IF NOT EXISTS chef_id BIGINT,
        ADD COLUMN IF NOT EXISTS head_chef_id BIGINT,
        ADD COLUMN IF NOT EXISTS actual_cost NUMERIC(15,2);

    CREATE INDEX IF NOT EXISTS idx_req_factory_section_date
        ON material_requisitions (factory_id, section_code, requisition_date);

    CREATE INDEX IF NOT EXISTS idx_req_factory_stall_date
        ON material_requisitions (factory_id, stall_code, requisition_date);

    CREATE INDEX IF NOT EXISTS idx_req_factory_operator_date
        ON material_requisitions (factory_id, operator_id, requisition_date);

    ALTER TABLE wastage_records
        ADD COLUMN IF NOT EXISTS operator_id BIGINT,
        ADD COLUMN IF NOT EXISTS section_code VARCHAR(32),
        ADD COLUMN IF NOT EXISTS stall_code VARCHAR(64),
        ADD COLUMN IF NOT EXISTS chef_id BIGINT,
        ADD COLUMN IF NOT EXISTS estimated_cost NUMERIC(15,2);

    CREATE INDEX IF NOT EXISTS idx_wastage_factory_stall_date
        ON wastage_records (factory_id, stall_code, wastage_date);

    CREATE INDEX IF NOT EXISTS idx_wastage_cost_attr_date
        ON wastage_records (factory_id, wastage_date, status, section_code, stall_code);

    CREATE INDEX IF NOT EXISTS idx_wastage_cost_attr_person
        ON wastage_records (factory_id, wastage_date, operator_id, chef_id);

    ALTER TABLE stocktaking_records
        ADD COLUMN IF NOT EXISTS section_code VARCHAR(32),
        ADD COLUMN IF NOT EXISTS stall_code VARCHAR(64),
        ADD COLUMN IF NOT EXISTS difference_amount NUMERIC(15,2);

    CREATE INDEX IF NOT EXISTS idx_stocktaking_factory_section_date
        ON stocktaking_records (factory_id, section_code, stocktaking_date);

    CREATE INDEX IF NOT EXISTS idx_stocktaking_cost_attr_date
        ON stocktaking_records (factory_id, stocktaking_date, status, difference_type, section_code, stall_code);

    CREATE INDEX IF NOT EXISTS idx_stocktaking_cost_attr_person
        ON stocktaking_records (factory_id, stocktaking_date, counted_by);
END $$;

CREATE TABLE IF NOT EXISTS restaurant_supplier_monthly_reconciliations (
    id                     VARCHAR(191) PRIMARY KEY,
    factory_id             VARCHAR(100) NOT NULL,
    supplier_id            VARCHAR(191) NOT NULL,
    supplier_name          VARCHAR(200),
    reconciliation_month   DATE NOT NULL,
    period_start           DATE NOT NULL,
    period_end             DATE NOT NULL,
    status                 VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    delivery_note_count    INTEGER NOT NULL DEFAULT 0,
    ap_transaction_count   INTEGER NOT NULL DEFAULT 0,
    delivery_total         NUMERIC(15,2) NOT NULL DEFAULT 0,
    ap_invoice_total       NUMERIC(15,2) NOT NULL DEFAULT 0,
    ap_payment_total       NUMERIC(15,2) NOT NULL DEFAULT 0,
    ap_adjustment_total    NUMERIC(15,2) NOT NULL DEFAULT 0,
    difference_amount      NUMERIC(15,2) NOT NULL DEFAULT 0,
    net_payable_amount     NUMERIC(15,2) NOT NULL DEFAULT 0,
    confirmed_by           BIGINT,
    confirmed_at           TIMESTAMP,
    created_by             BIGINT,
    created_at             TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at             TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_rsmr_factory_supplier_month
    ON restaurant_supplier_monthly_reconciliations (factory_id, supplier_id, reconciliation_month)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_rsmr_factory_status
    ON restaurant_supplier_monthly_reconciliations (factory_id, status)
    WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS restaurant_supplier_monthly_reconciliation_lines (
    id                       BIGSERIAL PRIMARY KEY,
    reconciliation_id        VARCHAR(191) NOT NULL,
    line_type                VARCHAR(32) NOT NULL,
    delivery_note_id         VARCHAR(191),
    delivery_note_number     VARCHAR(100),
    delivery_date            DATE,
    delivery_amount          NUMERIC(15,2),
    ap_transaction_id        VARCHAR(191),
    ap_transaction_number    VARCHAR(50),
    transaction_date         DATE,
    transaction_type         VARCHAR(32),
    ap_amount                NUMERIC(15,2),
    difference_amount        NUMERIC(15,2),
    line_status              VARCHAR(32) NOT NULL,
    remark                   VARCHAR(500),
    created_at               TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_rsmrl_reconciliation
    ON restaurant_supplier_monthly_reconciliation_lines (reconciliation_id);

CREATE INDEX IF NOT EXISTS idx_rsmrl_delivery_note
    ON restaurant_supplier_monthly_reconciliation_lines (delivery_note_id)
    WHERE delivery_note_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_rsmrl_ap_transaction
    ON restaurant_supplier_monthly_reconciliation_lines (ap_transaction_id)
    WHERE ap_transaction_id IS NOT NULL;
