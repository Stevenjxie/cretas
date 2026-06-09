-- SP12 T6: payment_requests 表新增 workflow 字段
-- ALTER TABLE ADD COLUMN IF NOT EXISTS — 幂等，重复跑安全

ALTER TABLE payment_requests
    ADD COLUMN IF NOT EXISTS workflow_instance_id  VARCHAR(191),
    ADD COLUMN IF NOT EXISTS purpose_code          VARCHAR(50),
    ADD COLUMN IF NOT EXISTS purpose_description   TEXT,
    ADD COLUMN IF NOT EXISTS payee_name            VARCHAR(200),
    ADD COLUMN IF NOT EXISTS due_date              TIMESTAMP,
    ADD COLUMN IF NOT EXISTS submitted_by          BIGINT;

COMMENT ON COLUMN payment_requests.workflow_instance_id  IS 'SP12 T6: ApprovalWorkflowInstance.id — 审批流实例 ID';
COMMENT ON COLUMN payment_requests.purpose_code          IS 'SP12 T6: 付款用途代码 (PROCUREMENT_PAYMENT/SALARY_ADVANCE/EXPENSE_REIMBURSEMENT/OTHER)';
COMMENT ON COLUMN payment_requests.purpose_description   IS 'SP12 T6: 付款用途详细说明';
COMMENT ON COLUMN payment_requests.payee_name            IS 'SP12 T6: 收款方名称 (可与 supplier 不同，如个人付款)';
COMMENT ON COLUMN payment_requests.due_date              IS 'SP12 T6: 要求付款截止日期';
COMMENT ON COLUMN payment_requests.submitted_by          IS 'SP12 T6: 提交审批的操作人 user_id';

CREATE INDEX IF NOT EXISTS idx_pr_workflow_instance ON payment_requests(workflow_instance_id);
