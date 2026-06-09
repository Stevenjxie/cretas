-- SP6 采购到付款 — 补充索引
-- 为采购付款/发票/异常单的常用查询路径加速

-- purchase_exceptions: 按供应商查询
CREATE INDEX IF NOT EXISTS idx_purchase_exceptions_supplier
    ON purchase_exceptions (factory_id, supplier_id)
    WHERE deleted_at IS NULL;

-- purchase_exceptions: 按物料类型查询（用于超收退货关联）
CREATE INDEX IF NOT EXISTS idx_purchase_exceptions_material
    ON purchase_exceptions (factory_id, material_type_id)
    WHERE deleted_at IS NULL;

-- payment_requests: 按供应商查询
CREATE INDEX IF NOT EXISTS idx_payment_requests_supplier
    ON payment_requests (factory_id, supplier_id)
    WHERE deleted_at IS NULL;

-- payment_requests: 出纳台账主查询（APPROVED + approvedAt 升序）
CREATE INDEX IF NOT EXISTS idx_payment_requests_cashier
    ON payment_requests (factory_id, approved_at ASC)
    WHERE deleted_at IS NULL AND status = 'APPROVED';

-- purchase_invoices: 按 invoice_date 查询（逾期催收查询用）
CREATE INDEX IF NOT EXISTS idx_purchase_invoices_date
    ON purchase_invoices (factory_id, invoice_date)
    WHERE deleted_at IS NULL;

-- purchase_invoices: 逾期未对账查询（PENDING 状态 + invoice_date）
CREATE INDEX IF NOT EXISTS idx_purchase_invoices_pending_date
    ON purchase_invoices (factory_id, invoice_date)
    WHERE deleted_at IS NULL AND reconcile_status = 'PENDING';

-- purchase_accounting_subjects: 工厂科目列表
CREATE INDEX IF NOT EXISTS idx_purchase_accounting_subjects_factory
    ON purchase_accounting_subjects (factory_id, sort_order)
    WHERE deleted_at IS NULL AND is_active = TRUE;
