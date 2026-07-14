CREATE TABLE product_unit_conversions (
  id VARCHAR(36) PRIMARY KEY,
  factory_id VARCHAR(50) NOT NULL,
  product_type_id VARCHAR(100) NOT NULL,
  from_unit_code VARCHAR(20) NOT NULL,
  to_unit_code VARCHAR(20) NOT NULL,
  factor NUMERIC(20,8) NOT NULL CHECK (factor > 0),
  source_type VARCHAR(20) NOT NULL
    CHECK (source_type IN ('NET_CONTENT','PACKAGING','MANUAL')),
  is_primary_sales_conversion BOOLEAN NOT NULL DEFAULT FALSE,
  effective_from TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_to TIMESTAMP,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at TIMESTAMP,
  CHECK (from_unit_code <> to_unit_code),
  CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE UNIQUE INDEX uq_puc_active_direction
  ON product_unit_conversions(factory_id, product_type_id, from_unit_code, to_unit_code)
  WHERE deleted_at IS NULL AND effective_to IS NULL;

ALTER TABLE unit_of_measurements
  ADD COLUMN aliases_json TEXT;
