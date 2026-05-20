-- Sprint 7 T2 F-PERIOD: 期间结账 (Accounting Period)
-- 大企业财务月底 / 季末 / 年末 关账机制. Round 12 §G.5 客户需求.
--
-- 状态机: OPEN ──requestClose──► PENDING_CLOSE ──confirmClose──► CLOSED
--                    ▲                                              │
--                    └──────────── reopenPeriod (audit) ◄────────────┘
--
-- VoucherService.save/post/void 必查 AccountingPeriod.status — CLOSED 抛
-- PeriodClosedException (400). 没有 row 的 factory+year+month 默认 OPEN
-- (backwards compat — 老 factory 不被新机制 gate).

CREATE TABLE IF NOT EXISTS accounting_periods (
    id                              VARCHAR(191) PRIMARY KEY,
    factory_id                      VARCHAR(191) NOT NULL,
    year                            INTEGER      NOT NULL,
    month                           INTEGER      NOT NULL
                                        CHECK (month BETWEEN 1 AND 12),
    status                          VARCHAR(32)  NOT NULL DEFAULT 'OPEN'
                                        CHECK (status IN ('OPEN', 'PENDING_CLOSE', 'CLOSED')),
    opened_at                       TIMESTAMP,
    opened_by                       BIGINT,
    closed_at                       TIMESTAMP,
    closed_by                       BIGINT,
    reopened_at                     TIMESTAMP,
    reopened_by                     BIGINT,
    reopen_reason                   VARCHAR(1000),
    approval_workflow_instance_id   VARCHAR(191),
    created_at                      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at                      TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at                      TIMESTAMP
);

-- 唯一约束: factory 内同一 (year, month) 只能有一个 period 行
CREATE UNIQUE INDEX IF NOT EXISTS uk_accounting_period_factory_year_month
    ON accounting_periods(factory_id, year, month)
    WHERE deleted_at IS NULL;

-- 检索索引
CREATE INDEX IF NOT EXISTS idx_ap_factory     ON accounting_periods(factory_id);
CREATE INDEX IF NOT EXISTS idx_ap_status      ON accounting_periods(status);
CREATE INDEX IF NOT EXISTS idx_ap_year_month  ON accounting_periods(year, month);

COMMENT ON TABLE accounting_periods IS
    'Sprint 7 T2 F-PERIOD: 期间结账状态记录. factory_id 内 (year, month) 唯一. 没有 row 默认 OPEN.';
COMMENT ON COLUMN accounting_periods.status IS
    'OPEN (默认, voucher 可写) | PENDING_CLOSE (财务发起, 走 BUDGET_APPROVAL workflow) | CLOSED (gate voucher 写)';
COMMENT ON COLUMN accounting_periods.reopen_reason IS
    '反结账原因 audit log — finance director CLOSED→OPEN 操作必填';
COMMENT ON COLUMN accounting_periods.approval_workflow_instance_id IS
    'PENDING_CLOSE 时关联的 ApprovalWorkflowInstance.id (BUDGET_APPROVAL decisionType)';
