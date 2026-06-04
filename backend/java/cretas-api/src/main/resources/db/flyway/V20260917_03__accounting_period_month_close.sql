-- Wave2 月结自动闭环 (Month-Close Auto-Loop)
-- 兑现邓总 "30号数据完结, 1-3号出报表, 留20天调整窗口".
--
-- 在 Sprint 7 T2 accounting_periods 状态机基础上扩列:
--   * adjust_deadline: 结账后 20 天调整窗口截止. CLOSED 期间在此之前 voucher 仍可写
--     (assertOpen 调整窗口语义). null = 旧 CLOSED 行硬锁 (backwards compat).
--   * reconciliation_status / reconciliation_summary: 月结前对账校验结论 (审计留痕).
--   * total_revenue_snapshot / net_profit_snapshot / income_statement_snapshot:
--     结账时冻结的利润表 (P&L) 快照, 即使后续调整窗口内 voucher 变动也保留结账当时的报表.
--   * report_ready_at: 报表生成完成时间 (邓总 "1-3号出报表" 达成标记).
--
-- 幂等: 用 IF NOT EXISTS 加列, 重复 apply 安全.

ALTER TABLE accounting_periods
    ADD COLUMN IF NOT EXISTS adjust_deadline           TIMESTAMP,
    ADD COLUMN IF NOT EXISTS reconciliation_status     VARCHAR(32),
    ADD COLUMN IF NOT EXISTS reconciliation_summary    VARCHAR(2000),
    ADD COLUMN IF NOT EXISTS total_revenue_snapshot    NUMERIC(18, 2),
    ADD COLUMN IF NOT EXISTS net_profit_snapshot       NUMERIC(18, 2),
    ADD COLUMN IF NOT EXISTS income_statement_snapshot TEXT,
    ADD COLUMN IF NOT EXISTS report_ready_at           TIMESTAMP;

-- 调整窗口检索索引 (scheduler 未来可扫"窗口已过期但仍 CLOSED"的期间; 看板按截止排序)
CREATE INDEX IF NOT EXISTS idx_ap_adjust_deadline
    ON accounting_periods(adjust_deadline)
    WHERE adjust_deadline IS NOT NULL;

COMMENT ON COLUMN accounting_periods.adjust_deadline IS
    'Wave2 月结: 结账后调整窗口截止 (closed_at + 20 天). CLOSED 期间在此之前 voucher 仍可写; 之后硬锁. null = 旧 CLOSED 行立即硬锁 (backwards compat).';
COMMENT ON COLUMN accounting_periods.reconciliation_status IS
    'Wave2 月结: 结账前对账校验结论. PASS (无阻塞) | WARNING (有未审批调整等非阻塞项) | null (未执行月结编排).';
COMMENT ON COLUMN accounting_periods.reconciliation_summary IS
    'Wave2 月结: 对账校验摘要文本 (各 check 项汇总), 审计留痕.';
COMMENT ON COLUMN accounting_periods.total_revenue_snapshot IS
    'Wave2 月结: 结账时冻结的营业收入合计 (来自 IncomeStatement P&L).';
COMMENT ON COLUMN accounting_periods.net_profit_snapshot IS
    'Wave2 月结: 结账时冻结的净利润 (来自 IncomeStatement P&L).';
COMMENT ON COLUMN accounting_periods.income_statement_snapshot IS
    'Wave2 月结: 结账时冻结的完整利润表 JSON 快照. 调整窗口内 voucher 变动不影响此快照 (报表冻结).';
COMMENT ON COLUMN accounting_periods.report_ready_at IS
    'Wave2 月结: 报表 (P&L 快照) 生成完成时间. 邓总 "1-3号出报表" 达成标记.';
