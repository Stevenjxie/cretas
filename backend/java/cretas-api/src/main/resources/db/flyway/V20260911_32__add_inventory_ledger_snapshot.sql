-- SP11: 库存台账期末快照表
-- 目的: 月结时冻结期初数量/金额, 支持按期间查进销存

CREATE TABLE IF NOT EXISTS inventory_ledger_snapshots (
    id                    VARCHAR(191) PRIMARY KEY,
    factory_id            VARCHAR(191) NOT NULL,
    accounting_period_id  VARCHAR(191) NOT NULL,
    material_type_id      VARCHAR(191) NOT NULL,
    material_code         VARCHAR(64),
    material_name         VARCHAR(128),
    unit                  VARCHAR(32),
    closing_qty           NUMERIC(18,6) NOT NULL DEFAULT 0,
    closing_unit_price    NUMERIC(18,4),
    closing_amount        NUMERIC(18,2),
    snapshot_type         VARCHAR(32)   NOT NULL DEFAULT 'PERIOD_CLOSE',
    created_at            TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP     NOT NULL DEFAULT NOW(),
    deleted_at            TIMESTAMP,
    UNIQUE (factory_id, accounting_period_id, material_type_id, snapshot_type)
);

CREATE INDEX IF NOT EXISTS idx_ils_factory_period ON inventory_ledger_snapshots(factory_id, accounting_period_id);
CREATE INDEX IF NOT EXISTS idx_ils_material ON inventory_ledger_snapshots(material_type_id);

CREATE TRIGGER trg_ils_updated_at
    BEFORE UPDATE ON inventory_ledger_snapshots
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
