-- 手工回滚: V20261029_43__cleanup_liushanmen_plans_boms_workflows.sql
--
-- 按台账表 `backup_lsm_cleanup_20260801` 精确还原 —— **只还原那次迁移删的行**,
-- 库里本来就有的历史软删行不受影响(这正是当初要记台账的原因: 光靠 deleted_at
-- 区分不出「这次删的」和「早就删了的」)。
--
-- 用法:
--   psql -d cretas_prod_db -f V20261029_43__cleanup_liushanmen_plans_boms_workflows_rollback.sql
--
-- 执行后核对文末 ROLLBACK CHECK 的计数, 应回到: 计划 8 / BOM 13 / workflow 18。

BEGIN;

UPDATE process_sheet_rows SET deleted_at = NULL, updated_at = now()
WHERE id IN (SELECT object_id FROM backup_lsm_cleanup_20260801 WHERE object_type = 'process_sheet_rows');

UPDATE production_plans SET deleted_at = NULL, updated_at = now()
WHERE id IN (SELECT object_id FROM backup_lsm_cleanup_20260801 WHERE object_type = 'production_plans');

UPDATE bom_recipe_items SET deleted_at = NULL, updated_at = now()
WHERE id IN (SELECT object_id FROM backup_lsm_cleanup_20260801 WHERE object_type = 'bom_recipe_items');

UPDATE bom_recipes SET deleted_at = NULL, updated_at = now()
WHERE id IN (SELECT object_id FROM backup_lsm_cleanup_20260801 WHERE object_type = 'bom_recipes');

UPDATE workflow_task_ports SET deleted_at = NULL, updated_at = now()
WHERE id IN (SELECT object_id FROM backup_lsm_cleanup_20260801 WHERE object_type = 'workflow_task_ports');

UPDATE product_process_workflow_activations SET deleted_at = NULL, updated_at = now()
WHERE id IN (SELECT object_id FROM backup_lsm_cleanup_20260801 WHERE object_type = 'product_process_workflow_activations');

UPDATE product_process_workflow_revisions SET deleted_at = NULL, updated_at = now()
WHERE id IN (SELECT object_id FROM backup_lsm_cleanup_20260801 WHERE object_type = 'product_process_workflow_revisions');

UPDATE product_process_workflows SET deleted_at = NULL, updated_at = now()
WHERE id IN (SELECT object_id FROM backup_lsm_cleanup_20260801 WHERE object_type = 'product_process_workflows');

-- 批次单位还原: 台账把原单位编在 object_type 里 (material_batches_unit:袋 / :kg)
UPDATE material_batches b
SET quantity_unit = split_part(t.object_type, ':', 2), updated_at = now()
FROM backup_lsm_cleanup_20260801 t
WHERE t.object_id = b.id
  AND t.object_type LIKE 'material_batches_unit:%';

-- ROLLBACK CHECK: 应回到 计划 8 / BOM 13 / workflow 18
SELECT 'plans' AS t, count(*) FROM production_plans WHERE factory_id = 'LIUSHANMEN' AND deleted_at IS NULL
UNION ALL SELECT 'boms', count(*) FROM bom_recipes WHERE factory_id = 'LIUSHANMEN' AND deleted_at IS NULL
UNION ALL SELECT 'workflows', count(*) FROM product_process_workflows WHERE factory_id = 'LIUSHANMEN' AND deleted_at IS NULL
ORDER BY 1;

COMMIT;
