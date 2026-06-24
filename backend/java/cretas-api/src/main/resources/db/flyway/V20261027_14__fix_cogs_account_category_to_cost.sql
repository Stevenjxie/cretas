-- 修正: 营业成本科目 (6401 主营业务成本 / 6402 其他业务成本) 大类 EXPENSE → COST
--
-- 原 V20260701_02 seed 把 6401/6402 标为 EXPENSE (期间费用), 导致利润表毛利润
-- = 营业收入 - 营业成本(COST) 中不含 COGS → 毛利 = 营收 (毛利率恒 100%, 误导).
-- 主营业务成本/其他业务成本是"营业成本", 应属 COST, 在毛利之上扣除;
-- 销售/管理/财务费用 (6601/6602/6603) 及 税金及附加 (6403, 在毛利之下) 才是 EXPENSE.
--
-- 影响: 利润表毛利润恢复有意义 (营收 - 营业成本); 营业利润/净利润不变
-- (IncomeStatementService 中 COST 与 EXPENSE 都在 营业利润/净利润 路径上扣除).
-- 资产负债表不变 (BalanceSheetService 未分配利润 = 营收 - COST - EXPENSE, 同时含两类).
--
-- 仅改系统级科目 (factory_id IS NULL): 工厂自定义同号科目可能有不同语义, 不跨租户静默改写.
-- 幂等: 仅翻转仍为 EXPENSE 的系统级 6401/6402.

UPDATE accounts
SET category = 'COST', updated_at = NOW()
WHERE code IN ('6401', '6402') AND category = 'EXPENSE' AND factory_id IS NULL;
