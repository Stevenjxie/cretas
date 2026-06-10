-- V20261013_02__so_draft_allow_empty_price.sql
--
-- E-2 空价销售订单草稿支持 (六扇门追溯矩阵 E-2, 2026-06-10)
--
-- 需求依据:
--   行485 (P0): "订单单价/成本后面才有，下单时可空"
--   行717 (constraint): "后期审核时报价/单价应有数据不能空"
--
-- 修法:
--   1. 禁用全局 POSITIVE_AMOUNT CREATE 规则（草稿阶段允许零/空单价）
--      使用 UPDATE enabled=false 而非 DELETE，保留历史审计记录。
--      factory_id=NULL 是全局规则，重新通过 ON CONFLICT 处理不安全。
--   2. 不新建 STATUS_CHANGE 验证规则（服务层做更详细的行级报错，见 SalesServiceImpl）

UPDATE factory_validation_rules
SET    enabled = false
WHERE  factory_id IS NULL
  AND  module_code = 'sales_order'
  AND  rule_code   = 'POSITIVE_AMOUNT'
  AND  operation   = 'CREATE';
