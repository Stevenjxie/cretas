-- Fresh databases create the legacy bom_items compatibility table from the
-- lightweight bootstrap migration, which predates standard_quantity. Existing
-- long-lived databases already have this column, so this migration is a no-op.
-- It must sort before V20261028_69, which relaxes the column's nullability.
ALTER TABLE bom_items
    ADD COLUMN IF NOT EXISTS standard_quantity DECIMAL(15, 4);
