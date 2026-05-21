-- V20260822_01__drop_tax_direct.sql
--
-- 删 Sprint 5 spike 数电票税局直连 6 字段 (F-TAX-DIRECT-1) — dead code 4 sprints 无 caller.
-- Sprint 6 W1 承诺接百望/诺诺 真实装从未落地. 决定清理.
--
-- 同步删除: 7 java files (TaxDirectInvoiceProvider interface + Noop/Baiwang impls + 3 DTOs + TaxDirectStatus enum)
-- 保留: InvoiceStatusTool (实际用 InvoiceService 不依赖 TaxDirect, 是 finance_invoice_status 真功能)
-- 保留: V20260606_04__invoice_status_intent.sql (注册 finance_invoice_status intent, 仍 live)
--
-- 数据安全: tax_direct_* 字段 4 sprints 无写入 (NoopTaxDirectProvider throws 不写, BaiwangStub 不 @Primary 也不写, InvoiceStatusTool 不用).
-- DROP 直接安全, 无数据丢失风险.
--
-- Triggered by: 2026-05-21 Sprint 5-9 comprehensive audit finding #2
-- spec: docs/audits/2026-05-21-sprint-5-to-9-comprehensive-audit.md

-- 删 partial index 先 (避免 column drop 时被 cascade 复杂化)
DROP INDEX IF EXISTS idx_inv_tax_direct_status;

ALTER TABLE invoice_records
    DROP COLUMN IF EXISTS tax_direct_status,
    DROP COLUMN IF EXISTS tax_direct_provider,
    DROP COLUMN IF EXISTS tax_direct_provider_id,
    DROP COLUMN IF EXISTS tax_direct_requested_at,
    DROP COLUMN IF EXISTS tax_direct_succeeded_at,
    DROP COLUMN IF EXISTS tax_direct_error_message;
