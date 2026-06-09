-- SP6 采购到付款 — 付款申请表
-- 状态机: PENDING → FINANCE_REVIEW → APPROVED → PAID
-- markPaid 三写原子: status=PAID + ar_ap_transactions(AP_PAYMENT) + suppliers.current_balance 扣减

CREATE TABLE IF NOT EXISTS payment_requests (
    id                    VARCHAR(191)   NOT NULL PRIMARY KEY,
    version               INTEGER,
    factory_id            VARCHAR(191)   NOT NULL,
    request_number        VARCHAR(50)    NOT NULL,
    purchase_order_id     VARCHAR(191)   NOT NULL,
    supplier_id           VARCHAR(191)   NOT NULL,

    -- 状态: PENDING / FINANCE_REVIEW / APPROVED / PAID / REJECTED / CANCELLED
    status                VARCHAR(32)    NOT NULL DEFAULT 'PENDING',

    -- 金额
    amount                NUMERIC(12,2)  NOT NULL,

    -- 银行信息
    payment_method        VARCHAR(50),
    bank_name             VARCHAR(100),
    bank_account          VARCHAR(100),
    remark                TEXT,

    -- 创建人
    created_by            BIGINT         NOT NULL,

    -- 财务审核
    finance_reviewed_by   BIGINT,
    finance_reviewed_at   TIMESTAMP,
    finance_review_note   TEXT,

    -- 审批
    approved_by           BIGINT,
    approved_at           TIMESTAMP,
    approve_note          TEXT,

    -- 付款确认
    paid_by               BIGINT,
    paid_at               TIMESTAMP,
    arap_transaction_id   VARCHAR(191),

    -- 驳回/取消
    reject_reason         TEXT,
    cancelled_at          TIMESTAMP,

    -- BaseEntity audit 字段
    created_at            TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP      NOT NULL DEFAULT NOW(),
    deleted_at            TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uidx_payment_requests_number
    ON payment_requests (factory_id, request_number)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_payment_requests_factory_status
    ON payment_requests (factory_id, status)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_payment_requests_po
    ON payment_requests (purchase_order_id)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_payment_requests_approved_at
    ON payment_requests (factory_id, approved_at)
    WHERE deleted_at IS NULL AND status = 'APPROVED';

COMMENT ON TABLE  payment_requests               IS 'SP6: 采购付款申请（采购员发起 → 财务审批 → 出纳付款）';
COMMENT ON COLUMN payment_requests.status        IS 'PENDING/FINANCE_REVIEW/APPROVED/PAID/REJECTED/CANCELLED';
COMMENT ON COLUMN payment_requests.arap_transaction_id IS 'markPaid 写入的 ar_ap_transactions.id（AP_PAYMENT）';
