-- Additive dual-code contract for material master data.
-- Existing raw_material_types.code stays unchanged as the 16-digit legacy classification code.
-- No historical backfill is performed by this migration.

ALTER TABLE raw_material_types
    ADD COLUMN IF NOT EXISTS business_code VARCHAR(14);

ALTER TABLE raw_material_types
    ADD CONSTRAINT chk_raw_material_business_code_ascii
        CHECK (business_code IS NULL OR business_code ~ '^[A-Z0-9]+$');

CREATE UNIQUE INDEX IF NOT EXISTS uk_raw_material_factory_business_code
    ON raw_material_types(factory_id, business_code)
    WHERE business_code IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_raw_material_business_code
    ON raw_material_types(factory_id, business_code);

CREATE TABLE material_business_code_prefixes (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(50) NOT NULL,
    classification_segment_code VARCHAR(10) NOT NULL,
    code_prefix VARCHAR(8) NOT NULL,
    sequence_length SMALLINT NOT NULL DEFAULT 6,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    CONSTRAINT uk_mbc_prefix_factory_segment
        UNIQUE (factory_id, classification_segment_code),
    CONSTRAINT uk_mbc_prefix_factory_code
        UNIQUE (factory_id, code_prefix),
    CONSTRAINT chk_mbc_prefix_segment
        CHECK (classification_segment_code ~ '^([0-9]{3}|[0-9]{6}|[0-9]{10})$'),
    CONSTRAINT chk_mbc_prefix_ascii
        CHECK (code_prefix ~ '^[A-Z0-9]{2,8}$'),
    CONSTRAINT chk_mbc_prefix_sequence_length
        CHECK (sequence_length = 6),
    CONSTRAINT chk_mbc_prefix_total_length
        CHECK (char_length(code_prefix) + sequence_length <= 14),
    CONSTRAINT fk_mbc_prefix_classification_segment
        FOREIGN KEY (factory_id, classification_segment_code)
        REFERENCES material_code_segments(factory_id, segment_code)
);

CREATE INDEX idx_mbc_prefix_factory_active
    ON material_business_code_prefixes(factory_id, is_active)
    WHERE deleted_at IS NULL;

CREATE TABLE material_business_code_counters (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(50) NOT NULL,
    code_prefix VARCHAR(8) NOT NULL,
    last_allocated BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    CONSTRAINT uk_mbc_counter_factory_prefix UNIQUE (factory_id, code_prefix),
    CONSTRAINT chk_mbc_counter_range CHECK (last_allocated BETWEEN 0 AND 999999),
    CONSTRAINT fk_mbc_counter_prefix
        FOREIGN KEY (factory_id, code_prefix)
        REFERENCES material_business_code_prefixes(factory_id, code_prefix)
);

COMMENT ON COLUMN raw_material_types.business_code IS
    'Human-readable immutable material code: uppercase A-Z and digits only; legacy code remains in code';
COMMENT ON TABLE material_business_code_prefixes IS
    'Backend-controlled classification-to-business-code prefix configuration';
COMMENT ON TABLE material_business_code_counters IS
    'Factory and prefix scoped monotonic sequence state; historical values must never be reset or reused';
