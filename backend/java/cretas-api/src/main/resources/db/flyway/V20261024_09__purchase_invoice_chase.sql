ALTER TABLE purchase_orders
    ADD COLUMN IF NOT EXISTS purchase_invoice_status VARCHAR(32) NOT NULL DEFAULT 'NOT_RECEIVED';

COMMENT ON COLUMN purchase_orders.purchase_invoice_status IS
    'Purchase invoice lifecycle: NOT_RECEIVED, RECEIVED, RECONCILED';

UPDATE purchase_orders po
   SET purchase_invoice_status = 'RECONCILED'
 WHERE EXISTS (
        SELECT 1
          FROM purchase_invoices pi
         WHERE pi.purchase_order_id = po.id
           AND pi.deleted_at IS NULL
           AND pi.reconcile_status = 'MATCHED'
 );

UPDATE purchase_orders po
   SET purchase_invoice_status = 'RECEIVED'
 WHERE purchase_invoice_status <> 'RECONCILED'
   AND EXISTS (
        SELECT 1
          FROM purchase_invoices pi
         WHERE pi.purchase_order_id = po.id
           AND pi.deleted_at IS NULL
 );

CREATE TABLE IF NOT EXISTS purchase_invoice_chase_logs (
    id                  VARCHAR(36) PRIMARY KEY,
    factory_id          VARCHAR(191) NOT NULL,
    purchase_order_id   VARCHAR(191) NOT NULL,
    order_number        VARCHAR(50),
    chase_level         VARCHAR(32) NOT NULL,
    status              VARCHAR(32) NOT NULL DEFAULT 'SENT',
    due_date            DATE NOT NULL,
    chase_window_start  DATE NOT NULL,
    days_overdue        INTEGER NOT NULL,
    closed_at           TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at          TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_pi_chase_po_level_window_active
    ON purchase_invoice_chase_logs(factory_id, purchase_order_id, chase_level, chase_window_start)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_pi_chase_factory_po_status
    ON purchase_invoice_chase_logs(factory_id, purchase_order_id, status);

COMMENT ON TABLE purchase_invoice_chase_logs IS
    'Active purchase invoice chase push log and idempotency window record';
