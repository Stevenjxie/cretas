ALTER TABLE factory_stocktakes
    ADD COLUMN IF NOT EXISTS inventory_cutoff_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS counting_started_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS reconciliation_start_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS reconciliation_end_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS reconciliation_preset VARCHAR(30),
    ADD COLUMN IF NOT EXISTS counted_by BIGINT,
    ADD COLUMN IF NOT EXISTS applied_by BIGINT,
    ADD COLUMN IF NOT EXISTS self_confirmed_zero_difference BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_stocktake_last_applied_cutoff
    ON factory_stocktakes(factory_id, warehouse_id, status, applied_at DESC);

COMMENT ON COLUMN factory_stocktakes.inventory_cutoff_at IS
    'Server-authored point-in-time inventory snapshot cutoff; never client supplied';
COMMENT ON COLUMN factory_stocktakes.reconciliation_start_at IS
    'Audit transaction review start only; does not alter system_qty snapshot';
COMMENT ON COLUMN factory_stocktakes.reconciliation_end_at IS
    'Locked to inventory_cutoff_at for new stocktakes';
