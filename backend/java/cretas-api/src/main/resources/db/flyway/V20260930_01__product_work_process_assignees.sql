-- T121: multiple responsible workers per product process.
-- product_work_processes is entity-only in fresh CI DBs: Flyway runs before Hibernate DDL.
DO $$
BEGIN
    IF to_regclass('public.product_work_processes') IS NULL THEN
        RAISE NOTICE 'V20260930_01 skipped: product_work_processes not present before Hibernate DDL';
        RETURN;
    END IF;

    CREATE TABLE IF NOT EXISTS product_work_process_assignees (
        id                      BIGSERIAL PRIMARY KEY,
        product_work_process_id BIGINT NOT NULL REFERENCES product_work_processes(id) ON DELETE CASCADE,
        worker_id               BIGINT NOT NULL,
        created_at              TIMESTAMP NOT NULL DEFAULT NOW(),
        updated_at              TIMESTAMP NOT NULL DEFAULT NOW(),
        deleted_at              TIMESTAMP NULL
    );

    CREATE UNIQUE INDEX IF NOT EXISTS uq_pwp_assignee_active
        ON product_work_process_assignees (product_work_process_id, worker_id)
        WHERE deleted_at IS NULL;

    CREATE INDEX IF NOT EXISTS idx_pwp_assignees_pwp
        ON product_work_process_assignees (product_work_process_id)
        WHERE deleted_at IS NULL;

    EXECUTE '
        CREATE OR REPLACE FUNCTION update_pwp_assignees_updated_at()
        RETURNS TRIGGER AS $fn$
        BEGIN
            NEW.updated_at = NOW();
            RETURN NEW;
        END;
        $fn$ LANGUAGE plpgsql
    ';

    DROP TRIGGER IF EXISTS trg_pwp_assignees_updated_at ON product_work_process_assignees;
    CREATE TRIGGER trg_pwp_assignees_updated_at
        BEFORE UPDATE ON product_work_process_assignees
        FOR EACH ROW EXECUTE FUNCTION update_pwp_assignees_updated_at();

    INSERT INTO product_work_process_assignees (product_work_process_id, worker_id)
    SELECT id, responsible_worker_id
    FROM product_work_processes
    WHERE responsible_worker_id IS NOT NULL
    ON CONFLICT DO NOTHING;
END $$;
