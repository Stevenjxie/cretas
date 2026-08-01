-- 手工回滚: V20261029_45__void_f001_dangling_product_references.sql
--
-- 按台账表 `backup_f001_dangling_20260801` 中 `v45:` 前缀的行精确还原 ——
-- **只还原本次作废的 28 行**, 库里原有的历史软删行不受影响。
--
-- 用法:
--   scp V20261029_45__void_f001_dangling_product_references_rollback.sql root@47.100.235.168:/tmp/
--   ssh root@47.100.235.168 "chmod 644 /tmp/V20261029_45__*_rollback.sql && \
--     su - postgres -c 'psql -d cretas_prod_db -f /tmp/V20261029_45__void_f001_dangling_product_references_rollback.sql'"
--
-- 执行后核对文末 ROLLBACK CHECK, 应回到:
--   sales_order_items 18 / finished_goods_batches 9 / bom_recipes 1  (均为未软删)
--
-- ⚠️ 回滚只把行放回来, **不会**重建那些被硬删的 product_types(PT-001 / PT-002 /
--    PT003 / 19a68893-… / b7d5657b-… / cfc1a365-…)。也就是说回滚后这些行**仍然是悬空的**,
--    回到作废前的状态 —— 这正是回滚该做的事(还原, 不是修复)。

BEGIN;

-- ⚠️ sales_order_items.id 是 bigint, 而台账 object_id 是 varchar(64)。
-- 必须显式 ::varchar 比较, 否则 PG 报 `operator does not exist: bigint = character varying`。
-- 这个错**只在回滚时出现** —— 写台账的 INSERT 有隐式转换, 部署/验收全看不见。
-- (另两张表的 id 本身就是 varchar, 直接比。)
UPDATE sales_order_items SET deleted_at = NULL, updated_at = now()
WHERE id::varchar IN (
    SELECT object_id FROM backup_f001_dangling_20260801
    WHERE object_type = 'v45:sales_order_items');

UPDATE bom_recipes SET deleted_at = NULL, updated_at = now()
WHERE id IN (
    SELECT object_id FROM backup_f001_dangling_20260801
    WHERE object_type = 'v45:bom_recipes');

UPDATE finished_goods_batches SET deleted_at = NULL, updated_at = now()
WHERE id IN (
    SELECT object_id FROM backup_f001_dangling_20260801
    WHERE object_type = 'v45:finished_goods_batches');

-- ---------- ROLLBACK CHECK ----------
\echo '===== 回滚后应为: sales_order_items 18 / finished_goods_batches 9 / bom_recipes 1 ====='
SELECT 'sales_order_items' AS tbl, count(*) AS restored
FROM sales_order_items i
WHERE i.deleted_at IS NULL
  AND i.id::varchar IN (SELECT object_id FROM backup_f001_dangling_20260801
                        WHERE object_type = 'v45:sales_order_items')
UNION ALL
SELECT 'finished_goods_batches', count(*)
FROM finished_goods_batches b
WHERE b.deleted_at IS NULL
  AND b.id IN (SELECT object_id FROM backup_f001_dangling_20260801
               WHERE object_type = 'v45:finished_goods_batches')
UNION ALL
SELECT 'bom_recipes', count(*)
FROM bom_recipes r
WHERE r.deleted_at IS NULL
  AND r.id IN (SELECT object_id FROM backup_f001_dangling_20260801
               WHERE object_type = 'v45:bom_recipes');

COMMIT;
