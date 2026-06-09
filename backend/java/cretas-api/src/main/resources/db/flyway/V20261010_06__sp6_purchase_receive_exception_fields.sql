-- SP6 采购到付款 — purchase_receive_records 超收/少收字段（历史记录用）
-- 当前异常单已独立为 purchase_exceptions 表（V20261010_07）
-- 这里仅保留 receive_record 级别的快速状态字段，供列表页展示用
-- 幂等: ADD COLUMN IF NOT EXISTS

ALTER TABLE purchase_receive_records
    ADD COLUMN IF NOT EXISTS has_exception   BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS exception_count INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN purchase_receive_records.has_exception   IS 'SP6: 是否含超收/少收异常（快速过滤用）';
COMMENT ON COLUMN purchase_receive_records.exception_count IS 'SP6: 异常单数量（与 purchase_exceptions 计数一致）';
