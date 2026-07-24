CREATE TABLE label_qc_tasks (
    id VARCHAR(36) PRIMARY KEY,
    factory_id VARCHAR(50) NOT NULL,
    product_type_id VARCHAR(100) NOT NULL,
    sku_code VARCHAR(50) NOT NULL,
    sku_name VARCHAR(200) NOT NULL,
    batch_number VARCHAR(100) NOT NULL,
    production_date DATE NOT NULL,
    created_by BIGINT NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    status VARCHAR(30) NOT NULL,
    photo_count INTEGER NOT NULL DEFAULT 0,
    ai_candidate_count INTEGER NOT NULL DEFAULT 0,
    final_defect_count INTEGER NOT NULL DEFAULT 0,
    reviewed_by BIGINT,
    reviewed_at TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    CONSTRAINT uq_label_qc_task_idempotency UNIQUE (factory_id, created_by, idempotency_key),
    CONSTRAINT chk_label_qc_task_status CHECK (
        status IN ('DRAFT','UPLOADING','QUEUED','ANALYZING','NEEDS_REVIEW','REVIEWED','ANALYSIS_FAILED')
    )
);

CREATE INDEX idx_label_qc_task_factory_status
    ON label_qc_tasks(factory_id, status, created_at);
CREATE INDEX idx_label_qc_task_reviewed
    ON label_qc_tasks(factory_id, reviewed_at);

CREATE TABLE label_qc_photos (
    id VARCHAR(36) PRIMARY KEY,
    factory_id VARCHAR(50) NOT NULL,
    task_id VARCHAR(36) NOT NULL REFERENCES label_qc_tasks(id),
    attachment_id VARCHAR(191) NOT NULL REFERENCES attachments(id),
    order_index INTEGER NOT NULL,
    image_width INTEGER NOT NULL,
    image_height INTEGER NOT NULL,
    status VARCHAR(30) NOT NULL,
    ai_model VARCHAR(300),
    prompt_version VARCHAR(100),
    analysis_error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    CONSTRAINT uq_label_qc_photo_attachment UNIQUE (task_id, attachment_id),
    CONSTRAINT uq_label_qc_photo_order UNIQUE (task_id, order_index),
    CONSTRAINT chk_label_qc_photo_size CHECK (image_width > 0 AND image_height > 0),
    CONSTRAINT chk_label_qc_photo_status CHECK (
        status IN ('UPLOADED','QUEUED','ANALYZING','ANALYZED','ANALYSIS_FAILED','REVIEWED')
    )
);

CREATE INDEX idx_label_qc_photo_task
    ON label_qc_photos(factory_id, task_id, order_index);

CREATE TABLE label_qc_annotations (
    id VARCHAR(36) PRIMARY KEY,
    factory_id VARCHAR(50) NOT NULL,
    task_id VARCHAR(36) NOT NULL REFERENCES label_qc_tasks(id),
    photo_id VARCHAR(36) NOT NULL REFERENCES label_qc_photos(id),
    source VARCHAR(10) NOT NULL,
    ai_candidate_id VARCHAR(100),
    ai_label VARCHAR(40),
    ai_confidence DOUBLE PRECISION,
    ai_evidence VARCHAR(500),
    human_label VARCHAR(40),
    x_min DOUBLE PRECISION,
    y_min DOUBLE PRECISION,
    x_max DOUBLE PRECISION,
    y_max DOUBLE PRECISION,
    reviewer_notes VARCHAR(500),
    reviewed_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    CONSTRAINT chk_label_qc_annotation_source CHECK (source IN ('AI','HUMAN')),
    CONSTRAINT chk_label_qc_annotation_ai_label CHECK (
        ai_label IS NULL OR ai_label IN ('MISSING_WHITE_LABEL','MISSING_COLOR_LABEL','NO_DEFECT','UNJUDGEABLE')
    ),
    CONSTRAINT chk_label_qc_annotation_human_label CHECK (
        human_label IS NULL OR human_label IN ('MISSING_WHITE_LABEL','MISSING_COLOR_LABEL','NO_DEFECT','UNJUDGEABLE')
    ),
    CONSTRAINT chk_label_qc_annotation_confidence CHECK (
        ai_confidence IS NULL OR (ai_confidence >= 0 AND ai_confidence <= 1)
    ),
    CONSTRAINT chk_label_qc_annotation_bbox CHECK (
        (x_min IS NULL AND y_min IS NULL AND x_max IS NULL AND y_max IS NULL)
        OR (
            x_min >= 0 AND y_min >= 0 AND x_max <= 1 AND y_max <= 1
            AND x_min < x_max AND y_min < y_max
        )
    )
);

CREATE INDEX idx_label_qc_annotation_photo
    ON label_qc_annotations(factory_id, photo_id);
CREATE INDEX idx_label_qc_annotation_task
    ON label_qc_annotations(factory_id, task_id);
