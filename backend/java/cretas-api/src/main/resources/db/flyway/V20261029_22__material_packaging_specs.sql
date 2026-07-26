CREATE TABLE material_packaging_specs (
  id VARCHAR(36) PRIMARY KEY,
  factory_id VARCHAR(50) NOT NULL,
  material_type_id VARCHAR(191) NOT NULL,
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

CREATE INDEX idx_material_packaging_specs_material
  ON material_packaging_specs(factory_id, material_type_id, sort_order);

CREATE UNIQUE INDEX uq_material_packaging_specs_default
  ON material_packaging_specs(factory_id, material_type_id)
  WHERE deleted_at IS NULL AND is_active = TRUE AND is_default = TRUE;

CREATE UNIQUE INDEX uq_material_packaging_specs_unit
  ON material_packaging_specs(factory_id, material_type_id, package_unit)
  WHERE deleted_at IS NULL AND is_active = TRUE;

INSERT INTO material_packaging_specs (
  id, factory_id, material_type_id, name, package_unit, base_unit,
  conversion_factor, is_default, is_active, sort_order, version,
  created_at, updated_at
)
SELECT
  substr(md5(h.factory_id || ':' || h.material_type_id || ':default-material-packaging'), 1, 8) || '-' ||
  substr(md5(h.factory_id || ':' || h.material_type_id || ':default-material-packaging'), 9, 4) || '-' ||
  substr(md5(h.factory_id || ':' || h.material_type_id || ':default-material-packaging'), 13, 4) || '-' ||
  substr(md5(h.factory_id || ':' || h.material_type_id || ':default-material-packaging'), 17, 4) || '-' ||
  substr(md5(h.factory_id || ':' || h.material_type_id || ':default-material-packaging'), 21, 12),
  h.factory_id, h.material_type_id, '默认包装', h.level2_unit, h.level1_unit,
  h.level1_per_level2, TRUE, TRUE, 0, 0,
  COALESCE(h.created_at, CURRENT_TIMESTAMP), COALESCE(h.updated_at, CURRENT_TIMESTAMP)
FROM material_packaging_hierarchy h
WHERE h.deleted_at IS NULL
  AND h.level1_unit IS NOT NULL AND btrim(h.level1_unit) <> ''
  AND h.level2_unit IS NOT NULL AND btrim(h.level2_unit) <> ''
  AND btrim(h.level1_unit) <> btrim(h.level2_unit)
  AND h.level1_per_level2 IS NOT NULL
  AND h.level1_per_level2 > 0;

INSERT INTO material_packaging_specs (
  id, factory_id, material_type_id, name, package_unit, base_unit,
  conversion_factor, is_default, is_active, sort_order, version,
  created_at, updated_at
)
SELECT
  substr(md5(h.factory_id || ':' || h.material_type_id || ':secondary-material-packaging'), 1, 8) || '-' ||
  substr(md5(h.factory_id || ':' || h.material_type_id || ':secondary-material-packaging'), 9, 4) || '-' ||
  substr(md5(h.factory_id || ':' || h.material_type_id || ':secondary-material-packaging'), 13, 4) || '-' ||
  substr(md5(h.factory_id || ':' || h.material_type_id || ':secondary-material-packaging'), 17, 4) || '-' ||
  substr(md5(h.factory_id || ':' || h.material_type_id || ':secondary-material-packaging'), 21, 12),
  h.factory_id, h.material_type_id, '包装规格 2', h.level3_unit, h.level1_unit,
  h.level1_per_level2 * h.level2_per_level3, FALSE, TRUE, 1, 0,
  COALESCE(h.created_at, CURRENT_TIMESTAMP), COALESCE(h.updated_at, CURRENT_TIMESTAMP)
FROM material_packaging_hierarchy h
WHERE h.deleted_at IS NULL
  AND h.level1_unit IS NOT NULL AND btrim(h.level1_unit) <> ''
  AND h.level3_unit IS NOT NULL AND btrim(h.level3_unit) <> ''
  AND btrim(h.level1_unit) <> btrim(h.level3_unit)
  AND h.level1_per_level2 IS NOT NULL AND h.level1_per_level2 > 0
  AND h.level2_per_level3 IS NOT NULL AND h.level2_per_level3 > 0;
