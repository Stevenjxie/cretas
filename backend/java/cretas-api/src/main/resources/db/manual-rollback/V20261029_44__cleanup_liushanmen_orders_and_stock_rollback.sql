-- 手工回滚: V20261029_44__cleanup_liushanmen_orders_and_stock.sql
--
-- 按台账表 `backup_lsm_cleanup_20260801` 中 **`v44:` 前缀**的行精确还原 ——
-- 只还原第二步删的行。第一步 V20261029_43 的台账没有前缀(`production_plans` 等裸表名),
-- 所以两步互不干扰: 跑本脚本不会把第一步删掉的计划/BOM/workflow 一起放回来,
-- 库里本来就有的历史软删行也不受影响。
--
-- 用法:
--   scp V20261029_44__cleanup_liushanmen_orders_and_stock_rollback.sql root@47.100.235.168:/tmp/
--   ssh root@47.100.235.168 "chmod 644 /tmp/V20261029_44__*_rollback.sql && \
--     su - postgres -c 'psql -d cretas_prod_db -f /tmp/V20261029_44__cleanup_liushanmen_orders_and_stock_rollback.sql'"
--
-- 执行后核对文末 ROLLBACK CHECK, 应回到:
--   sales_orders 2 / sales_order_items 2 / purchase_orders 6 / purchase_order_items 6 /
--   material_batches 27 / finished_goods_batches 2 / factory_stocktakes 2 / factory_stocktake_items 3
--
-- 注: SO-20260801-0001(在办审批单)本来就没被删, 不在台账里, 因此回滚前后它都是 1 张;
--     上面 sales_orders 2 = 它 + 被还原的 SO-20260709-0001。

BEGIN;

-- ---------- 主表先于子表 (与删除顺序相反) ----------
UPDATE sales_orders SET deleted_at = NULL, updated_at = now()
WHERE id IN (SELECT object_id FROM backup_lsm_cleanup_20260801 WHERE object_type = 'v44:sales_orders');

-- ⚠️ sales_order_items.id / purchase_order_items.id 是 bigint, 而台账 object_id 是 varchar(64)。
-- 必须显式 ::varchar 比较, 否则 PG 报 `operator does not exist: bigint = character varying`。
-- (其余 6 张表的 id 本身就是 varchar, 直接比。干跑时实测抓到的。)
UPDATE sales_order_items SET deleted_at = NULL, updated_at = now()
WHERE id::varchar IN (SELECT object_id FROM backup_lsm_cleanup_20260801 WHERE object_type = 'v44:sales_order_items');

UPDATE purchase_orders SET deleted_at = NULL, updated_at = now()
WHERE id IN (SELECT object_id FROM backup_lsm_cleanup_20260801 WHERE object_type = 'v44:purchase_orders');

UPDATE purchase_order_items SET deleted_at = NULL, updated_at = now()
WHERE id::varchar IN (SELECT object_id FROM backup_lsm_cleanup_20260801 WHERE object_type = 'v44:purchase_order_items');

UPDATE factory_stocktakes SET deleted_at = NULL, updated_at = now()
WHERE id IN (SELECT object_id FROM backup_lsm_cleanup_20260801 WHERE object_type = 'v44:factory_stocktakes');

UPDATE factory_stocktake_items SET deleted_at = NULL, updated_at = now()
WHERE id IN (SELECT object_id FROM backup_lsm_cleanup_20260801 WHERE object_type = 'v44:factory_stocktake_items');

UPDATE finished_goods_batches SET deleted_at = NULL, updated_at = now()
WHERE id IN (SELECT object_id FROM backup_lsm_cleanup_20260801 WHERE object_type = 'v44:finished_goods_batches');

UPDATE material_batches SET deleted_at = NULL, updated_at = now()
WHERE id IN (SELECT object_id FROM backup_lsm_cleanup_20260801 WHERE object_type = 'v44:material_batches');

-- ROLLBACK CHECK: 应回到 2 / 2 / 6 / 6 / 27 / 2 / 2 / 3
SELECT 'sales_orders' AS t, count(*) FROM sales_orders WHERE factory_id = 'LIUSHANMEN' AND deleted_at IS NULL
UNION ALL SELECT 'sales_order_items', count(*) FROM sales_order_items i JOIN sales_orders o ON o.id = i.sales_order_id WHERE o.factory_id = 'LIUSHANMEN' AND i.deleted_at IS NULL
UNION ALL SELECT 'purchase_orders', count(*) FROM purchase_orders WHERE factory_id = 'LIUSHANMEN' AND deleted_at IS NULL
UNION ALL SELECT 'purchase_order_items', count(*) FROM purchase_order_items i JOIN purchase_orders o ON o.id = i.purchase_order_id WHERE o.factory_id = 'LIUSHANMEN' AND i.deleted_at IS NULL
UNION ALL SELECT 'material_batches', count(*) FROM material_batches WHERE factory_id = 'LIUSHANMEN' AND deleted_at IS NULL
UNION ALL SELECT 'finished_goods_batches', count(*) FROM finished_goods_batches WHERE factory_id = 'LIUSHANMEN' AND deleted_at IS NULL
UNION ALL SELECT 'factory_stocktakes', count(*) FROM factory_stocktakes WHERE factory_id = 'LIUSHANMEN' AND deleted_at IS NULL
UNION ALL SELECT 'factory_stocktake_items', count(*) FROM factory_stocktake_items i JOIN factory_stocktakes s ON s.id = i.stocktake_id WHERE s.factory_id = 'LIUSHANMEN' AND i.deleted_at IS NULL
ORDER BY 1;

COMMIT;
