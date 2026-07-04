-- 资金段 GL 打通 (cash segment GL, finance audit Bug 5, PR): VoucherType 加了 CASH_RECEIPT
-- (借 1002 银行存款 / 贷 1122 应收账款) + CASH_PAYMENT (借 2202 应付账款 / 贷 1002 银行存款),
-- 让 收款/付款 的现金流动真正进 GL。但 vouchers 的 vouchers_voucher_type_check CHECK 约束
-- (末次 V20261027_38 加 COST_CARRYOVER) 只允许 10 类, 不含这两个新值 → 收款/付款凭证 insert
-- 触发 CHECK violation → 500。因凭证由 AFTER_COMMIT 监听器 fail-soft 生成, 不会 doom 收付款
-- 事务, 但会导致资金段凭证永远无法落库 (GL 继续漂移)。
--
-- 这是本 repo 第 6 次 "枚举加了但 DB CHECK 未同步加宽" 模式 (同
-- V20261027_15/29/34/35/37/38 的教训: PL_CLOSING / INVENTORY_STOCKTAKE / ck_it_type /
-- ck_aat_type / ck_it_status / COST_CARRYOVER)。prod PG 直接查 pg_constraint 已确认该约束
-- 存在且当前 10 值 (2026-07-04 核实)。单测 mock repo / H2 均照不到该 PG-only 具名约束,
-- 仅 prod PG 才暴 (同前 5 次教训)。
--
-- 修法: DROP + 重建 vouchers_voucher_type_check, 含 VoucherType 枚举全部 12 个值。
-- 顺带 drop 可能存在的另一命名 (ck_voucher_type, 防某些环境用了别名)。
-- 幂等 (DROP IF EXISTS + ADD), additive, 不改数据。

ALTER TABLE vouchers DROP CONSTRAINT IF EXISTS vouchers_voucher_type_check;
ALTER TABLE vouchers DROP CONSTRAINT IF EXISTS ck_voucher_type;

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
        'INVENTORY_STOCKTAKE'::varchar,
        'COST_CARRYOVER'::varchar,
        'CASH_RECEIPT'::varchar,
        'CASH_PAYMENT'::varchar
    ]::text[]));
