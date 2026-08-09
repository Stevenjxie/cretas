-- Operations coordination source for customer-owned material received before any sales order.
-- Creating a notice never creates inventory; warehouse receipt creates the MaterialBatch.

CREATE TABLE IF NOT EXISTS customer_material_arrival_notices (
    id VARCHAR(64) PRIMARY KEY,
    factory_id VARCHAR(191) NOT NULL,
    notice_number VARCHAR(50) NOT NULL,
    customer_id VARCHAR(191) NOT NULL,
    expected_arrival_at TIMESTAMP,
    contact_name VARCHAR(100),
    contact_phone VARCHAR(50),
    remark VARCHAR(1000),
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    receipt_count INTEGER NOT NULL DEFAULT 0,
    last_received_at TIMESTAMP,
    created_by BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    deleted_at TIMESTAMP,
    CONSTRAINT uk_cman_factory_notice UNIQUE (factory_id, notice_number),
    CONSTRAINT chk_cman_status CHECK (status IN (
        'OPEN', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CANCELLED')),
    CONSTRAINT chk_cman_receipt_count CHECK (receipt_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_cman_factory_status_arrival
    ON customer_material_arrival_notices(factory_id, status, expected_arrival_at);
CREATE INDEX IF NOT EXISTS idx_cman_factory_customer
    ON customer_material_arrival_notices(factory_id, customer_id);

COMMENT ON TABLE customer_material_arrival_notices IS
    'Operations-created non-order customer material arrival source; no inventory is written until warehouse receipt';

-- Distinguish legacy/order-driven customer material from formal orders that allocate
-- customer-specific finished stock produced before a sales order existed.
ALTER TABLE sales_orders
    ADD COLUMN IF NOT EXISTS customer_stock_fulfillment_mode VARCHAR(32)
        NOT NULL DEFAULT 'ORDER_DRIVEN';

ALTER TABLE sales_orders DROP CONSTRAINT IF EXISTS chk_sales_order_customer_stock_fulfillment;
ALTER TABLE sales_orders ADD CONSTRAINT chk_sales_order_customer_stock_fulfillment
    CHECK (customer_stock_fulfillment_mode IN ('ORDER_DRIVEN', 'PRESTOCKED'));

-- Customer-specific stock production stays SAFETY_STOCK, but freezes customer ownership without a sales order.
ALTER TABLE production_plans DROP CONSTRAINT IF EXISTS chk_production_plan_supply_contract;
ALTER TABLE production_plans ADD CONSTRAINT chk_production_plan_supply_contract
    CHECK (
        processing_mode IS NULL
        OR (processing_mode = 'STANDARD_SALE'
            AND material_supply_mode = 'FACTORY_SUPPLIED'
            AND output_ownership = 'COMPANY_OWNED')
        OR (processing_mode = 'TOLL_PROCESSING'
            AND material_supply_mode = 'CUSTOMER_SUPPLIED'
            AND output_ownership = 'CUSTOMER_OWNED'
            AND customer_id IS NOT NULL
            AND (source_order_id IS NOT NULL OR source_type = 'SAFETY_STOCK'))
    );

-- A customer-owned finished batch may be produced before a formal sales order.
-- Later allocation uses fg_reservation_ledger and does not rewrite this lineage column.
ALTER TABLE finished_goods_batches DROP CONSTRAINT IF EXISTS chk_finished_goods_owner_consistency;
ALTER TABLE finished_goods_batches ADD CONSTRAINT chk_finished_goods_owner_consistency
    CHECK ((ownership IS NULL AND owner_customer_id IS NULL)
        OR (ownership = 'COMPANY_OWNED' AND owner_customer_id IS NULL)
        OR (ownership = 'CUSTOMER_OWNED' AND owner_customer_id IS NOT NULL));

-- Minimal operations role: own coordination module RW, cross-module fulfillment visibility only.
INSERT INTO platform_role_permissions (role_code, module_code, permission_level)
VALUES
    ('platform_admin',        'operations',  'rw'),
    ('factory_super_admin',   'operations',  'rw'),
    ('permission_admin',      'operations',  'rw'),
    ('operations_coordinator', 'dashboard',   'r'),
    ('operations_coordinator', 'operations',  'rw'),
    ('operations_coordinator', 'warehouse',   'r'),
    ('operations_coordinator', 'inventory',   'r'),
    ('operations_coordinator', 'production',  'r'),
    ('operations_coordinator', 'procurement', 'r'),
    ('operations_coordinator', 'sales',       'r'),
    ('operations_coordinator', 'analytics',   'r'),
    ('operations_coordinator', 'report',      'r'),
    ('operations_coordinator', 'quality',     '-'),
    ('operations_coordinator', 'finance',     '-'),
    ('operations_coordinator', 'hr',          '-'),
    ('operations_coordinator', 'equipment',   '-'),
    ('operations_coordinator', 'system',      '-'),
    ('operations_coordinator', 'scheduling',  '-'),
    ('operations_coordinator', 'work_report', '-'),
    ('operations_coordinator', 'rd',          '-'),
    ('operations_coordinator', 'restaurant',  '-')
ON CONFLICT (role_code, module_code) DO UPDATE
SET permission_level = EXCLUDED.permission_level,
    updated_at = now();
