DO $$
BEGIN
    IF to_regclass('public.product_work_processes') IS NOT NULL THEN
        ALTER TABLE product_work_processes
            ADD COLUMN IF NOT EXISTS allow_multiple_upstream_sources BOOLEAN;

        UPDATE product_work_processes pwp
        SET allow_multiple_upstream_sources = TRUE
        WHERE pwp.allow_multiple_upstream_sources IS NULL
          AND pwp.default_cost_category IN ('SEASONING', 'PACKAGING');

        IF to_regclass('public.work_processes') IS NOT NULL THEN
            UPDATE product_work_processes pwp
            SET allow_multiple_upstream_sources = TRUE
            WHERE pwp.allow_multiple_upstream_sources IS NULL
              AND EXISTS (
                SELECT 1
                FROM work_processes wp
                WHERE wp.factory_id = pwp.factory_id
                  AND wp.id = pwp.work_process_id
                  AND (
                    wp.process_name LIKE U&'%\719F\5236%'
                    OR wp.process_name LIKE U&'%\6C14\8C03%'
                    OR wp.process_name LIKE U&'%\6C2E\8C03%'
                  )
              );
        END IF;

        UPDATE product_work_processes
        SET allow_multiple_upstream_sources = FALSE
        WHERE allow_multiple_upstream_sources IS NULL;

        ALTER TABLE product_work_processes
            ALTER COLUMN allow_multiple_upstream_sources SET DEFAULT FALSE,
            ALTER COLUMN allow_multiple_upstream_sources SET NOT NULL;
    END IF;
END $$;
