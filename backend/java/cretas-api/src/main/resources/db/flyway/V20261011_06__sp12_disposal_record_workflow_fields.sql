-- SP12 T5: disposal_records 表新增 workflow_instance_id 字段
-- 报废审批 PENDING_APPROVAL → APPROVED 状态由 WorkflowEngineService 驱动

DO $$
BEGIN
    IF to_regclass('public.disposal_records') IS NULL THEN
        RAISE NOTICE 'V20261011_06 skipped: disposal_records not present before Hibernate DDL';
        RETURN;
    END IF;

    ALTER TABLE disposal_records
        ADD COLUMN IF NOT EXISTS workflow_instance_id VARCHAR(191);

    COMMENT ON COLUMN disposal_records.workflow_instance_id IS
        'SP12 T5: ApprovalWorkflowInstance.id for disposal approval workflow';

    CREATE INDEX IF NOT EXISTS idx_disposal_workflow_instance
        ON disposal_records(workflow_instance_id);
END $$;
