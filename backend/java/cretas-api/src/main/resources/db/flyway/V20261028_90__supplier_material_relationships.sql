CREATE TABLE IF NOT EXISTS supplier_materials (
    id VARCHAR(64) PRIMARY KEY,
    factory_id VARCHAR(64) NOT NULL,
    supplier_id VARCHAR(191) NOT NULL,
    material_type_id VARCHAR(191) NOT NULL,
    supplier_material_code VARCHAR(100),
    default_purchase_price NUMERIC(18,6),
    currency VARCHAR(8) NOT NULL DEFAULT 'CNY',
    purchase_unit VARCHAR(20) NOT NULL,
    min_order_quantity NUMERIC(18,6),
    lead_time_days INTEGER,
    preferred BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP,
    CONSTRAINT uk_supplier_material_identity UNIQUE(factory_id, supplier_id, material_type_id),
    CONSTRAINT ck_supplier_material_price CHECK(default_purchase_price IS NULL OR default_purchase_price >= 0),
    CONSTRAINT ck_supplier_material_moq CHECK(min_order_quantity IS NULL OR min_order_quantity > 0),
    CONSTRAINT ck_supplier_material_lead CHECK(lead_time_days IS NULL OR lead_time_days >= 0)
);
CREATE INDEX IF NOT EXISTS idx_supplier_material_supplier ON supplier_materials(factory_id, supplier_id, active);
CREATE INDEX IF NOT EXISTS idx_supplier_material_material ON supplier_materials(factory_id, material_type_id, active);
CREATE UNIQUE INDEX IF NOT EXISTS uk_supplier_material_one_preferred
    ON supplier_materials(factory_id, material_type_id)
    WHERE active = TRUE AND preferred = TRUE AND deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS supplier_material_purchase_specs (
    id VARCHAR(64) PRIMARY KEY,
    factory_id VARCHAR(64) NOT NULL,
    supplier_material_id VARCHAR(64) NOT NULL,
    material_type_id VARCHAR(191) NOT NULL,
    name VARCHAR(100) NOT NULL,
    purchase_package_unit VARCHAR(20) NOT NULL,
    inventory_base_unit VARCHAR(20) NOT NULL,
    conversion_factor NUMERIC(24,12) NOT NULL,
    quoted_price NUMERIC(18,6),
    currency VARCHAR(8) NOT NULL DEFAULT 'CNY',
    min_order_quantity NUMERIC(18,6),
    lead_time_days INTEGER,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP,
    CONSTRAINT uk_supplier_material_purchase_spec_name UNIQUE(factory_id, supplier_material_id, name),
    CONSTRAINT ck_purchase_spec_factor CHECK(conversion_factor > 0),
    CONSTRAINT ck_purchase_spec_price CHECK(quoted_price IS NULL OR quoted_price >= 0),
    CONSTRAINT ck_purchase_spec_moq CHECK(min_order_quantity IS NULL OR min_order_quantity > 0),
    CONSTRAINT ck_purchase_spec_lead CHECK(lead_time_days IS NULL OR lead_time_days >= 0)
);
CREATE INDEX IF NOT EXISTS idx_supplier_material_purchase_spec
    ON supplier_material_purchase_specs(factory_id, supplier_material_id, active);
CREATE UNIQUE INDEX IF NOT EXISTS uk_supplier_material_one_default_spec
    ON supplier_material_purchase_specs(factory_id, supplier_material_id)
    WHERE active = TRUE AND is_default = TRUE AND deleted_at IS NULL;

ALTER TABLE purchase_order_items ADD COLUMN IF NOT EXISTS supplier_material_id VARCHAR(64);
ALTER TABLE purchase_order_items ADD COLUMN IF NOT EXISTS purchase_packaging_spec_id VARCHAR(64);
ALTER TABLE purchase_order_items ADD COLUMN IF NOT EXISTS purchase_package_unit_snapshot VARCHAR(20);
ALTER TABLE purchase_order_items ADD COLUMN IF NOT EXISTS inventory_base_unit_snapshot VARCHAR(20);
ALTER TABLE purchase_order_items ADD COLUMN IF NOT EXISTS package_to_base_factor_snapshot NUMERIC(24,12);
ALTER TABLE purchase_order_items ADD COLUMN IF NOT EXISTS inventory_quantity_snapshot NUMERIC(24,12);
ALTER TABLE purchase_order_items ADD COLUMN IF NOT EXISTS untaxed_amount_snapshot NUMERIC(18,2);
ALTER TABLE purchase_order_items ADD COLUMN IF NOT EXISTS tax_amount_snapshot NUMERIC(18,2);
ALTER TABLE purchase_order_items ADD COLUMN IF NOT EXISTS tax_inclusive_amount_snapshot NUMERIC(18,2);
