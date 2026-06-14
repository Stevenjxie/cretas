-- SP1: WorkProcess semi-finished output SKU code.
-- work_processes is entity-only in fresh CI DBs: Flyway runs before Hibernate DDL.
DO $$
BEGIN
    IF to_regclass('public.work_processes') IS NULL THEN
        RAISE NOTICE 'V20261010_03 skipped: work_processes not present before Hibernate DDL';
        RETURN;
    END IF;

    ALTER TABLE work_processes
        ADD COLUMN IF NOT EXISTS semi_finished_output_code VARCHAR(50) DEFAULT NULL;

    COMMENT ON COLUMN work_processes.semi_finished_output_code IS
        'SP1: semi-finished output SKU code for output-options; null means finished-product only.';
END $$;
