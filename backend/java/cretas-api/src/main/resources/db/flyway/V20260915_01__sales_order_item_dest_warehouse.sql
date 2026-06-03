-- P3 多仓订单建模: 目的仓 + 叮咚扩展字段
--
-- 场景: 六扇门叮咚采购单"0601-熟食T+2" 跨 6 个目的仓 × 4 产品 = 24 行,
--       每行指定配送目的仓. 备货看板需按仓拆需求.
--
-- 表: sales_order_items — entity-mapped (Hibernate ddl-auto 可能已有列)
--   → 用 ADD COLUMN IF NOT EXISTS 防 Flyway/ddl-auto 竞态 (参考 V20260514_01 范式)
--
-- 表: sales_orders — entity-mapped
--   → 同样用 IF NOT EXISTS

ALTER TABLE sales_order_items
    ADD COLUMN IF NOT EXISTS dest_warehouse_name   VARCHAR(100) NULL,
    ADD COLUMN IF NOT EXISTS dest_warehouse_code   VARCHAR(32)  NULL,
    ADD COLUMN IF NOT EXISTS external_po_id        VARCHAR(64)  NULL,
    ADD COLUMN IF NOT EXISTS barcode               VARCHAR(64)  NULL,
    ADD COLUMN IF NOT EXISTS appointment_time      VARCHAR(50)  NULL,
    ADD COLUMN IF NOT EXISTS required_arrival_date DATE         NULL;

COMMENT ON COLUMN sales_order_items.dest_warehouse_name IS
    'P3 多仓: 目的仓全名 (如 叮咚-北仑总仓), 冗余字段方便展示. Nullable — 普通订单无目的仓.';
COMMENT ON COLUMN sales_order_items.dest_warehouse_code IS
    'P3 多仓: 目的仓 code, 备货看板按此分组. Nullable.';
COMMENT ON COLUMN sales_order_items.external_po_id IS
    'P3 多仓: 叮咚采购单ID (外部 PO 号), 跨行共享. Nullable.';
COMMENT ON COLUMN sales_order_items.barcode IS
    'P3 多仓: 商品条码 (叮咚配送用). Nullable.';
COMMENT ON COLUMN sales_order_items.appointment_time IS
    'P3 多仓: 预约入场时段字符串 (如 "8:00-12:00"), 叮咚仓库签收用. Nullable.';
COMMENT ON COLUMN sales_order_items.required_arrival_date IS
    'P3 多仓: 要求到达日期 (精细到行级, 可与 SalesOrder.requiredDeliveryDate 不同). Nullable.';

-- 备货看板按目的仓分组的核心查询索引
CREATE INDEX IF NOT EXISTS idx_soi_dest_wh
    ON sales_order_items (dest_warehouse_code);

ALTER TABLE sales_orders
    ADD COLUMN IF NOT EXISTS external_order_title VARCHAR(100) NULL;

COMMENT ON COLUMN sales_orders.external_order_title IS
    'P3 多仓: 叮咚采购单标题 (如 "0601-熟食T+2"), 客户"1单"分组键, 用于列表展示. Nullable.';
