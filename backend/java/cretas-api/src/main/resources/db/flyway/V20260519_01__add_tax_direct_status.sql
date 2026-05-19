-- F-TAX-DIRECT-1 Sprint 5 spike (2026-05-19) — 数电票税局直连字段
--
-- 大客户硬需 (会计师事务所 / 上市公司 / 政府): HJ 不支持税局直连.
-- Cretas 加 Provider 抽象 (百望 / 诺诺 / 瑞宏 等), Sprint 6 W1 真集成.
--
-- 这是 SPIKE migration — 字段先加, 实际写入逻辑 Sprint 6 完成.
-- 现有 invoice_records 行 tax_direct_status 默认 'NOT_REQUESTED' 表示未启用直连.
--
-- spec: docs/superpowers/specs/2026-05-19-tax-direct-spike.md §3.3

ALTER TABLE invoice_records
    ADD COLUMN IF NOT EXISTS tax_direct_status        VARCHAR(20)  DEFAULT 'NOT_REQUESTED',
    ADD COLUMN IF NOT EXISTS tax_direct_provider      VARCHAR(30)  DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS tax_direct_provider_id   VARCHAR(100) DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS tax_direct_requested_at  TIMESTAMP    DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS tax_direct_succeeded_at  TIMESTAMP    DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS tax_direct_error_message TEXT         DEFAULT NULL;

COMMENT ON COLUMN invoice_records.tax_direct_status        IS '数电票直连税局状态 (NOT_REQUESTED / REQUESTED / SUCCESS / FAILED)';
COMMENT ON COLUMN invoice_records.tax_direct_provider      IS 'Provider 标识 (BAIWANG / NUONUO / RUIHONG), null = 未走直连';
COMMENT ON COLUMN invoice_records.tax_direct_provider_id   IS 'Provider 内部 ID (用于轮询状态 / 下载 PDF / 红冲)';
COMMENT ON COLUMN invoice_records.tax_direct_requested_at  IS '提交直连请求时间';
COMMENT ON COLUMN invoice_records.tax_direct_succeeded_at  IS '税局出票成功时间';
COMMENT ON COLUMN invoice_records.tax_direct_error_message IS 'Provider / 税局失败原因 (FAILED 时填充)';

-- 索引: 轮询 scheduler 需要快速查 REQUESTED 状态的记录
CREATE INDEX IF NOT EXISTS idx_inv_tax_direct_status
    ON invoice_records(tax_direct_status)
    WHERE tax_direct_status = 'REQUESTED';
