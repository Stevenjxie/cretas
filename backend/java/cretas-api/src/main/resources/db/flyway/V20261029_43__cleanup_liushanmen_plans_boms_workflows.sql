-- 清空六膳门(LIUSHANMEN)的生产计划 / BOM 配方 / 工序 workflow, 为重建业务腾干净场地。
-- Steve 2026-08-01: 「清理掉所有生产计划 bom 还有工序workflow」
--
-- ⛔ **不动主数据和库存**: raw_material_types(228) / product_types(151) /
--    material_batches(27) / factory_warehouses(7) 全部保留 —— 它们是重建的基础。
--
-- ## 全部用软删, 因此可逆
-- 涉及的 9 张表都有 deleted_at。**但只有软删还不够**: 库里本来就有之前删掉的行
-- (13 条 BOM 里只有 7 条是生效的), 事后无法区分「这次删的」和「早就删了的」。
-- 所以先把本次要删的 id 落进 backup 台账表, 回滚时按台账精确还原。
--
-- 台账表命名沿用本仓既有做法(repair_backup_liushanmen_batch_cost_20260627 等)。
--
-- ## 幂等
-- 每条 UPDATE 都带 `deleted_at IS NULL`; 台账表用 CREATE TABLE IF NOT EXISTS +
-- 只插入尚未记录的 id, 重复执行不会重复记账, 也不会把「后来又恢复的行」再删一次。
--
-- ## 回滚
-- 见 db/manual-rollback/V20261029_43__..._rollback.sql —— 按台账逐表 UPDATE ... SET deleted_at = NULL。

-- ---------- 1. 台账: 记下本次要软删的 id ----------
CREATE TABLE IF NOT EXISTS backup_lsm_cleanup_20260801 (
    object_type varchar(64)  NOT NULL,
    object_id   varchar(64)  NOT NULL,
    recorded_at timestamp    NOT NULL DEFAULT now(),
    PRIMARY KEY (object_type, object_id)
);

INSERT INTO backup_lsm_cleanup_20260801 (object_type, object_id)
SELECT 'production_plans', id FROM production_plans
WHERE factory_id = 'LIUSHANMEN' AND deleted_at IS NULL
ON CONFLICT DO NOTHING;

INSERT INTO backup_lsm_cleanup_20260801 (object_type, object_id)
SELECT 'process_sheet_rows', r.id FROM process_sheet_rows r
WHERE r.deleted_at IS NULL
  AND r.plan_id IN (SELECT id FROM production_plans WHERE factory_id = 'LIUSHANMEN')
ON CONFLICT DO NOTHING;

INSERT INTO backup_lsm_cleanup_20260801 (object_type, object_id)
SELECT 'bom_recipes', id FROM bom_recipes
WHERE factory_id = 'LIUSHANMEN' AND deleted_at IS NULL
ON CONFLICT DO NOTHING;

INSERT INTO backup_lsm_cleanup_20260801 (object_type, object_id)
SELECT 'bom_recipe_items', i.id FROM bom_recipe_items i
WHERE i.deleted_at IS NULL
  AND i.recipe_id IN (SELECT id FROM bom_recipes WHERE factory_id = 'LIUSHANMEN')
ON CONFLICT DO NOTHING;

INSERT INTO backup_lsm_cleanup_20260801 (object_type, object_id)
SELECT 'product_process_workflows', id FROM product_process_workflows
WHERE factory_id = 'LIUSHANMEN' AND deleted_at IS NULL
ON CONFLICT DO NOTHING;

INSERT INTO backup_lsm_cleanup_20260801 (object_type, object_id)
SELECT 'product_process_workflow_revisions', id FROM product_process_workflow_revisions
WHERE factory_id = 'LIUSHANMEN' AND deleted_at IS NULL
ON CONFLICT DO NOTHING;

INSERT INTO backup_lsm_cleanup_20260801 (object_type, object_id)
SELECT 'product_process_workflow_activations', id FROM product_process_workflow_activations
WHERE factory_id = 'LIUSHANMEN' AND deleted_at IS NULL
ON CONFLICT DO NOTHING;

INSERT INTO backup_lsm_cleanup_20260801 (object_type, object_id)
SELECT 'workflow_task_ports', id FROM workflow_task_ports
WHERE factory_id = 'LIUSHANMEN' AND deleted_at IS NULL
ON CONFLICT DO NOTHING;

-- ---------- 2. 软删 (顺序: 子表先于主表, 便于事后核对) ----------

UPDATE process_sheet_rows SET deleted_at = now(), updated_at = now()
WHERE deleted_at IS NULL
  AND plan_id IN (SELECT id FROM production_plans WHERE factory_id = 'LIUSHANMEN');

UPDATE production_plans SET deleted_at = now(), updated_at = now()
WHERE factory_id = 'LIUSHANMEN' AND deleted_at IS NULL;

UPDATE bom_recipe_items SET deleted_at = now(), updated_at = now()
WHERE deleted_at IS NULL
  AND recipe_id IN (SELECT id FROM bom_recipes WHERE factory_id = 'LIUSHANMEN');

UPDATE bom_recipes SET deleted_at = now(), updated_at = now()
WHERE factory_id = 'LIUSHANMEN' AND deleted_at IS NULL;

UPDATE workflow_task_ports SET deleted_at = now(), updated_at = now()
WHERE factory_id = 'LIUSHANMEN' AND deleted_at IS NULL;

UPDATE product_process_workflow_activations SET deleted_at = now(), updated_at = now()
WHERE factory_id = 'LIUSHANMEN' AND deleted_at IS NULL;

UPDATE product_process_workflow_revisions SET deleted_at = now(), updated_at = now()
WHERE factory_id = 'LIUSHANMEN' AND deleted_at IS NULL;

UPDATE product_process_workflows SET deleted_at = now(), updated_at = now()
WHERE factory_id = 'LIUSHANMEN' AND deleted_at IS NULL;

-- ---------- 3. 顺带: 把批次单位对齐到物料自己的单位 ----------
-- Steve 2026-08-01 要求「单位都是正确的」。清理完之后**库存是唯一还留着单位错误的地方**。
--
-- 现状(prod 实测): 只有 1 个物料出问题 —— `YL-元益漫-黄油鸡`(unit=只) 的 6 个批次:
--   3 条标「袋」, 剩余合计 801  ← 真库存
--   3 条标「kg」, 剩余 0        ← 空批次, 无害
-- 全是 `WIP-CLK-W-*`(报工产生的半成品批次), 单位取的是当时 workflow 端口的写法,
-- 而不是物料自己的单位。产生它们的 workflow 正在被本迁移清掉。
--
-- 判据与 V20261029_32 第 2 段一致: **批次单位应当等于该物料自己的单位**。
--
-- ⚠️ **明确记录这里的假设**: 只改单位标签, **不动数量**(401 袋 → 401 只)。
-- 前提是 1 袋 = 1 只 —— 对「整只鸡」这种主材成立。若日后发现某物料 1 袋装 N 只,
-- 那类要单独换算, 不能靠这条。因此本次**限定在 LIUSHANMEN**, 不做全租户扫。
--
-- 台账同样记账, 可精确回滚。

INSERT INTO backup_lsm_cleanup_20260801 (object_type, object_id)
SELECT 'material_batches_unit:' || b.quantity_unit, b.id
FROM material_batches b JOIN raw_material_types r ON r.id = b.material_type_id
WHERE b.factory_id = 'LIUSHANMEN' AND b.deleted_at IS NULL
  AND b.quantity_unit IS DISTINCT FROM r.unit
ON CONFLICT DO NOTHING;

UPDATE material_batches b
SET quantity_unit = r.unit, updated_at = now()
FROM raw_material_types r
WHERE r.id = b.material_type_id
  AND b.factory_id = 'LIUSHANMEN'
  AND b.deleted_at IS NULL
  AND b.quantity_unit IS DISTINCT FROM r.unit;
