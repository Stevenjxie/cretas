-- Restaurant supplier delivery: boss approval gate for onsite price anomalies.
-- Deng requirement: explanation alone is not enough; owner/manager must approve before inbound posting.

ALTER TABLE supplier_delivery_notes
    ADD COLUMN IF NOT EXISTS price_anomaly_approval_status VARCHAR(20) NOT NULL DEFAULT 'NONE',
    ADD COLUMN IF NOT EXISTS price_anomaly_submitted_by BIGINT,
    ADD COLUMN IF NOT EXISTS price_anomaly_submitted_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS price_anomaly_approved_by BIGINT,
    ADD COLUMN IF NOT EXISTS price_anomaly_approved_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS price_anomaly_rejected_by BIGINT,
    ADD COLUMN IF NOT EXISTS price_anomaly_rejected_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS price_anomaly_approval_comment TEXT;

CREATE INDEX IF NOT EXISTS idx_sdn_price_anomaly_pending
    ON supplier_delivery_notes (factory_id, price_anomaly_approval_status)
    WHERE price_anomaly_approval_status = 'PENDING';
