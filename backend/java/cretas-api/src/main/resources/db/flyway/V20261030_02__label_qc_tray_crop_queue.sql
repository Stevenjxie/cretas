CREATE TABLE IF NOT EXISTS label_qc_tray_crops (
    id VARCHAR(36) PRIMARY KEY,
    row_version BIGINT NOT NULL DEFAULT 0,
    factory_id VARCHAR(50) NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    photo_id VARCHAR(36) NOT NULL,
    source_attachment_id VARCHAR(191) NOT NULL,
    source_image_sha256 VARCHAR(64),
    tray_index INTEGER NOT NULL,
    ai_tray_key VARCHAR(100),
    source_decision VARCHAR(20) NOT NULL,
    tray_x_min DOUBLE PRECISION NOT NULL,
    tray_y_min DOUBLE PRECISION NOT NULL,
    tray_x_max DOUBLE PRECISION NOT NULL,
    tray_y_max DOUBLE PRECISION NOT NULL,
    crop_x_min DOUBLE PRECISION NOT NULL,
    crop_y_min DOUBLE PRECISION NOT NULL,
    crop_x_max DOUBLE PRECISION NOT NULL,
    crop_y_max DOUBLE PRECISION NOT NULL,
    padding_ratio DOUBLE PRECISION NOT NULL,
    crop_algorithm_version VARCHAR(40) NOT NULL,
    object_review_sha256 VARCHAR(64) NOT NULL,
    crop_spec_sha256 VARCHAR(64) NOT NULL,
    coordinate_transform TEXT NOT NULL,
    factory_label_proposals TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    platform_review_detail TEXT,
    reviewed_by BIGINT,
    reviewed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    CONSTRAINT uq_label_qc_tray_crop_spec UNIQUE (factory_id, crop_spec_sha256),
    CONSTRAINT chk_label_qc_tray_crop_status CHECK (status IN ('PENDING', 'REVIEWED', 'UNJUDGEABLE')),
    CONSTRAINT chk_label_qc_tray_crop_decision CHECK (source_decision IN ('CONFIRMED', 'CORRECTED', 'ADDED')),
    CONSTRAINT chk_label_qc_tray_crop_bounds CHECK (
        tray_x_min >= 0 AND tray_y_min >= 0 AND tray_x_max <= 1 AND tray_y_max <= 1
        AND tray_x_min < tray_x_max AND tray_y_min < tray_y_max
        AND crop_x_min >= 0 AND crop_y_min >= 0 AND crop_x_max <= 1 AND crop_y_max <= 1
        AND crop_x_min < crop_x_max AND crop_y_min < crop_y_max)
);

CREATE INDEX IF NOT EXISTS idx_label_qc_tray_crop_queue
    ON label_qc_tray_crops (factory_id, status, created_at);
CREATE INDEX IF NOT EXISTS idx_label_qc_tray_crop_photo
    ON label_qc_tray_crops (factory_id, photo_id, tray_index);

COMMENT ON TABLE label_qc_tray_crops IS
    'Deterministic virtual single-tray crops generated only from human-final full-photo tray reviews.';
COMMENT ON COLUMN label_qc_tray_crops.crop_spec_sha256 IS
    'Idempotency lineage hash over source photo, stable tray identity, review hash, crop bounds and algorithm version.';
