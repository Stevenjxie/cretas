-- The active Flyway location is db/flyway. The original FMR schema lives in
-- legacy db/migration and is therefore absent on a genuinely fresh database.
-- Keep this version between V20261028_69 and V20261028_70 so V70 can add and
-- backfill its display snapshot columns. Existing databases are unaffected by
-- CREATE TABLE/INDEX IF NOT EXISTS.

CREATE TABLE IF NOT EXISTS factory_material_requisitions (
    id                  VARCHAR(64) PRIMARY KEY,
    factory_id          VARCHAR(64) NOT NULL,
    requisition_no      VARCHAR(40) NOT NULL UNIQUE,
    production_plan_id  VARCHAR(64) NOT NULL,
    source_warehouse_id VARCHAR(64),
    target_warehouse_id VARCHAR(64),
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    required_date       DATE,
    requested_by        BIGINT,
    picked_by           BIGINT,
    picked_at           TIMESTAMP,
    transferred_by      BIGINT,
    transferred_at      TIMESTAMP,
    received_by         BIGINT,
    received_at         TIMESTAMP,
    closed_by           BIGINT,
    closed_at           TIMESTAMP,
    remarks             TEXT,
    outbound_transfer_id VARCHAR(191),
    return_transfer_id   VARCHAR(191),
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at          TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_fmr_factory_status
    ON factory_material_requisitions(factory_id, status);
CREATE INDEX IF NOT EXISTS idx_fmr_plan
    ON factory_material_requisitions(production_plan_id);
CREATE INDEX IF NOT EXISTS idx_fmr_required_date
    ON factory_material_requisitions(required_date);

CREATE TABLE IF NOT EXISTS factory_material_requisition_items (
    id                 VARCHAR(64) PRIMARY KEY,
    requisition_id     VARCHAR(64) NOT NULL
        REFERENCES factory_material_requisitions(id) ON DELETE CASCADE,
    material_type_id   VARCHAR(64) NOT NULL,
    material_name      VARCHAR(200),
    material_category  VARCHAR(20) DEFAULT 'RAW',
    bom_item_id        BIGINT,
    required_qty       NUMERIC(15,3),
    picked_qty         NUMERIC(15,3),
    issued_qty         NUMERIC(15,3),
    consumed_qty       NUMERIC(15,3),
    returned_qty       NUMERIC(15,3),
    unit               VARCHAR(20),
    batch_numbers      JSONB,
    created_at         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at         TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_fmri_req
    ON factory_material_requisition_items(requisition_id);
CREATE INDEX IF NOT EXISTS idx_fmri_material
    ON factory_material_requisition_items(material_type_id);

COMMENT ON TABLE factory_material_requisitions IS
    'Factory material requisitions between production planning and inventory transfer';
COMMENT ON TABLE factory_material_requisition_items IS
    'BOM-expanded material requisition details';
