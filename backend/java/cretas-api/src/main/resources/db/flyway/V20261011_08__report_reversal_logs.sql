-- SP2: 整单撤回审批日志表
-- 用于三层守卫 + 幂等控制 + 审批流
-- UNIQUE(batch_id, reversal_scope) 防重复撤回
CREATE TABLE IF NOT EXISTS report_reversal_logs (
    id               BIGSERIAL    PRIMARY KEY,
    factory_id       VARCHAR(50)  NOT NULL,
    batch_id         BIGINT       NOT NULL,
    plan_id          VARCHAR(191),
    reversal_scope   VARCHAR(20)  NOT NULL DEFAULT 'WHOLE_ORDER',
    submitted_by     BIGINT       NOT NULL,
    approved_by      BIGINT,
    reason           VARCHAR(500),
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    reverted_txn_ids JSONB,
    approved_at      TIMESTAMP,
    created_at       TIMESTAMP    DEFAULT NOW(),
    updated_at       TIMESTAMP    DEFAULT NOW(),
    deleted_at       TIMESTAMP,
    CONSTRAINT uq_reversal_batch UNIQUE (batch_id, reversal_scope)
);

CREATE INDEX IF NOT EXISTS idx_rrl_factory_batch ON report_reversal_logs(factory_id, batch_id);
CREATE INDEX IF NOT EXISTS idx_rrl_status ON report_reversal_logs(factory_id, status);

COMMENT ON TABLE  report_reversal_logs IS 'SP2整单撤回日志: status=PENDING|APPROVED|DONE|REJECTED; 幂等键(batch_id,reversal_scope)';
COMMENT ON COLUMN report_reversal_logs.reversal_scope IS 'WHOLE_ORDER=整单撤回(当前只支持此类型)';
COMMENT ON COLUMN report_reversal_logs.reverted_txn_ids IS '已成功撤回的 semi_finished_inventory_transactions.id 列表(jsonb)';
