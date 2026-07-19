-- PT-01: work_process_tasks is the only runtime truth for production process tasks.
-- Production read-only preview (2026-07-19): process_tasks=244, work_process_tasks=55.
-- 205 old rows are WPT mirrors; only 25 still match canonical rows and 6 statuses differ.
-- 39 rows are non-mirrors and 180 are orphan mirrors, for 219 legacy-only rows total.
-- Three legacy-only production_reports have no attachments, WIP children, or reversal
-- children and are authorized test data.
-- No foreign keys, views, or triggers depend on process_tasks. No CASCADE is used.
-- Rollback: restore the pre-migration database snapshot together with the previous release.

UPDATE production_reports report
SET work_process_task_id = canonical.id,
    process_task_id = NULL,
    updated_at = NOW()
FROM work_process_tasks canonical
WHERE report.work_process_task_id IS NULL
  AND report.process_task_id ~ '^WPT-[0-9]+$'
  AND canonical.id = substring(report.process_task_id FROM 5)::BIGINT
  AND canonical.factory_id = report.factory_id;

CREATE TEMP TABLE legacy_process_task_report_ids (id BIGINT PRIMARY KEY) ON COMMIT DROP;

WITH RECURSIVE legacy_reports AS (
    SELECT report.id
    FROM production_reports report
    WHERE report.process_task_id IS NOT NULL
      AND report.work_process_task_id IS NULL
    UNION
    SELECT child.id
    FROM production_reports child
    JOIN legacy_reports parent ON child.reversal_of_id = parent.id
)
INSERT INTO legacy_process_task_report_ids (id)
SELECT id FROM legacy_reports;

DELETE FROM semi_finished_inventory_transactions transaction_row
WHERE transaction_row.report_id IN (SELECT id FROM legacy_process_task_report_ids);

DELETE FROM attachments attachment
WHERE attachment.entity_type = 'PRODUCTION_REPORT'
  AND attachment.entity_id IN (SELECT id::TEXT FROM legacy_process_task_report_ids);

DELETE FROM production_reports report
WHERE report.id IN (SELECT id FROM legacy_process_task_report_ids);

UPDATE process_checkin_records checkin
SET process_task_id = substring(checkin.process_task_id FROM 5)
WHERE checkin.process_task_id ~ '^WPT-[0-9]+$'
  AND EXISTS (
      SELECT 1 FROM work_process_tasks canonical
      WHERE canonical.id = substring(checkin.process_task_id FROM 5)::BIGINT
        AND canonical.factory_id = checkin.factory_id
  );

DELETE FROM process_checkin_records checkin
WHERE checkin.process_task_id IS NOT NULL
  AND NOT (
      checkin.process_task_id ~ '^[0-9]+$'
      AND EXISTS (
          SELECT 1 FROM work_process_tasks canonical
          WHERE canonical.id = checkin.process_task_id::BIGINT
            AND canonical.factory_id = checkin.factory_id
      )
  );

UPDATE ai_intent_configs
SET is_active = FALSE,
    description = 'Disabled by PT-01: create tasks through production batch spawn only',
    updated_at = NOW()
WHERE intent_code = 'PROCESS_TASK_CREATE';

UPDATE drools_rules
SET enabled = FALSE,
    rule_description = 'Disabled by PT-01: legacy ProcessTask entity removed',
    updated_at = NOW()
WHERE rule_content LIKE '%com.cretas.aims.entity.ProcessTask%';

UPDATE state_machines
SET enabled = FALSE,
    updated_at = NOW()
WHERE UPPER(entity_type) = 'PROCESS_TASK';

ALTER TABLE production_reports DROP COLUMN IF EXISTS process_task_id;

DROP TABLE IF EXISTS process_tasks;
