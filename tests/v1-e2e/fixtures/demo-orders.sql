-- =============================================================================
-- E2E Demo Sales Orders: F_E2E_TEST
-- =============================================================================
-- Inserts 3 demo sales orders (DEMO_SO_001 / 002 / 003) covering the 3 main
-- tax-rate scenarios: 9% (B2B food), 13% (e-commerce), 0% (personal).
--
-- Prerequisites: seed-e2e-factory.sql must be applied first (master data).
--
-- Idempotency: uses ON CONFLICT DO NOTHING on (factory_id, order_number).
--   Items are DELETE-then-INSERT on each run because sales_order_items has
--   no unique constraint and uses a BIGINT sequence PK.
--
-- Column notes:
--   sales_orders.order_number  — the business code field (NOT order_code)
--   sales_orders.status        — NOT order_status
--   sales_order_items.id       — BIGINT auto-sequence (no UUID)
--   created_by                 — BIGINT user.id (not UUID)
--   No sub_total/subtotal col  — total_amount computed from items at app layer
--
-- IMPORTANT: tax_rate is stored as INTEGER PERCENT (9, 13, 0), NOT decimal fraction.
--   Backend formula: taxAmount = lineAmount × taxRate / 100
--   So tax_rate=9 means 9%, producing taxAmount = 2500 × 9 / 100 = 225.00
-- =============================================================================

BEGIN;

-- ─────────────────────────────────────────────────────────────────────────────
-- Helper: resolve created_by user ID once
-- ─────────────────────────────────────────────────────────────────────────────
DO $$
DECLARE v_uid BIGINT := (SELECT id FROM users WHERE username = 'e2e_super_admin' AND factory_id = 'F_E2E_TEST');
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'e2e_super_admin user not found — run seed-e2e-factory.sql first';
    END IF;
END;
$$;

-- ─────────────────────────────────────────────────────────────────────────────
-- DEMO_SO_001: 鼎鲜火锅 — 酸菜鱼 500g × 100 @ 25.00 — tax 9% — CONFIRMED
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO sales_orders (
    id, factory_id, order_number, customer_id,
    order_date, total_amount, discount_amount, tax_amount,
    status, created_by, paid_amount,
    created_at, updated_at
)
VALUES (
    'e2e-so-001-0000000000000000000001',
    'F_E2E_TEST',
    'DEMO_SO_001',
    (SELECT id FROM customers WHERE factory_id = 'F_E2E_TEST' AND code = 'CUS_DINGXIAN'),
    CURRENT_DATE - INTERVAL '2 days',
    2500.00,
    0.00,
    225.00,  -- 2500 × 9%
    'CONFIRMED',
    (SELECT id FROM users WHERE username = 'e2e_super_admin' AND factory_id = 'F_E2E_TEST'),
    0.00,
    NOW() - INTERVAL '2 days',
    NOW() - INTERVAL '2 days'
)
ON CONFLICT (factory_id, order_number) DO NOTHING;

-- Items for DEMO_SO_001 — delete-then-insert for idempotency
DELETE FROM sales_order_items
WHERE sales_order_id = 'e2e-so-001-0000000000000000000001';

INSERT INTO sales_order_items (
    sales_order_id, product_type_id, product_name,
    quantity, unit, unit_price, tax_rate,
    discount_rate, delivered_quantity,
    created_at, updated_at
)
VALUES (
    'e2e-so-001-0000000000000000000001',
    (SELECT id FROM product_types WHERE factory_id = 'F_E2E_TEST' AND code = 'SKU_SCY500'),
    '酸菜鱼 500g',
    100.0000, '袋', 25.0000, 9,
    0.00, 0.0000,
    NOW() - INTERVAL '2 days',
    NOW() - INTERVAL '2 days'
);

-- ─────────────────────────────────────────────────────────────────────────────
-- DEMO_SO_002: 云海电商 — 鲜牛肉丸 1kg × 50 @ 55.00 — tax 13% — DRAFT
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO sales_orders (
    id, factory_id, order_number, customer_id,
    order_date, total_amount, discount_amount, tax_amount,
    status, created_by, paid_amount,
    created_at, updated_at
)
VALUES (
    'e2e-so-002-0000000000000000000001',
    'F_E2E_TEST',
    'DEMO_SO_002',
    (SELECT id FROM customers WHERE factory_id = 'F_E2E_TEST' AND code = 'CUS_YUNHAI'),
    CURRENT_DATE - INTERVAL '1 day',
    2750.00,
    0.00,
    357.50,  -- 2750 × 13%
    'DRAFT',
    (SELECT id FROM users WHERE username = 'e2e_super_admin' AND factory_id = 'F_E2E_TEST'),
    0.00,
    NOW() - INTERVAL '1 day',
    NOW() - INTERVAL '1 day'
)
ON CONFLICT (factory_id, order_number) DO NOTHING;

DELETE FROM sales_order_items
WHERE sales_order_id = 'e2e-so-002-0000000000000000000001';

INSERT INTO sales_order_items (
    sales_order_id, product_type_id, product_name,
    quantity, unit, unit_price, tax_rate,
    discount_rate, delivered_quantity,
    created_at, updated_at
)
VALUES (
    'e2e-so-002-0000000000000000000001',
    (SELECT id FROM product_types WHERE factory_id = 'F_E2E_TEST' AND code = 'SKU_NRW1K'),
    '鲜牛肉丸 1kg',
    50.0000, '袋', 55.0000, 13,
    0.00, 0.0000,
    NOW() - INTERVAL '1 day',
    NOW() - INTERVAL '1 day'
);

-- ─────────────────────────────────────────────────────────────────────────────
-- DEMO_SO_003: 张三 — 花椒鱼 300g × 10 @ 18.00 — tax 0% — CONFIRMED + fully paid
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO sales_orders (
    id, factory_id, order_number, customer_id,
    order_date, total_amount, discount_amount, tax_amount,
    status, created_by, paid_amount,
    created_at, updated_at
)
VALUES (
    'e2e-so-003-0000000000000000000001',
    'F_E2E_TEST',
    'DEMO_SO_003',
    (SELECT id FROM customers WHERE factory_id = 'F_E2E_TEST' AND code = 'CUS_ZHANGSAN'),
    CURRENT_DATE,
    180.00,
    0.00,
    0.00,  -- 0% tax
    'CONFIRMED',
    (SELECT id FROM users WHERE username = 'e2e_super_admin' AND factory_id = 'F_E2E_TEST'),
    180.00,  -- fully paid
    NOW() - INTERVAL '3 hours',
    NOW() - INTERVAL '3 hours'
)
ON CONFLICT (factory_id, order_number) DO NOTHING;

DELETE FROM sales_order_items
WHERE sales_order_id = 'e2e-so-003-0000000000000000000001';

INSERT INTO sales_order_items (
    sales_order_id, product_type_id, product_name,
    quantity, unit, unit_price, tax_rate,
    discount_rate, delivered_quantity,
    created_at, updated_at
)
VALUES (
    'e2e-so-003-0000000000000000000001',
    (SELECT id FROM product_types WHERE factory_id = 'F_E2E_TEST' AND code = 'SKU_HJY300'),
    '花椒鱼 300g',
    10.0000, '袋', 18.0000, 0,
    0.00, 0.0000,
    NOW() - INTERVAL '3 hours',
    NOW() - INTERVAL '3 hours'
);

-- ─────────────────────────────────────────────────────────────────────────────
-- DEMO_SO_G1: 鼎鲜火锅 — G1杀手锏: 9% + 13% 混合税率 — CONFIRMED
-- 行1: 酸菜鱼 500g × 100 @ 25.00 = 2500, tax_rate=9 → taxAmount=225 (2500×9/100)
-- 行2: 鲜牛肉丸 1kg × 20 @ 55.00 = 1100, tax_rate=13 → taxAmount=143 (1100×13/100)
-- Used by: tests/v1-e2e/web/g1-invoice.spec.ts
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO sales_orders (
    id, factory_id, order_number, customer_id,
    order_date, total_amount, discount_amount, tax_amount,
    status, created_by, paid_amount,
    created_at, updated_at
)
VALUES (
    'e2e-so-g1-00000000000000000000001',
    'F_E2E_TEST',
    'DEMO_SO_G1',
    (SELECT id FROM customers WHERE factory_id = 'F_E2E_TEST' AND code = 'CUS_DINGXIAN'),
    CURRENT_DATE - INTERVAL '1 day',
    3600.00,      -- 2500 + 1100 (ex-tax)
    0.00,
    0.00,         -- tax_amount stored on items, not order-level here
    -- 2026-04-26 (commit 2bbc354ce, OrderUsageWhitelists.java:54): SO_INVOICEABLE
    -- whitelist tightened to {FINANCE_APPROVED, PROCESSING, PARTIAL_DELIVERED,
    -- COMPLETED}. CONFIRMED was removed. G1 invoice test creates an invoice
    -- against DEMO_SO_G1, so seed status must satisfy the new whitelist.
    'FINANCE_APPROVED',
    (SELECT id FROM users WHERE username = 'e2e_super_admin' AND factory_id = 'F_E2E_TEST'),
    0.00,
    NOW() - INTERVAL '1 day',
    NOW() - INTERVAL '1 day'
)
ON CONFLICT (factory_id, order_number) DO NOTHING;

-- Items for DEMO_SO_G1 — delete-then-insert for idempotency
DELETE FROM sales_order_items
WHERE sales_order_id = 'e2e-so-g1-00000000000000000000001';

INSERT INTO sales_order_items (
    sales_order_id, product_type_id, product_name,
    quantity, unit, unit_price, tax_rate,
    discount_rate, delivered_quantity,
    created_at, updated_at
)
VALUES
(
    'e2e-so-g1-00000000000000000000001',
    (SELECT id FROM product_types WHERE factory_id = 'F_E2E_TEST' AND code = 'SKU_SCY500'),
    '酸菜鱼 500g',
    100.0000, '袋', 25.0000, 9,
    0.00, 0.0000,
    NOW() - INTERVAL '1 day',
    NOW() - INTERVAL '1 day'
),
(
    'e2e-so-g1-00000000000000000000001',
    (SELECT id FROM product_types WHERE factory_id = 'F_E2E_TEST' AND code = 'SKU_NRW1K'),
    '鲜牛肉丸 1kg',
    20.0000, '袋', 55.0000, 13,
    0.00, 0.0000,
    NOW() - INTERVAL '1 day',
    NOW() - INTERVAL '1 day'
);

COMMIT;
