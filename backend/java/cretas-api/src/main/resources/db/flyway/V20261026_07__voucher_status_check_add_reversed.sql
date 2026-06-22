-- Feature #7 (R12) hotfix: 红字冲销把原凭证置 REVERSED, 但 vouchers_status_check
-- CHECK 约束只允许 DRAFT/POSTED/VOID → UPDATE 触发 DataIntegrityViolation → 409 "数据处理异常"。
-- V20261026_06 加了 reversal/original_voucher_id 列但漏了放开 status CHECK。
-- prod 活体验证 (PG13, cretas_prod_db) 暴露: 单测 mock repo 照不到 DB CHECK 约束。
--
-- 修: 重建 status CHECK 约束, 纳入 REVERSED。幂等 (DROP IF EXISTS + ADD)。

ALTER TABLE vouchers DROP CONSTRAINT IF EXISTS vouchers_status_check;

ALTER TABLE vouchers ADD CONSTRAINT vouchers_status_check
    CHECK (status::text = ANY (ARRAY[
        'DRAFT'::varchar,
        'POSTED'::varchar,
        'VOID'::varchar,
        'REVERSED'::varchar
    ]::text[]));
