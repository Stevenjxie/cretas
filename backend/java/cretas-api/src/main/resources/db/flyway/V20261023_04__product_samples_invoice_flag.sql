-- V20261023_01: 研发试样票务字段 — has_invoice + invoice_number
--
-- Background (catalog 行31): 研发试样采购时供应商有票/无票影响会计科目:
--   有票 (has_invoice=true)  → 进项税可抵扣 → 科目 2221.02
--   无票 (has_invoice=false) → 计入管理费用  → 科目 6602
--   未指定 (null)            → 向后兼容, 历史样品数据不受影响
--
-- Changes (all idempotent via IF NOT EXISTS):
--   1. ADD COLUMN has_invoice BOOLEAN (nullable, 向后兼容)
--   2. ADD COLUMN invoice_number VARCHAR(100) (nullable)

ALTER TABLE product_samples
    ADD COLUMN IF NOT EXISTS has_invoice BOOLEAN;

ALTER TABLE product_samples
    ADD COLUMN IF NOT EXISTS invoice_number VARCHAR(100);

COMMENT ON COLUMN product_samples.has_invoice IS
    '研发试样采购是否有票: true=有票(进项税抵扣→2221.02), false=无票(费用→6602), null=未指定(历史兼容)';

COMMENT ON COLUMN product_samples.invoice_number IS
    '试样采购发票号码 (has_invoice=true 时填写)';
