-- Cross-unit production (for example kg -> box) can produce a percentage
-- greater than 999.99. Keep the value as a percentage, but allow it to be
-- persisted without a numeric overflow.
DO $$
BEGIN
    IF to_regclass('public.production_batches') IS NOT NULL THEN
        ALTER TABLE production_batches
            ALTER COLUMN yield_rate TYPE NUMERIC(12, 2);
    END IF;
END $$;
