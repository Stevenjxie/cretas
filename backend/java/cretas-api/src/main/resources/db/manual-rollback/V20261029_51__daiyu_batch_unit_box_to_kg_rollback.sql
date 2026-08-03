-- =============================================================================
-- V20261029_51 回滚: 带鱼两条批次的单位 kg → 箱
--
-- 用法 (手动执行, 不由 Flyway 跑):
--   sudo -u postgres psql -d cretas_prod_db \
--     -f V20261029_51__daiyu_batch_unit_box_to_kg_rollback.sql
--
-- ⚠️ 只回滚「当前值仍是 kg」的那两行 —— 之后被人改过的不覆盖。
-- ⚠️ 数量未被迁移改动(只改了单位字面值), 所以回滚也不动数量。
-- =============================================================================

\echo '--- 回滚前 ---'
SELECT factory_id, batch_number, quantity_unit, receipt_quantity, status
FROM material_batches
WHERE batch_number = 'MB-TEST-20260102-001' AND factory_id IN ('F001','DEMO_FACTORY');

UPDATE material_batches
SET quantity_unit = '箱', updated_at = NOW()
WHERE batch_number = 'MB-TEST-20260102-001'
  AND factory_id IN ('F001','DEMO_FACTORY')
  AND quantity_unit = 'kg';

\echo '--- 回滚后 ---'
SELECT factory_id, batch_number, quantity_unit, receipt_quantity, status
FROM material_batches
WHERE batch_number = 'MB-TEST-20260102-001' AND factory_id IN ('F001','DEMO_FACTORY');
