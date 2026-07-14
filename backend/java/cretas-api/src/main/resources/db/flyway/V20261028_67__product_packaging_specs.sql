CREATE TABLE product_packaging_specs (
  id VARCHAR(36) PRIMARY KEY,
  factory_id VARCHAR(50) NOT NULL,
  product_type_id VARCHAR(100) NOT NULL,
  name VARCHAR(60) NOT NULL,
  package_unit VARCHAR(20) NOT NULL,
  base_unit VARCHAR(20) NOT NULL,
  conversion_factor NUMERIC(20,8) NOT NULL CHECK (conversion_factor > 0),
  is_default BOOLEAN NOT NULL DEFAULT FALSE,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  sort_order INTEGER NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at TIMESTAMP,
  CHECK (package_unit <> base_unit)
);

CREATE INDEX idx_product_packaging_specs_product
  ON product_packaging_specs(factory_id, product_type_id, sort_order);

CREATE UNIQUE INDEX uq_product_packaging_specs_default
  ON product_packaging_specs(factory_id, product_type_id)
  WHERE deleted_at IS NULL AND is_active = TRUE AND is_default = TRUE;

CREATE UNIQUE INDEX uq_product_packaging_specs_conversion
  ON product_packaging_specs(
    factory_id, product_type_id, package_unit, base_unit, conversion_factor
  )
  WHERE deleted_at IS NULL AND is_active = TRUE;

INSERT INTO product_packaging_specs (
  id, factory_id, product_type_id, name, package_unit, base_unit,
  conversion_factor, is_default, is_active, sort_order, version,
  created_at, updated_at
)
SELECT
  substr(md5(p.factory_id || ':' || p.id || ':default-packaging'), 1, 8) || '-' ||
  substr(md5(p.factory_id || ':' || p.id || ':default-packaging'), 9, 4) || '-' ||
  substr(md5(p.factory_id || ':' || p.id || ':default-packaging'), 13, 4) || '-' ||
  substr(md5(p.factory_id || ':' || p.id || ':default-packaging'), 17, 4) || '-' ||
  substr(md5(p.factory_id || ':' || p.id || ':default-packaging'), 21, 12),
  p.factory_id, p.id, '默认箱规', p.level1_unit, p.unit,
  p.box_conversion_coefficient, TRUE, TRUE, 0, 0,
  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM product_types p
WHERE p.deleted_at IS NULL
  AND p.level1_unit IS NOT NULL AND btrim(p.level1_unit) <> ''
  AND p.unit IS NOT NULL AND btrim(p.unit) <> ''
  AND btrim(p.level1_unit) <> btrim(p.unit)
  AND p.box_conversion_coefficient IS NOT NULL
  AND p.box_conversion_coefficient > 0;

ALTER TABLE sales_order_items
  ADD COLUMN packaging_spec_id VARCHAR(36),
  ADD COLUMN packaging_spec_name VARCHAR(60),
  ADD COLUMN packaging_unit VARCHAR(20),
  ADD COLUMN packaging_base_unit VARCHAR(20),
  ADD COLUMN packaging_factor NUMERIC(20,8);

ALTER TABLE sales_delivery_items
  ADD COLUMN packaging_spec_id VARCHAR(36),
  ADD COLUMN packaging_spec_name VARCHAR(60),
  ADD COLUMN packaging_unit VARCHAR(20),
  ADD COLUMN packaging_base_unit VARCHAR(20),
  ADD COLUMN packaging_factor NUMERIC(20,8);

ALTER TABLE finished_goods_batches
  ADD COLUMN packaging_spec_id VARCHAR(36),
  ADD COLUMN packaging_spec_name VARCHAR(60),
  ADD COLUMN packaging_unit VARCHAR(20),
  ADD COLUMN packaging_base_unit VARCHAR(20),
  ADD COLUMN packaging_factor NUMERIC(20,8);
