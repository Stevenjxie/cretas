-- 结转损益自动凭证 (P1): voucher_type 加 'PL_CLOSING'。
-- 现 vouchers 的 voucher_type CHECK (V20260602_01) 只允许 7 类业务凭证 →
-- 插入 PL_CLOSING 触发 DataIntegrityViolation 409。单测 mock repo 照不到 DB CHECK,
-- 仅 prod/PG 才暴 (同 V20261026_07 给 REVERSED 加的教训)。幂等 (DROP IF EXISTS + ADD)。

ALTER TABLE vouchers DROP CONSTRAINT IF EXISTS vouchers_voucher_type_check;

ALTER TABLE vouchers ADD CONSTRAINT vouchers_voucher_type_check
    CHECK (voucher_type::text = ANY (ARRAY[
        'SALES_RECEIPT'::varchar,
        'PURCHASE_PAYMENT'::varchar,
        'INVENTORY_TRANSFER'::varchar,
        'EXPENSE'::varchar,
        'WAGE'::varchar,
        'RETURN'::varchar,
        'DEPRECATION'::varchar,
        'PL_CLOSING'::varchar
    ]::text[]));
