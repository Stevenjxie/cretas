-- Procurement evidence chain for restaurant supplier delivery notes (mobile workflow).

ALTER TABLE supplier_delivery_notes
    ADD COLUMN IF NOT EXISTS source_requisition_id VARCHAR(191),
    ADD COLUMN IF NOT EXISTS procurement_confirmed_by BIGINT,
    ADD COLUMN IF NOT EXISTS procurement_confirmed_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS supplier_contact_note TEXT,
    ADD COLUMN IF NOT EXISTS voice_audio_url VARCHAR(500),
    ADD COLUMN IF NOT EXISTS voice_transcript_text TEXT,
    ADD COLUMN IF NOT EXISTS supplier_quote_photo_urls TEXT,
    ADD COLUMN IF NOT EXISTS expected_delivery_date DATE;

CREATE INDEX IF NOT EXISTS idx_sdn_source_requisition
    ON supplier_delivery_notes (factory_id, source_requisition_id)
    WHERE source_requisition_id IS NOT NULL;
