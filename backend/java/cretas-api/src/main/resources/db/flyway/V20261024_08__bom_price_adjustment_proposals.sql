CREATE TABLE IF NOT EXISTS bom_price_adjustment_proposals (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(50) NOT NULL,
    recipe_id VARCHAR(191) NOT NULL,
    recipe_item_id BIGINT NOT NULL,
    recipe_code VARCHAR(50),
    product_type_id VARCHAR(100),
    product_name VARCHAR(200),
    material_type_id VARCHAR(191) NOT NULL,
    material_name VARCHAR(200),
    current_unit_price NUMERIC(15, 4) NOT NULL,
    proposed_unit_price NUMERIC(15, 4) NOT NULL,
    delta_amount NUMERIC(15, 4) NOT NULL,
    delta_percent NUMERIC(10, 2),
    affected_product_count INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_receive_record_id VARCHAR(191),
    source_receive_item_id BIGINT,
    approved_by BIGINT,
    approved_at TIMESTAMP,
    approval_comment TEXT,
    applied_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_bpap_factory_status
    ON bom_price_adjustment_proposals(factory_id, status);
CREATE INDEX IF NOT EXISTS idx_bpap_material
    ON bom_price_adjustment_proposals(factory_id, material_type_id);
CREATE INDEX IF NOT EXISTS idx_bpap_recipe_item
    ON bom_price_adjustment_proposals(recipe_item_id);

CREATE TABLE IF NOT EXISTS bom_price_adjustment_audits (
    id BIGSERIAL PRIMARY KEY,
    proposal_id BIGINT NOT NULL,
    factory_id VARCHAR(50) NOT NULL,
    recipe_id VARCHAR(191),
    recipe_item_id BIGINT NOT NULL,
    material_type_id VARCHAR(191),
    before_unit_price NUMERIC(15, 4),
    after_unit_price NUMERIC(15, 4),
    approved_by BIGINT NOT NULL,
    approved_at TIMESTAMP NOT NULL,
    approval_comment TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_bpaa_proposal
    ON bom_price_adjustment_audits(proposal_id);
CREATE INDEX IF NOT EXISTS idx_bpaa_recipe_item
    ON bom_price_adjustment_audits(recipe_item_id);
