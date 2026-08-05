-- V20261029_53 的精确回滚。⛔ 禁止用「把所有 RESTAURANT 改回 true」——
-- 那会把本来就该停用的租户也打开。只恢复台账里记下的那些。
--
-- 注意两个 IN 子查询用的是不同类型的列: factory_id (varchar) 与 user_id (bigint)。

UPDATE factories SET is_active = true
 WHERE id IN (SELECT factory_id FROM restaurant_consolidation_ledger_20260805
               WHERE entity_kind = 'FACTORY');

UPDATE users SET is_active = true
 WHERE id IN (SELECT user_id FROM restaurant_consolidation_ledger_20260805
               WHERE entity_kind = 'USER');
