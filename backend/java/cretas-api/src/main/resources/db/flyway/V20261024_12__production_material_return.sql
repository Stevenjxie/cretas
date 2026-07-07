-- Production material return for zero-inventory production close.
-- Guard: factory_material_requisition_items is a Hibernate entity table.
-- Fresh CI DB runs Flyway before Hibernate, so add the column only when the
-- table already exists. validate-on-migrate=false keeps this safe on prod.

DO $$
BEGIN
    IF to_regclass('public.factory_material_requisition_items') IS NULL THEN
        RAISE NOTICE 'V20261024_12 skipped: factory_material_requisition_items not yet created (fresh-DB Flyway-before-Hibernate)';
    ELSE
        ALTER TABLE factory_material_requisition_items
            ADD COLUMN IF NOT EXISTS wastage_qty NUMERIC(15,3) DEFAULT 0;
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS production_material_returns (
    id                  VARCHAR(64) PRIMARY KEY,
    factory_id          VARCHAR(64) NOT NULL,
    requisition_id      VARCHAR(64) NOT NULL,
    requisition_item_id VARCHAR(64) NOT NULL,
    material_type_id    VARCHAR(64) NOT NULL,
    material_batch_id   VARCHAR(191) NOT NULL,
    return_quantity     NUMERIC(15,3) NOT NULL,
    return_status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at          TIMESTAMP DEFAULT NOW(),
    updated_at          TIMESTAMP DEFAULT NOW(),
    deleted_at          TIMESTAMP NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_pmr_active_req_item_batch
    ON production_material_returns(requisition_item_id, material_batch_id)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_pmr_factory_status
    ON production_material_returns(factory_id, return_status);

CREATE INDEX IF NOT EXISTS idx_pmr_requisition
    ON production_material_returns(requisition_id);

CREATE INDEX IF NOT EXISTS idx_pmr_batch
    ON production_material_returns(material_batch_id);

CREATE INDEX IF NOT EXISTS idx_pmr_material_type
    ON production_material_returns(material_type_id);

ALTER TABLE production_material_returns
    ADD CONSTRAINT chk_pmr_return_status
    CHECK (return_status IN ('PENDING', 'EXECUTED'));
