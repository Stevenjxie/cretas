-- SP6 采购到付款 — 采购发票表
-- 发票上传 → 对账（MATCHED/MISMATCHED）→ 逾期催收

CREATE TABLE IF NOT EXISTS purchase_invoices (
    id                  VARCHAR(191)   NOT NULL PRIMARY KEY,
    factory_id          VARCHAR(191)   NOT NULL,
    purchase_order_id   VARCHAR(191)   NOT NULL,
    supplier_id         VARCHAR(191),

    -- 发票基础信息
    invoice_number      VARCHAR(100),
    invoice_code        VARCHAR(50),
    invoice_type        VARCHAR(32),
    invoice_date        DATE,

    -- 金额
    amount              NUMERIC(12,2)  NOT NULL,
    tax_amount          NUMERIC(12,2),
    amount_without_tax  NUMERIC(12,2),

    -- 对账状态: PENDING / MATCHED / MISMATCHED
    reconcile_status    VARCHAR(32)    NOT NULL DEFAULT 'PENDING',
    reconcile_note      TEXT,
    reconciled_by       BIGINT,
    reconciled_at       TIMESTAMP,

    -- OCR 相关
    attachment_key      VARCHAR(500),
    ocr_confidence      NUMERIC(5,4),
    ocr_raw_result      TEXT,
    ocr_processed_at    TIMESTAMP,

    -- 上传人
    uploaded_by         BIGINT         NOT NULL,

    -- BaseEntity audit 字段
    created_at          TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP      NOT NULL DEFAULT NOW(),
    deleted_at          TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uidx_purchase_invoices_number
    ON purchase_invoices (factory_id, invoice_number)
    WHERE deleted_at IS NULL AND invoice_number IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_purchase_invoices_factory_status
    ON purchase_invoices (factory_id, reconcile_status)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_purchase_invoices_po
    ON purchase_invoices (purchase_order_id)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE  purchase_invoices                    IS 'SP6: 采购发票（OCR上传 → 对账 → 逾期催收）';
COMMENT ON COLUMN purchase_invoices.reconcile_status   IS 'PENDING=待对账, MATCHED=对账通过, MISMATCHED=不一致';
COMMENT ON COLUMN purchase_invoices.ocr_confidence     IS 'OCR 置信度 0.0000~1.0000';
