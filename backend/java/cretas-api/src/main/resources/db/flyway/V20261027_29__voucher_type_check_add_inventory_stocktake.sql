-- 半成品盘点差异凭证 (SP7): voucher_type 加 'INVENTORY_STOCKTAKE'。
-- 现 vouchers 的 voucher_type CHECK (末次 V20261027_15 加 PL_CLOSING) 允许 8 类 →
-- 不含 INVENTORY_STOCKTAKE。SemiFinishedStocktakeServiceImpl:604 apply() 有已计价差异时
-- 无条件插入 VoucherType.INVENTORY_STOCKTAKE 凭证 → 触发 DB CHECK 违反 409 → 整事务回滚,
-- 半成品盘点差异过账 100% 失败 (盘盈 1405/6301 / 盘亏 6602.01/1405 从未成功)。
-- 单测 mock repo 照不到 DB CHECK, 仅 prod/PG 才暴 (同 V20261027_15 给 PL_CLOSING 的教训)。
-- 幂等 (DROP IF EXISTS + ADD)。

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
        'PL_CLOSING'::varchar,
        'INVENTORY_STOCKTAKE'::varchar
    ]::text[]));
