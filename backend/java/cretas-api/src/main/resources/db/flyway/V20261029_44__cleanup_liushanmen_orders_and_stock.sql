-- 六膳门(LIUSHANMEN)清场第二步: 删订单与库存, 只留单位逻辑 / SKU / 原料字典。
-- Steve 2026-08-01:「就留单位逻辑 sku 原料字典, 其他安排的订单和工序workflow全部删除」
--
-- 第一步 V20261029_43 已删: 生产计划 / BOM 配方 / 工序 workflow。
-- 本步补删剩下的交易数据。
--
-- ## ⛔ 保留 (Steve 点名的三项 + 支撑它们的东西)
--   raw_material_types(229)  —— 原料字典
--   product_types(152)       —— SKU
--   unit_of_measurements     —— 单位逻辑(且六膳门没有工厂私有单位, 全走系统级 '*')
--   factory_warehouses(7)    —— 仓库主数据, 不是订单也不是库存, 留着重建才有地方放
--
-- ## 删除清单 (全部软删, 逐 id 记台账)
--   sales_orders 2 (+ items 2)
--   purchase_orders 6 (+ items 6)
--   material_batches 27          ← 库存批次
--   finished_goods_batches 2     ← 成品批次
--   factory_stocktakes 2 (+ items)
--   (sales_delivery_records / logistics_delivery_orders 实测已是 0, 无需处理)
--
-- ## 为什么库存也删
-- 库存是被删掉的那些订单/工序产出的**衍生数据**。留着就会重演 Steve 今天指出的问题
-- ——「库存里还有已经删掉的产品」, 单据引用一个不存在的东西, 页面看着正常、
-- 一到发货才炸(见 SO-20260709-0001)。清场就要清干净。
--
-- ## 台账与回滚
-- 沿用 V20261029_43 建的 `backup_lsm_cleanup_20260801`(CREATE TABLE IF NOT EXISTS 幂等),
-- object_type 前缀 `v44:` 与第一步区分, 回滚脚本按前缀精确还原 ——
-- **只还原这次删的**, 不碰库里原有的历史软删行。
--
-- ## 幂等
-- 每条 UPDATE 都带 `deleted_at IS NULL`; 台账 ON CONFLICT DO NOTHING。

CREATE TABLE IF NOT EXISTS backup_lsm_cleanup_20260801 (
    object_type varchar(64)  NOT NULL,
    object_id   varchar(64)  NOT NULL,
    recorded_at timestamp    NOT NULL DEFAULT now(),
    PRIMARY KEY (object_type, object_id)
);

-- ---------- 台账 ----------
INSERT INTO backup_lsm_cleanup_20260801 (object_type, object_id)
SELECT 'v44:sales_order_items', i.id FROM sales_order_items i
JOIN sales_orders o ON o.id = i.sales_order_id
WHERE o.factory_id = 'LIUSHANMEN' AND i.deleted_at IS NULL
ON CONFLICT DO NOTHING;

INSERT INTO backup_lsm_cleanup_20260801 (object_type, object_id)
SELECT 'v44:sales_orders', id FROM sales_orders
WHERE factory_id = 'LIUSHANMEN' AND deleted_at IS NULL
ON CONFLICT DO NOTHING;

INSERT INTO backup_lsm_cleanup_20260801 (object_type, object_id)
SELECT 'v44:purchase_order_items', i.id FROM purchase_order_items i
JOIN purchase_orders o ON o.id = i.purchase_order_id
WHERE o.factory_id = 'LIUSHANMEN' AND i.deleted_at IS NULL
ON CONFLICT DO NOTHING;

INSERT INTO backup_lsm_cleanup_20260801 (object_type, object_id)
SELECT 'v44:purchase_orders', id FROM purchase_orders
WHERE factory_id = 'LIUSHANMEN' AND deleted_at IS NULL
ON CONFLICT DO NOTHING;

INSERT INTO backup_lsm_cleanup_20260801 (object_type, object_id)
SELECT 'v44:material_batches', id FROM material_batches
WHERE factory_id = 'LIUSHANMEN' AND deleted_at IS NULL
ON CONFLICT DO NOTHING;

INSERT INTO backup_lsm_cleanup_20260801 (object_type, object_id)
SELECT 'v44:finished_goods_batches', id FROM finished_goods_batches
WHERE factory_id = 'LIUSHANMEN' AND deleted_at IS NULL
ON CONFLICT DO NOTHING;

INSERT INTO backup_lsm_cleanup_20260801 (object_type, object_id)
SELECT 'v44:factory_stocktake_items', i.id FROM factory_stocktake_items i
JOIN factory_stocktakes s ON s.id = i.stocktake_id
WHERE s.factory_id = 'LIUSHANMEN' AND i.deleted_at IS NULL
ON CONFLICT DO NOTHING;

INSERT INTO backup_lsm_cleanup_20260801 (object_type, object_id)
SELECT 'v44:factory_stocktakes', id FROM factory_stocktakes
WHERE factory_id = 'LIUSHANMEN' AND deleted_at IS NULL
ON CONFLICT DO NOTHING;

-- ---------- 软删 (子表先于主表) ----------
UPDATE sales_order_items i SET deleted_at = now(), updated_at = now()
FROM sales_orders o
WHERE o.id = i.sales_order_id AND o.factory_id = 'LIUSHANMEN' AND i.deleted_at IS NULL;

UPDATE sales_orders SET deleted_at = now(), updated_at = now()
WHERE factory_id = 'LIUSHANMEN' AND deleted_at IS NULL;

UPDATE purchase_order_items i SET deleted_at = now(), updated_at = now()
FROM purchase_orders o
WHERE o.id = i.purchase_order_id AND o.factory_id = 'LIUSHANMEN' AND i.deleted_at IS NULL;

UPDATE purchase_orders SET deleted_at = now(), updated_at = now()
WHERE factory_id = 'LIUSHANMEN' AND deleted_at IS NULL;

UPDATE factory_stocktake_items i SET deleted_at = now(), updated_at = now()
FROM factory_stocktakes s
WHERE s.id = i.stocktake_id AND s.factory_id = 'LIUSHANMEN' AND i.deleted_at IS NULL;

UPDATE factory_stocktakes SET deleted_at = now(), updated_at = now()
WHERE factory_id = 'LIUSHANMEN' AND deleted_at IS NULL;

UPDATE finished_goods_batches SET deleted_at = now(), updated_at = now()
WHERE factory_id = 'LIUSHANMEN' AND deleted_at IS NULL;

UPDATE material_batches SET deleted_at = now(), updated_at = now()
WHERE factory_id = 'LIUSHANMEN' AND deleted_at IS NULL;
