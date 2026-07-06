-- V20261027_47__backfill_sales_order_actual_shipped_amount.sql
--
-- 背景: #1267 (2026-07-05 上线) 在 SalesServiceImpl.updateOrderDeliveryStatus 里第一次
--   把 sales_orders.actual_shipped_amount 写起来 (该字段此前定义了但从未有 writer, 详情页
--   「已发货金额」永远显示 ¥0.00, 且销售订单列表 tab 按 actualShippedAmount||0 分桶把已部分
--   /全部发货的历史订单误分进「未出库」)。该修复只对 #1267 上线后新触发的发货状态转换向前
--   生效 (shipDelivery / warehouseConfirmDelivery 调用 updateOrderDeliveryStatus 时才回写),
--   #1267 之前已发生的历史发货 (sales_order_items.delivered_quantity 已经 > 0) 没有 backfill,
--   这些订单头 actual_shipped_amount 仍是 NULL。
--
-- 本迁移: 幂等补录, 逐字镜像 SalesOrderItem.getShippedAmount() 的公式
--   (SalesServiceImpl.updateOrderDeliveryStatus 对 orderItems 求和写回 order.actualShippedAmount):
--     line_shipped = unitPrice IS NULL ? NULL (贡献 0, 不 null-传播整单)
--                    : ROUND(delivered_quantity * unit_price, 2)
--                      再 (discount_rate IS NOT NULL AND discount_rate > 0 时)
--                      × (1 - ROUND(discount_rate / 100, 6))  -- 先对折扣率单独 round 到 6 位,
--                                                                 再用 1 减, 严格对应 Java
--                                                                 BigDecimal.ONE.subtract(...)
--                                                                 的运算顺序 (不是先减再 round,
--                                                                 两者在理论上不总相等)
--                      再 ROUND(..., 2)
--   订单级 total_shipped = SUM(COALESCE(line_shipped, 0))  -- 空价行贡献 0, 不是整单跳过
--
-- 不含税: getShippedAmount() 不像 getLineAmountWithTax() 那样乘 taxRate — 严格只用
--   delivered_quantity × unit_price × (1-discount_rate/100), 无税额。
--
-- 幂等: 只更新 actual_shipped_amount IS NULL 的订单; 已有非 NULL 值 (含 #1267 之后正常写入的
--   ¥0.00, 例如全部空价行订单) 一律不动, 再跑 0 行受影响。
-- 范围: 全工厂 (含真客户 F006 六膳门), 不按 status 过滤 —— 直接以「该订单存在
--   delivered_quantity > 0 的在架行项」为准 (COMPLETED/PARTIAL_DELIVERED 是目前唯一会产生
--   已发货行的状态, 但直接从行项判定比硬编码 status 列表更贴近 writer 语义, 也覆盖发货后又被
--   取消等边缘状态)。
-- 软删过滤: sales_orders / sales_order_items 均 @Where(deleted_at IS NULL) — 迁移显式加
--   deleted_at IS NULL 守卫, 与 JPA repository 查询口径一致。

WITH item_calc AS (
    SELECT
        soi.sales_order_id AS sales_order_id,
        CASE
            WHEN soi.unit_price IS NULL THEN NULL
            WHEN soi.discount_rate IS NOT NULL AND soi.discount_rate > 0 THEN
                ROUND(
                    ROUND(soi.delivered_quantity * soi.unit_price, 2)
                    * (1 - ROUND(soi.discount_rate / 100.0, 6)),
                    2
                )
            ELSE
                ROUND(soi.delivered_quantity * soi.unit_price, 2)
        END AS line_shipped
    FROM sales_order_items soi
    WHERE soi.deleted_at IS NULL
),
order_calc AS (
    SELECT
        sales_order_id,
        SUM(COALESCE(line_shipped, 0)) AS total_shipped
    FROM item_calc
    GROUP BY sales_order_id
)
UPDATE sales_orders so
SET actual_shipped_amount = oc.total_shipped
FROM order_calc oc
WHERE so.id = oc.sales_order_id
  AND so.deleted_at IS NULL
  AND so.actual_shipped_amount IS NULL
  AND EXISTS (
      SELECT 1
      FROM sales_order_items soi2
      WHERE soi2.sales_order_id = so.id
        AND soi2.deleted_at IS NULL
        AND soi2.delivered_quantity IS NOT NULL
        AND soi2.delivered_quantity > 0
  );
