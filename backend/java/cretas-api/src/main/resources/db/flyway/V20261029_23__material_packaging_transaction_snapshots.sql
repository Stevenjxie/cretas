ALTER TABLE purchase_order_items
  ADD COLUMN IF NOT EXISTS material_packaging_spec_id VARCHAR(36);

ALTER TABLE purchase_receive_items
  ADD COLUMN IF NOT EXISTS material_packaging_spec_id VARCHAR(36),
  ADD COLUMN IF NOT EXISTS receive_package_unit_snapshot VARCHAR(20),
  ADD COLUMN IF NOT EXISTS inventory_base_unit_snapshot VARCHAR(20),
  ADD COLUMN IF NOT EXISTS package_to_base_factor_snapshot NUMERIC(24,12),
  ADD COLUMN IF NOT EXISTS inventory_quantity_snapshot NUMERIC(24,12);

ALTER TABLE internal_transfer_items
  ADD COLUMN IF NOT EXISTS material_packaging_spec_id VARCHAR(36),
  ADD COLUMN IF NOT EXISTS package_quantity_snapshot NUMERIC(24,12),
  ADD COLUMN IF NOT EXISTS package_unit_snapshot VARCHAR(20),
  ADD COLUMN IF NOT EXISTS inventory_base_unit_snapshot VARCHAR(20),
  ADD COLUMN IF NOT EXISTS package_to_base_factor_snapshot NUMERIC(24,12);

CREATE INDEX IF NOT EXISTS idx_purchase_order_item_material_packaging
  ON purchase_order_items(material_packaging_spec_id);

CREATE INDEX IF NOT EXISTS idx_purchase_receive_item_material_packaging
  ON purchase_receive_items(material_packaging_spec_id);

CREATE INDEX IF NOT EXISTS idx_transfer_item_material_packaging
  ON internal_transfer_items(material_packaging_spec_id);
