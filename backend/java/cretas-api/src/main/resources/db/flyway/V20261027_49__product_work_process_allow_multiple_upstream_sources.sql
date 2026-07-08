ALTER TABLE product_work_processes
    ADD COLUMN IF NOT EXISTS allow_multiple_upstream_sources BOOLEAN;

UPDATE product_work_processes pwp
SET allow_multiple_upstream_sources = TRUE
WHERE pwp.allow_multiple_upstream_sources IS NULL
  AND (
    pwp.default_cost_category IN ('SEASONING', 'PACKAGING')
    OR EXISTS (
      SELECT 1
      FROM work_processes wp
      WHERE wp.factory_id = pwp.factory_id
        AND wp.id = pwp.work_process_id
        AND (
          wp.process_name LIKE '%熟制%'
          OR wp.process_name LIKE '%气调%'
          OR wp.process_name LIKE '%氣調%'
        )
    )
  );

UPDATE product_work_processes
SET allow_multiple_upstream_sources = FALSE
WHERE allow_multiple_upstream_sources IS NULL;

ALTER TABLE product_work_processes
    ALTER COLUMN allow_multiple_upstream_sources SET DEFAULT FALSE,
    ALTER COLUMN allow_multiple_upstream_sources SET NOT NULL;
