-- 修正: 主营业务成本 (6401) 科目大类 EXPENSE → COST
--
-- 原 V20260701_02 seed 把 6401 主营业务成本 标为 EXPENSE (期间费用), 导致利润表
-- 毛利润 = 营业收入 - 营业成本(COST) 中不含 COGS → 毛利 = 营收 (毛利率恒 100%, 误导).
-- 主营业务成本是"营业成本", 应属 COST, 在毛利之上扣除; 销售/管理/财务费用才是 EXPENSE.
--
-- 此修正与代码约定一致: IncomeStatementService.inferCategoryByCode 对未绑定 6xxx 已 fallback COST,
-- 且 IncomeStatementServiceTest 用 COST 类别表示主营业务成本. 仅 seed 数据是异常.
--
-- 影响: 利润表毛利润恢复有意义 (营收 - 营业成本); 营业利润/净利润不变 (COST 与 EXPENSE 都在毛利/净利路径).
-- 资产负债表不变 (未分配利润 = 营收 - COST - EXPENSE, 6401 在两类之一均计入).
-- 幂等: 仅翻转仍为 EXPENSE 的 6401 (系统级 + 任何工厂自定义错值).

UPDATE accounts
SET category = 'COST', updated_at = NOW()
WHERE code = '6401' AND category = 'EXPENSE';
