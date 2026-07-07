DO $$
BEGIN
    IF to_regclass('public.material_batches') IS NULL THEN
        RAISE NOTICE 'V20261027_48 skipped: material_batches not present before Hibernate DDL';
        RETURN;
    END IF;

    ALTER TABLE material_batches
        ADD COLUMN IF NOT EXISTS version BIGINT;

    UPDATE material_batches
       SET version = 0
     WHERE version IS NULL;

    ALTER TABLE material_batches
        ALTER COLUMN version SET DEFAULT 0;

    ALTER TABLE material_batches
        ALTER COLUMN version SET NOT NULL;
END $$;
