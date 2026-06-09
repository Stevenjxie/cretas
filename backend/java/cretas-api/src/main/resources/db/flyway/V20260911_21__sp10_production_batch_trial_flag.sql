-- SP10: ProductionBatch 扩字段 — is_trial / trial_sample_id
ALTER TABLE production_batches
    ADD COLUMN IF NOT EXISTS is_trial        BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS trial_sample_id VARCHAR(191) NULL;

COMMENT ON COLUMN production_batches.is_trial IS '是否为研发试制批次';
COMMENT ON COLUMN production_batches.trial_sample_id IS 'FK → product_samples.id, 试制批次关联样品';

CREATE INDEX IF NOT EXISTS idx_pb_trial_sample
    ON production_batches (factory_id, is_trial, trial_sample_id)
    WHERE is_trial = TRUE;
