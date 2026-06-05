-- 餐饮供应商送货单验收入库过账字段.
-- Existing V20260916_01 created OCR/manual supplier delivery draft tables; this
-- migration adds the posting state needed to create PurchaseReceiveRecord and
-- MaterialBatch during warehouse confirmation.

ALTER TABLE supplier_delivery_notes
    ADD COLUMN IF NOT EXISTS warehouse_id VARCHAR(191),
    ADD COLUMN IF NOT EXISTS posting_status VARCHAR(20) NOT NULL DEFAULT 'UNPOSTED',
    ADD COLUMN IF NOT EXISTS receive_record_id VARCHAR(191),
    ADD COLUMN IF NOT EXISTS posted_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS posted_by BIGINT,
    ADD COLUMN IF NOT EXISTS posting_error TEXT;

ALTER TABLE supplier_delivery_note_lines
    ADD COLUMN IF NOT EXISTS qc_result VARCHAR(50),
    ADD COLUMN IF NOT EXISTS material_batch_id VARCHAR(191),
    ADD COLUMN IF NOT EXISTS remark TEXT;

CREATE INDEX IF NOT EXISTS idx_sdn_posting
    ON supplier_delivery_notes (factory_id, posting_status);

CREATE INDEX IF NOT EXISTS idx_sdn_receive_record
    ON supplier_delivery_notes (factory_id, receive_record_id)
    WHERE receive_record_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_sdnl_material_batch
    ON supplier_delivery_note_lines (material_batch_id)
    WHERE material_batch_id IS NOT NULL;
