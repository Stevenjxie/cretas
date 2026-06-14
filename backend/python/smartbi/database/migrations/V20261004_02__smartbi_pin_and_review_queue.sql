CREATE TABLE IF NOT EXISTS smart_bi_pin_mappings (
    id            SERIAL PRIMARY KEY,
    factory_id    VARCHAR(50)  NOT NULL,
    template_key  VARCHAR(16)  NOT NULL,        -- hash(sorted(columns))[:16]
    column_name   VARCHAR(255) NOT NULL,        -- normalized original column name(lower/strip)
    standard_name VARCHAR(255) NOT NULL,        -- canonical field
    confidence    FLOAT        NOT NULL DEFAULT 1.0,
    method        VARCHAR(32)  NOT NULL DEFAULT 'user_pinned',  -- user_pinned/auto_promoted
    pinned_by     VARCHAR(100),
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    UNIQUE (factory_id, template_key, column_name)
);

CREATE TABLE IF NOT EXISTS smart_bi_mapping_review_queue (
    id                  SERIAL PRIMARY KEY,
    upload_id           INT          NOT NULL,
    factory_id          VARCHAR(50)  NOT NULL,
    template_key        VARCHAR(16)  NOT NULL,
    column_name         VARCHAR(255) NOT NULL,
    detected_standard   VARCHAR(255),
    detected_confidence FLOAT        NOT NULL DEFAULT 0.0,
    detected_method     VARCHAR(32),
    user_confirmed_standard VARCHAR(255),
    review_status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING',  -- PENDING/CONFIRMED/REJECTED
    reviewed_by         VARCHAR(100),
    reviewed_at         TIMESTAMP,
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    UNIQUE (upload_id, column_name)
);

CREATE INDEX IF NOT EXISTS idx_pin_factory_template
    ON smart_bi_pin_mappings (factory_id, template_key);

CREATE INDEX IF NOT EXISTS idx_review_queue_factory_pending
    ON smart_bi_mapping_review_queue (factory_id, review_status);
