-- Restaurant supplier delivery payable posting (P2P-E).
-- Links non-PO restaurant inbound delivery notes to AP_INVOICE transactions.

ALTER TABLE ar_ap_transactions
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(50),
    ADD COLUMN IF NOT EXISTS source_id VARCHAR(191);

CREATE UNIQUE INDEX IF NOT EXISTS ux_ar_ap_invoice_source
    ON ar_ap_transactions (factory_id, transaction_type, source_type, source_id)
    WHERE deleted_at IS NULL
      AND transaction_type = 'AP_INVOICE'
      AND source_type IS NOT NULL
      AND source_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_ar_ap_source
    ON ar_ap_transactions (factory_id, source_type, source_id)
    WHERE deleted_at IS NULL
      AND source_type IS NOT NULL
      AND source_id IS NOT NULL;

ALTER TABLE supplier_delivery_notes
    ADD COLUMN IF NOT EXISTS payable_transaction_id VARCHAR(191),
    ADD COLUMN IF NOT EXISTS payable_posted_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS payable_posting_error TEXT;

CREATE INDEX IF NOT EXISTS idx_sdn_payable_transaction
    ON supplier_delivery_notes (factory_id, payable_transaction_id)
    WHERE deleted_at IS NULL
      AND payable_transaction_id IS NOT NULL;
