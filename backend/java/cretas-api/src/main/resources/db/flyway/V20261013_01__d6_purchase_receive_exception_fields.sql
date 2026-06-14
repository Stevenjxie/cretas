-- SP6 D-6 purchase receive exception decision fields.
-- purchase_receive_records is entity-owned in fresh CI DBs: Flyway runs before Hibernate DDL.
DO $$
BEGIN
    IF to_regclass('public.purchase_receive_records') IS NULL THEN
        RAISE NOTICE 'V20261013_01 skipped: purchase_receive_records not present before Hibernate DDL';
        RETURN;
    END IF;

    ALTER TABLE purchase_receive_records
        ADD COLUMN IF NOT EXISTS exception_type    VARCHAR(32),
        ADD COLUMN IF NOT EXISTS exception_qty     NUMERIC(15, 4),
        ADD COLUMN IF NOT EXISTS decision_status   VARCHAR(32);

    COMMENT ON COLUMN purchase_receive_records.exception_type IS
        'SP6 D-6: receive exception type, such as OVER_RECEIVE or SHORT_RECEIVE.';
    COMMENT ON COLUMN purchase_receive_records.exception_qty IS
        'SP6 D-6: exception quantity.';
    COMMENT ON COLUMN purchase_receive_records.decision_status IS
        'SP6 D-6: exception decision status.';
END $$;
