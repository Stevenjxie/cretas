CREATE TABLE IF NOT EXISTS sales_order_supplied_material_requirements (
    id VARCHAR(64) PRIMARY KEY,
    factory_id VARCHAR(191) NOT NULL,
    customer_id VARCHAR(191) NOT NULL,
    sales_order_id VARCHAR(191) NOT NULL,
    sales_order_item_id BIGINT,
    material_type_id VARCHAR(191) NOT NULL,
    material_name VARCHAR(200) NOT NULL,
    expected_quantity NUMERIC(10, 2) NOT NULL,
    received_quantity NUMERIC(10, 2) NOT NULL DEFAULT 0,
    unit VARCHAR(20) NOT NULL,
    expected_arrival_at TIMESTAMP NOT NULL,
    target_warehouse_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    CONSTRAINT fk_sosmr_factory FOREIGN KEY (factory_id) REFERENCES factories(id),
    CONSTRAINT fk_sosmr_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
    CONSTRAINT fk_sosmr_sales_order FOREIGN KEY (sales_order_id) REFERENCES sales_orders(id),
    CONSTRAINT fk_sosmr_sales_order_item FOREIGN KEY (sales_order_item_id)
        REFERENCES sales_order_items(id) ON DELETE SET NULL,
    CONSTRAINT fk_sosmr_material_type FOREIGN KEY (material_type_id) REFERENCES raw_material_types(id),
    CONSTRAINT fk_sosmr_target_warehouse FOREIGN KEY (target_warehouse_id) REFERENCES factory_warehouses(id),
    CONSTRAINT chk_sosmr_expected_quantity CHECK (expected_quantity > 0),
    CONSTRAINT chk_sosmr_received_quantity CHECK (
        received_quantity >= 0 AND received_quantity <= expected_quantity
    ),
    CONSTRAINT chk_sosmr_status CHECK (
        status IN ('PENDING', 'PARTIALLY_RECEIVED', 'COMPLETED', 'CANCELLED')
    )
);

CREATE INDEX IF NOT EXISTS idx_sosmr_factory_status_arrival
    ON sales_order_supplied_material_requirements(factory_id, status, expected_arrival_at)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_sosmr_sales_order
    ON sales_order_supplied_material_requirements(sales_order_id)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_sosmr_sales_order_item
    ON sales_order_supplied_material_requirements(sales_order_item_id)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_sosmr_material
    ON sales_order_supplied_material_requirements(material_type_id)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_sosmr_target_warehouse
    ON sales_order_supplied_material_requirements(target_warehouse_id)
    WHERE deleted_at IS NULL;

-- One active requirement row is the sole receiving-task identity for the same
-- factory/order/order-line/material tuple. COALESCE keeps order-level rows unique too.
CREATE UNIQUE INDEX IF NOT EXISTS uq_sosmr_active_identity
    ON sales_order_supplied_material_requirements(
        factory_id,
        sales_order_id,
        COALESCE(sales_order_item_id, -1),
        material_type_id
    )
    WHERE deleted_at IS NULL;

COMMENT ON TABLE sales_order_supplied_material_requirements IS
    'Customer-supplied material requirements; each row is the sole warehouse receiving-task identity';
COMMENT ON COLUMN sales_order_supplied_material_requirements.received_quantity IS
    'Cumulative quantity posted by the future warehouse receipt mutation; requirement creation never writes inventory';
COMMENT ON COLUMN sales_order_supplied_material_requirements.sales_order_item_id IS
    'Optional lineage to one finished-product sales order line';
