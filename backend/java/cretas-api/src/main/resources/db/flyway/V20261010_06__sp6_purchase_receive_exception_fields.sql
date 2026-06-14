-- SP6 purchase receive exception quick status fields.
-- purchase_receive_records is entity-owned in fresh CI DBs: Flyway runs before Hibernate DDL.
DO $$
BEGIN
    IF to_regclass('public.purchase_receive_records') IS NULL THEN
        RAISE NOTICE 'V20261010_06 skipped: purchase_receive_records not present before Hibernate DDL';
        RETURN;
    END IF;

    ALTER TABLE purchase_receive_records
        ADD COLUMN IF NOT EXISTS has_exception   BOOLEAN NOT NULL DEFAULT FALSE,
        ADD COLUMN IF NOT EXISTS exception_count INTEGER NOT NULL DEFAULT 0;

    COMMENT ON COLUMN purchase_receive_records.has_exception IS
        'SP6: whether the receive record has over/short receive exceptions for fast filtering.';
    COMMENT ON COLUMN purchase_receive_records.exception_count IS
        'SP6: number of linked purchase exception records.';
END $$;
