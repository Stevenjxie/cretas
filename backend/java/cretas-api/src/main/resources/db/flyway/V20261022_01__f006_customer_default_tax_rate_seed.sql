-- V20261022_01__f006_customer_default_tax_rate_seed.sql
--
-- 根因数据修复 (Fable 戳穿点 2026-06-11): 标准下单流程永远产 tax_amount=0。
--
-- ============================================================================
-- 背景 (demo agent 实证)
-- ============================================================================
--   含税三行凭证 (V-2026-0054) 只能靠 SQL 手设 sales_orders.tax_amount=520 才演成;
--   经标准 API 下单永远产 tax_amount=0 → 凭证 line3 (omitWhenZero 销项税) 永远省略
--   → 含税凭证永远走不通。
--
-- ============================================================================
-- 真因 = case (b): 税率继承链全部 fallback 到 0
-- ============================================================================
--   SalesServiceImpl.createSalesOrder / updateSalesOrder 的税额汇总代码 (#737/SP4)
--   逻辑本身**正确** —— order.taxAmount = Σ(item.lineAmountWithTax − item.lineAmount),
--   减法兜底, 已在代码中 set (createSalesOrder L556, updateSalesOrder L1390)。
--
--   但三层继承链 Customer.defaultTaxRate → SalesOrder.defaultTaxRate → SalesOrderItem.taxRate
--   依赖**客户级 default_tax_rate** 作为种子。审计发现:
--     SELECT factory_id, count(default_tax_rate) FROM customers GROUP BY factory_id;
--   **全部工厂、全部客户 default_tax_rate 均为 NULL** (含 F006 叮咚及各冷藏仓子客户)。
--   下单时前端通常不传显式 item.taxRate → effectiveTaxRate fallback 0
--   → lineAmountWithTax == lineAmount → taxAmount=0 → 凭证 2 行。
--
-- ============================================================================
-- 修复 = 给 F006 真实食品客户配 default_tax_rate
-- ============================================================================
--   食品流通环节增值税常见税率 13% (一般纳税人销售货物)。配置后, 经标准 API 下单
--   即自动产非零销项税 → 凭证三行 (借应收含税 / 贷主营收入未税 / 贷应交税费-销项税)。
--
--   ⚠️ 资金口径 (#737 不变): 此迁移**只配客户级税率种子**, 不改任何订单金额口径。
--     totalAmount 仍是未税净额 (毛利对未税成本), taxAmount 单列, 含税=totalAmount+taxAmount。
--     现有 tax_amount=0 的历史订单**不变** (本迁移只动 customers 表, 不碰 sales_orders)。
--
--   ⚠️ 向后兼容: 只 UPDATE default_tax_rate IS NULL 的行 (幂等, 不覆盖任何手工设过的税率);
--     排除 RBAC 测试夹具客户 (name LIKE 'RBAC %') —— 它们是权限测试用, 不应带业务税率;
--     未配税率的客户/其他工厂仍 0 税 (凭证退化 2 行), 行为不变。

-- F006 (六扇门) 真实食品客户: 叮咚 主客户 + 各冷藏仓子客户。
-- 幂等 + 防误伤: 仅 factory_id='F006' 且 default_tax_rate IS NULL 且非 RBAC 测试夹具。
UPDATE customers
SET default_tax_rate = 13.00,
    updated_at = NOW()
WHERE factory_id = 'F006'
  AND default_tax_rate IS NULL
  AND name NOT LIKE 'RBAC %'
  AND deleted_at IS NULL;
