ALTER TABLE material_batches
    ADD COLUMN IF NOT EXISTS source_event_key VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uq_material_batch_source_event
    ON material_batches(factory_id, source_doc_type, source_event_key)
    WHERE source_event_key IS NOT NULL;
