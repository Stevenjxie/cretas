-- Restaurant supplier delivery onsite price anomaly explanation.
-- Deng requirement: when today's supplier unit price jumps beyond a reasonable
-- range, warehouse/procurement must record the supplier explanation before
-- confirming inbound posting.

ALTER TABLE supplier_delivery_note_lines
    ADD COLUMN IF NOT EXISTS baseline_unit_price NUMERIC(12,4),
    ADD COLUMN IF NOT EXISTS price_variance_rate NUMERIC(8,4),
    ADD COLUMN IF NOT EXISTS price_anomaly_flag BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS price_anomaly_reason_code VARCHAR(50),
    ADD COLUMN IF NOT EXISTS price_anomaly_explanation TEXT;

CREATE INDEX IF NOT EXISTS idx_sdnl_price_anomaly
    ON supplier_delivery_note_lines (factory_id, price_anomaly_flag)
    WHERE price_anomaly_flag = TRUE;
