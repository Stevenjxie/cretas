-- AP open-item settlement core.
-- Historical AP_PAYMENT rows are intentionally not auto-allocated: they remain queryable anomalies.

ALTER TABLE ar_ap_transactions
    ADD COLUMN IF NOT EXISTS settled_amount NUMERIC(15,2),
    ADD COLUMN IF NOT EXISTS outstanding_amount NUMERIC(15,2),
    ADD COLUMN IF NOT EXISTS payment_status VARCHAR(32),
    ADD COLUMN IF NOT EXISTS currency_code VARCHAR(3),
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- The application previously had no currency dimension and all postings were implicit CNY.
UPDATE ar_ap_transactions
SET currency_code = 'CNY'
WHERE currency_code IS NULL;

-- Historical AP invoices predate payment allocation identity. Treating all of
-- them as fully unpaid would reopen payables that may already have been paid by
-- the legacy PaymentRequest flow. Keep their balance fields unknown and make
-- them explicitly non-payable until finance reconciles them. No historical
-- payment is auto-matched or otherwise bridged by this migration.
UPDATE ar_ap_transactions
SET payment_status = 'NEEDS_RECONCILIATION'
WHERE transaction_type = 'AP_INVOICE'
  AND payment_status IS NULL;

ALTER TABLE ar_ap_transactions
    DROP CONSTRAINT IF EXISTS ck_ar_ap_payable_open_item;
ALTER TABLE ar_ap_transactions
    ADD CONSTRAINT ck_ar_ap_payable_open_item CHECK (
        transaction_type <> 'AP_INVOICE'
        OR amount IS NULL
        OR (payment_status = 'NEEDS_RECONCILIATION'
            AND settled_amount IS NULL
            AND outstanding_amount IS NULL)
        OR (settled_amount IS NOT NULL
            AND outstanding_amount IS NOT NULL
            AND settled_amount >= 0
            AND outstanding_amount >= 0
            AND settled_amount + outstanding_amount = ABS(amount)
            AND payment_status IN ('UNPAID', 'PARTIALLY_PAID', 'PAID')
            AND ((payment_status = 'UNPAID' AND settled_amount = 0 AND outstanding_amount > 0)
                OR (payment_status = 'PARTIALLY_PAID' AND settled_amount > 0 AND outstanding_amount > 0)
                OR (payment_status = 'PAID' AND outstanding_amount = 0)))
    );

ALTER TABLE ar_ap_transactions
    DROP CONSTRAINT IF EXISTS ck_ar_ap_currency_code;
ALTER TABLE ar_ap_transactions
    ADD CONSTRAINT ck_ar_ap_currency_code CHECK (
        currency_code IS NULL OR currency_code ~ '^[A-Z]{3}$'
    );

CREATE TABLE IF NOT EXISTS ar_ap_payment_allocations (
    id VARCHAR(191) PRIMARY KEY,
    factory_id VARCHAR(191) NOT NULL,
    payment_transaction_id VARCHAR(191) NOT NULL,
    payable_transaction_id VARCHAR(191) NOT NULL,
    allocated_amount NUMERIC(15,2) NOT NULL,
    currency_code VARCHAR(3) NOT NULL,
    operated_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    CONSTRAINT fk_ap_alloc_payment FOREIGN KEY (payment_transaction_id)
        REFERENCES ar_ap_transactions(id) ON DELETE RESTRICT,
    CONSTRAINT fk_ap_alloc_payable FOREIGN KEY (payable_transaction_id)
        REFERENCES ar_ap_transactions(id) ON DELETE RESTRICT,
    CONSTRAINT ck_ap_alloc_amount CHECK (allocated_amount > 0),
    CONSTRAINT ck_ap_alloc_currency CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT uk_ap_payment_allocation_pair UNIQUE (
        factory_id, payment_transaction_id, payable_transaction_id
    )
);

CREATE INDEX IF NOT EXISTS idx_ap_alloc_payment
    ON ar_ap_payment_allocations(factory_id, payment_transaction_id)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_ap_alloc_payable
    ON ar_ap_payment_allocations(factory_id, payable_transaction_id)
    WHERE deleted_at IS NULL;

-- A payment settlement command is unique per factory and idempotency key.
CREATE UNIQUE INDEX IF NOT EXISTS ux_ap_settlement_source
    ON ar_ap_transactions(factory_id, transaction_type, source_type, source_id)
    WHERE transaction_type = 'AP_PAYMENT'
      AND source_type = 'PAYABLE_SETTLEMENT'
      AND source_id IS NOT NULL
      AND deleted_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_ap_settlement_payment_reference
    ON ar_ap_transactions(factory_id, payment_reference)
    WHERE transaction_type = 'AP_PAYMENT'
      AND source_type = 'PAYABLE_SETTLEMENT'
      AND payment_reference IS NOT NULL
      AND deleted_at IS NULL;
