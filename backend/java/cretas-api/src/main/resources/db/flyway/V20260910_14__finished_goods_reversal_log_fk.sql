-- SP2: 成品批次关联撤回日志
-- 整单撤回完成时将 finished_goods_batches.reversal_log_id 指向 report_reversal_logs.id
ALTER TABLE finished_goods_batches
    ADD COLUMN IF NOT EXISTS reversal_log_id BIGINT REFERENCES report_reversal_logs(id);

COMMENT ON COLUMN finished_goods_batches.reversal_log_id IS 'SP2撤回完成后关联 report_reversal_logs.id; NULL=未撤回';
