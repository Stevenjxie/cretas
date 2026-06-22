-- SP-A 配方 BOM: product_recipes(配方头) + recipe_ingredients(明细)
-- Spec 2026-06-22 §3.1. 1 SKU 1 条 ACTIVE 配方; 锅序规则(subsequent_pot_ratio)在配方头.

CREATE TABLE IF NOT EXISTS product_recipes (
    id VARCHAR(64) NOT NULL,
    factory_id VARCHAR(64) NOT NULL,
    product_type_id VARCHAR(64) NOT NULL,
    name VARCHAR(200) NOT NULL,
    injection_rate DECIMAL(8,4),
    cooking_pot_base_kg DECIMAL(12,3),
    subsequent_pot_ratio DECIMAL(8,4) NOT NULL DEFAULT 0.3333,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    deleted_at TIMESTAMP,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_precipe_factory_product
    ON product_recipes (factory_id, product_type_id);

CREATE TABLE IF NOT EXISTS recipe_ingredients (
    id VARCHAR(64) NOT NULL,
    recipe_id VARCHAR(64) NOT NULL,
    factory_id VARCHAR(64) NOT NULL,
    section VARCHAR(20) NOT NULL,           -- INJECTION | COOKING
    seq INT NOT NULL DEFAULT 0,
    name VARCHAR(200) NOT NULL,
    dosage_per_kg_g DECIMAL(14,4) NOT NULL, -- 每kg原料用量(g)
    price_source1 DECIMAL(14,4),
    price_source2 DECIMAL(14,4),
    count_in_seasoning BOOLEAN NOT NULL DEFAULT TRUE,  -- 老汤=false
    remark VARCHAR(500),
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    deleted_at TIMESTAMP,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_ringredient_recipe
    ON recipe_ingredients (recipe_id);
