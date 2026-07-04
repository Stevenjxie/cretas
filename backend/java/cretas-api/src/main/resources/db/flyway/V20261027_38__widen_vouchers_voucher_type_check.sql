-- 期末结转成本 (period-end cost carry, PR #1226): VoucherType 加了 COST_CARRYOVER
-- (借 6401 主营业务成本 / 贷 1405 库存商品, 见 VoucherType.java 注释), 但 vouchers 的
-- vouchers_voucher_type_check CHECK 约束 (末次 V20261027_29 加 INVENTORY_STOCKTAKE)
-- 只允许 9 类, 不含 COST_CARRYOVER → 期末结转成本凭证 insert 触发 CHECK violation
-- → 500 → @Transactional 整个期末结转事务回滚, 首次 period-close 100% 失败。
--
-- 这是本 repo 第 5 次 "枚举加了但 DB CHECK 未同步加宽" 模式 (同
-- V20261027_15/29/34/35/37 的教训: PL_CLOSING / INVENTORY_STOCKTAKE / ck_it_type /
-- ck_aat_type / ck_it_status)。#1226 作者曾误称"vouchers 无 voucher_type CHECK 约束"
-- —— 事实错误, prod PG 直接查询 pg_constraint 已确认该约束存在且仅 9 值
-- (2026-07-04 核实)。单测 mock repo / H2 均照不到该 PG-only 具名约束,
-- 仅 prod PG 才暴 (同前 4 次教训)。
--
-- 修法: DROP + 重建 vouchers_voucher_type_check, 含 VoucherType 枚举全部 10 个值。
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
        'COST_CARRYOVER'::varchar
    ]::text[]));
