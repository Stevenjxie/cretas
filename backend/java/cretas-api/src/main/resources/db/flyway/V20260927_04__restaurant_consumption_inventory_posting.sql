-- Restaurant requisition/wastage/stocktaking inventory posting markers.
-- These columns make MaterialBatch side effects idempotent and auditable.
-- Guard: entity-only tables (Hibernate DDL). Fresh CI DB runs Flyway before Hibernate.

DO $$
BEGIN
    IF to_regclass('public.material_requisitions') IS NULL THEN
        RAISE NOTICE 'V20260927_04 skipped: material_requisitions not yet created (fresh-DB Flyway-before-Hibernate)';
        RETURN;
    END IF;

    ALTER TABLE material_requisitions
        ADD COLUMN IF NOT EXISTS inventory_posted_at TIMESTAMP,
        ADD COLUMN IF NOT EXISTS inventory_posted_by BIGINT,
        ADD COLUMN IF NOT EXISTS inventory_posting_detail TEXT,
        ADD COLUMN IF NOT EXISTS inventory_posting_error TEXT;

    ALTER TABLE wastage_records
        ADD COLUMN IF NOT EXISTS inventory_posted_at TIMESTAMP,
        ADD COLUMN IF NOT EXISTS inventory_posted_by BIGINT,
        ADD COLUMN IF NOT EXISTS inventory_posting_detail TEXT,
        ADD COLUMN IF NOT EXISTS inventory_posting_error TEXT;

    ALTER TABLE stocktaking_records
        ADD COLUMN IF NOT EXISTS inventory_posted_at TIMESTAMP,
        ADD COLUMN IF NOT EXISTS inventory_posted_by BIGINT,
        ADD COLUMN IF NOT EXISTS inventory_posting_detail TEXT,
        ADD COLUMN IF NOT EXISTS inventory_posting_error TEXT;

    CREATE INDEX IF NOT EXISTS idx_material_requisitions_inventory_posted
        ON material_requisitions (factory_id, inventory_posted_at)
        WHERE inventory_posted_at IS NOT NULL;

    CREATE INDEX IF NOT EXISTS idx_wastage_records_inventory_posted
        ON wastage_records (factory_id, inventory_posted_at)
        WHERE inventory_posted_at IS NOT NULL;

    CREATE INDEX IF NOT EXISTS idx_stocktaking_records_inventory_posted
        ON stocktaking_records (factory_id, inventory_posted_at)
        WHERE inventory_posted_at IS NOT NULL;
END $$;
