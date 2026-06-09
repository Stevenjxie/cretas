-- SP12 T4: factory_stocktakes 表新增 workflow_instance_id 字段
-- 盘点 PENDING_APPROVAL → APPROVED 状态由 WorkflowEngineService 驱动

ALTER TABLE factory_stocktakes
    ADD COLUMN IF NOT EXISTS workflow_instance_id VARCHAR(191);

COMMENT ON COLUMN factory_stocktakes.workflow_instance_id IS 'SP12 T4: ApprovalWorkflowInstance.id — 盘点审批流实例 ID';

CREATE INDEX IF NOT EXISTS idx_stocktake_workflow_instance ON factory_stocktakes(workflow_instance_id);
