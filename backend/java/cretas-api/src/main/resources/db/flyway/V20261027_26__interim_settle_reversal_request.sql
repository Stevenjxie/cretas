-- 撤销小结治理 (interim-settle reversal governance): 申请 → 审批 → 执行。
-- 撤销不再是免审即时动作: 创建撤销申请 (PENDING_APPROVAL, 零库存副作用), 经 STOCKTAKE_APPROVAL_ROLES
-- (finance_manager/factory_super_admin/platform_admin) 审批后才执行 InterimSettleReversalService.reverse。
-- 纯 additive。审计: 本行 (requestedBy/reason/approvedBy/executedAt) + SFI REVERSE 流水 + 成品调整日志。

CREATE TABLE IF NOT EXISTS interim_settle_reversal_request (
    id                      VARCHAR(64) PRIMARY KEY,
    factory_id              VARCHAR(50)  NOT NULL,
    production_plan_id      VARCHAR(50)  NOT NULL,
    session_seq             INTEGER      NOT NULL,
    -- 1天时间窗锚点: 小结的 posted_at 快照 (非申请时间); 申请 + 审批 两端都据此校验 24h。
    settlement_posted_at    TIMESTAMP    NOT NULL,
    reason                  TEXT         NOT NULL,
    -- PENDING_APPROVAL / EXECUTED / REJECTED
    status                  VARCHAR(32)  NOT NULL DEFAULT 'PENDING_APPROVAL',
    requested_by            BIGINT,
    requested_at            TIMESTAMP    NOT NULL,
    approved_by             BIGINT,
    approved_at             TIMESTAMP,
    reject_reason           TEXT,
    executed_at             TIMESTAMP,
    -- 执行时快照被逆转影响的半成品/成品批次号 (逗号分隔), 供半成品盘点列撤销告警 (settlement 已硬删, 故快照)。
    affected_batch_numbers  TEXT,
    created_at              TIMESTAMP DEFAULT NOW(),
    updated_at              TIMESTAMP DEFAULT NOW(),
    deleted_at              TIMESTAMP NULL
);

CREATE INDEX IF NOT EXISTS idx_isrr_factory_plan
    ON interim_settle_reversal_request (factory_id, production_plan_id);
CREATE INDEX IF NOT EXISTS idx_isrr_factory_status
    ON interim_settle_reversal_request (factory_id, status);
CREATE INDEX IF NOT EXISTS idx_isrr_executed_at
    ON interim_settle_reversal_request (factory_id, executed_at);

-- 幂等: 同 (factory, plan, session_seq) 同时只允许一个 PENDING_APPROVAL 申请 (partial unique)。
CREATE UNIQUE INDEX IF NOT EXISTS uk_isrr_pending
    ON interim_settle_reversal_request (factory_id, production_plan_id, session_seq)
    WHERE status = 'PENDING_APPROVAL' AND deleted_at IS NULL;

COMMENT ON TABLE interim_settle_reversal_request IS '撤销小结申请 (申请→审批→执行治理; 审批前零库存副作用; 本行即操作留痕)';
