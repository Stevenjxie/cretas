-- SP12: 撤回快速通道标记
-- 在 report_reversal_logs 表添加 fast_path 布尔列，
-- 记录该条撤回是否走快速通道（本人+无下游消费+5分钟窗口内，免审批直接执行）。
-- 默认 false，兼容历史数据（历史记录均视为正常审批路径）。
ALTER TABLE report_reversal_logs
    ADD COLUMN IF NOT EXISTS fast_path BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN report_reversal_logs.fast_path IS
    'SP12快速撤回标记: true=本人+无下游消费+5min窗口内免审批执行; false=正常审批路径或无报工直通';
