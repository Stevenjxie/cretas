ALTER TABLE sales_orders
    ADD COLUMN IF NOT EXISTS processing_mode VARCHAR(32),
    ADD COLUMN IF NOT EXISTS material_supply_mode VARCHAR(32);

ALTER TABLE sales_order_items
    ADD COLUMN IF NOT EXISTS processing_mode VARCHAR(32),
    ADD COLUMN IF NOT EXISTS material_supply_mode VARCHAR(32);

ALTER TABLE sales_orders DROP CONSTRAINT IF EXISTS chk_sales_orders_processing_mode;
ALTER TABLE sales_orders ADD CONSTRAINT chk_sales_orders_processing_mode
    CHECK (processing_mode IS NULL OR processing_mode IN ('STANDARD_SALE', 'TOLL_PROCESSING'));
ALTER TABLE sales_orders DROP CONSTRAINT IF EXISTS chk_sales_orders_material_supply_mode;
ALTER TABLE sales_orders ADD CONSTRAINT chk_sales_orders_material_supply_mode
    CHECK (material_supply_mode IS NULL OR material_supply_mode IN ('CUSTOMER_SUPPLIED', 'FACTORY_SUPPLIED'));

ALTER TABLE sales_order_items DROP CONSTRAINT IF EXISTS chk_sales_order_items_processing_mode;
ALTER TABLE sales_order_items ADD CONSTRAINT chk_sales_order_items_processing_mode
    CHECK (processing_mode IS NULL OR processing_mode IN ('STANDARD_SALE', 'TOLL_PROCESSING'));
ALTER TABLE sales_order_items DROP CONSTRAINT IF EXISTS chk_sales_order_items_material_supply_mode;
ALTER TABLE sales_order_items ADD CONSTRAINT chk_sales_order_items_material_supply_mode
    CHECK (material_supply_mode IS NULL OR material_supply_mode IN ('CUSTOMER_SUPPLIED', 'FACTORY_SUPPLIED'));

COMMENT ON COLUMN sales_orders.processing_mode IS 'STANDARD_SALE or TOLL_PROCESSING; NULL only for legacy rows';
COMMENT ON COLUMN sales_orders.material_supply_mode IS 'CUSTOMER_SUPPLIED or FACTORY_SUPPLIED; NULL only for legacy rows';
COMMENT ON COLUMN sales_order_items.processing_mode IS 'Order-line processing-mode snapshot; NULL only for legacy rows';
COMMENT ON COLUMN sales_order_items.material_supply_mode IS 'Order-line material-supply snapshot; NULL only for legacy rows';

