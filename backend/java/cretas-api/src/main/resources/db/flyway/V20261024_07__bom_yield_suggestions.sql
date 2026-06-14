CREATE TABLE IF NOT EXISTS bom_yield_suggestions (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(50) NOT NULL,
    product_type_id VARCHAR(100) NOT NULL,
    product_name VARCHAR(100),
    bom_item_id BIGINT,
    previous_yield_rate NUMERIC(6, 2),
    suggested_yield_rate NUMERIC(6, 2) NOT NULL,
    sample_count INTEGER NOT NULL,
    excluded_sample_count INTEGER NOT NULL DEFAULT 0,
    guard_max_offset_percent NUMERIC(6, 2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    source_event_type VARCHAR(64) NOT NULL,
    source_event_id VARCHAR(191) NOT NULL,
    generated_by VARCHAR(64) NOT NULL,
    generated_at TIMESTAMP NOT NULL,
    applied_by BIGINT,
    applied_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP,
    CONSTRAINT ck_bom_yield_suggestions_status
        CHECK (status IN ('PENDING', 'APPLIED', 'REJECTED')),
    CONSTRAINT uk_bom_yield_suggestion_source
        UNIQUE (factory_id, product_type_id, source_event_type, source_event_id)
);

CREATE INDEX IF NOT EXISTS idx_bom_yield_suggestion_factory_status
    ON bom_yield_suggestions(factory_id, status);

CREATE INDEX IF NOT EXISTS idx_bom_yield_suggestion_product
    ON bom_yield_suggestions(factory_id, product_type_id);
