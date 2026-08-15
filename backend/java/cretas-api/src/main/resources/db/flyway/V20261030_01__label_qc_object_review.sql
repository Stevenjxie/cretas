-- Label QC: persist the human-final object truth separately from immutable AI screening detail.
ALTER TABLE label_qc_photos
    ADD COLUMN IF NOT EXISTS object_review_detail TEXT,
    ADD COLUMN IF NOT EXISTS object_reviewed_by BIGINT,
    ADD COLUMN IF NOT EXISTS object_reviewed_at TIMESTAMP;

COMMENT ON COLUMN label_qc_photos.object_review_detail IS
    'Versioned human-final tray/white-label/color-label object review JSON; AI screening_detail remains immutable.';
COMMENT ON COLUMN label_qc_photos.object_reviewed_by IS
    'User id that submitted the object-level human truth.';
COMMENT ON COLUMN label_qc_photos.object_reviewed_at IS
    'Time the object-level human truth was submitted.';
