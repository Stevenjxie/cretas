-- V20260603_01: F006 test environment seed (issue #538)
-- Initial draft used V20260516_09 but renamed because spring.flyway.out-of-order=false
-- silently skips any migration whose version is earlier than the latest applied
-- (V20260601_05). Using a date > V20260601_* ensures Flyway picks this up on next
-- deploy. Migration is idempotent — safe even if seed was already applied directly
-- via psql to test DB on 2026-05-16 (which is what happened for issue #538 fix).
--
-- Background: F006 coverage push reached scope limit at 74.5% verified state.
-- 3 unverified asks are test-env-blocked because they involve write operations:
--   T4-B3 stock-insufficient four-tuple (production plan + start)
--   T4-D5 WH-LOG outbound (sales order fulfillment)
--   T3-14 three-price comparison refresh (procurement order creation)
--
-- Test DB (cretas_db) verified 2026-05-16 has F006 factory + 20 customers +
-- 3 of 16 users (admin/worker1/workshop), but is missing: 13 users
-- (warehouse_mgr, procurement_mgr, production_mgr, sales_mgr, finance_mgr, etc.),
-- 0 suppliers, 0 BOMs, 0 product_types, 0 material_batches,
-- 0 sales_orders, 0 purchase_orders.
--
-- This migration seeds the gaps. Idempotent:
--   - Users: ON CONFLICT (username) DO NOTHING (safe on both test and prod;
--     prod already has all 16 via V20260429_01, so this is a no-op there).
--   - Sample biz data: wrapped in DO block guarded by current_database()='cretas_db'
--     so prod (cretas_prod_db) is never seeded with fabricated test rows.
--
-- ============================================================
-- STEP 1: Missing F006 users (13)
-- Password = '123456' (bcrypt hash matches existing f006_admin in test DB).
-- Position values mirror V20260429_01 role-suffix conventions.
-- ============================================================

INSERT INTO users (
  username, password_hash, factory_id, full_name, phone, department,
  position, role_code, is_active, level, platform_type, created_at, updated_at
) VALUES
  ('f006_finance_mgr',     '$2a$10$7T94UobpRg9lv0tfT5vGs.pvKkFvCWkWivHqKTWQSYdbdZfEmwHHS', 'F006', 'F006 财务主管',   '13912345601', 'finance',           'finance_manager',     'finance_manager',     true, 5,  'web,mobile', NOW(), NOW()),
  ('f006_production_mgr',  '$2a$10$7T94UobpRg9lv0tfT5vGs.pvKkFvCWkWivHqKTWQSYdbdZfEmwHHS', 'F006', 'F006 生产主管',   '13912345602', 'production',        'production_manager',  'production_manager',  true, 5,  'web,mobile', NOW(), NOW()),
  ('f006_procurement_mgr', '$2a$10$7T94UobpRg9lv0tfT5vGs.pvKkFvCWkWivHqKTWQSYdbdZfEmwHHS', 'F006', 'F006 采购主管',   '13912345603', 'procurement',       'procurement_manager', 'procurement_manager', true, 5,  'web,mobile', NOW(), NOW()),
  ('f006_warehouse_mgr',   '$2a$10$7T94UobpRg9lv0tfT5vGs.pvKkFvCWkWivHqKTWQSYdbdZfEmwHHS', 'F006', 'F006 仓储主管',   '13912345604', 'logistics',         'warehouse_manager',   'warehouse_manager',   true, 5,  'web,mobile', NOW(), NOW()),
  ('f006_warehouse_worker','$2a$10$7T94UobpRg9lv0tfT5vGs.pvKkFvCWkWivHqKTWQSYdbdZfEmwHHS', 'F006', 'F006 仓储员',     '13912345605', 'logistics',         'warehouse_worker',    'warehouse_worker',    true, 10, 'web,mobile', NOW(), NOW()),
  ('f006_sales_mgr',       '$2a$10$7T94UobpRg9lv0tfT5vGs.pvKkFvCWkWivHqKTWQSYdbdZfEmwHHS', 'F006', 'F006 销售主管',   '13912345606', 'sales',             'sales_manager',       'sales_manager',       true, 5,  'web,mobile', NOW(), NOW()),
  ('f006_quality_mgr',     '$2a$10$7T94UobpRg9lv0tfT5vGs.pvKkFvCWkWivHqKTWQSYdbdZfEmwHHS', 'F006', 'F006 品控主管',   '13912345607', 'quality',           'quality_manager',     'quality_manager',     true, 5,  'web,mobile', NOW(), NOW()),
  ('f006_quality_insp',    '$2a$10$7T94UobpRg9lv0tfT5vGs.pvKkFvCWkWivHqKTWQSYdbdZfEmwHHS', 'F006', 'F006 品控员',     '13912345608', 'quality',           'quality_inspector',   'quality_inspector',   true, 10, 'web,mobile', NOW(), NOW()),
  ('f006_equipment_admin', '$2a$10$7T94UobpRg9lv0tfT5vGs.pvKkFvCWkWivHqKTWQSYdbdZfEmwHHS', 'F006', 'F006 设备管理员', '13912345609', 'equipment',         'equipment_admin',     'equipment_admin',     true, 10, 'web,mobile', NOW(), NOW()),
  ('f006_hr_admin',        '$2a$10$7T94UobpRg9lv0tfT5vGs.pvKkFvCWkWivHqKTWQSYdbdZfEmwHHS', 'F006', 'F006 HR管理员',   '13912345610', 'hr',                'hr_admin',            'hr_admin',            true, 10, 'web,mobile', NOW(), NOW()),
  ('f006_viewer',          '$2a$10$7T94UobpRg9lv0tfT5vGs.pvKkFvCWkWivHqKTWQSYdbdZfEmwHHS', 'F006', 'F006 查看者',     '13912345611', 'general',           'viewer',              'viewer',              true, 20, 'web,mobile', NOW(), NOW()),
  ('f006_dispatcher',      '$2a$10$7T94UobpRg9lv0tfT5vGs.pvKkFvCWkWivHqKTWQSYdbdZfEmwHHS', 'F006', 'F006 调度员',     '13912345612', 'production',        'dispatcher',          'dispatcher',          true, 10, 'web,mobile', NOW(), NOW()),
  ('f006_dept_admin',      '$2a$10$7T94UobpRg9lv0tfT5vGs.pvKkFvCWkWivHqKTWQSYdbdZfEmwHHS', 'F006', 'F006 部门管理员', '13912345613', 'general',           'department_admin',    'department_admin',    true, 5,  'web,mobile', NOW(), NOW())
ON CONFLICT (username) DO NOTHING;

-- ============================================================
-- STEP 2: Sample business data (test DB only)
-- All inserts guarded by current_database()='cretas_db' so prod is never touched.
-- ============================================================

DO $$
DECLARE
  v_admin_id  BIGINT;
  v_wh_log    VARCHAR := '3523a235-f063-473c-ad1d-9725ace63752';  -- 物流仓 WH-LOG
  v_wh_wks    VARCHAR := 'd5914dcb-c797-42dd-964c-da639e1375aa';  -- 鲜棉仓 WH-WKS
  v_cust_id   VARCHAR;
  v_now       TIMESTAMP := NOW();
  v_today     DATE := CURRENT_DATE;
BEGIN
  IF current_database() <> 'cretas_db' THEN
    RAISE NOTICE 'V20260603_01: skipped sample-data block on %', current_database();
    RETURN;
  END IF;

  SELECT id INTO v_admin_id FROM users WHERE username = 'f006_admin' LIMIT 1;
  SELECT id INTO v_cust_id  FROM customers WHERE factory_id = 'F006' ORDER BY created_at LIMIT 1;

  -- Class 4 sibling fix (2026-05-21): migration was designed assuming psql
  -- pre-seeded f006_admin user + F006 customer (issue #538 fix on 2026-05-16),
  -- but fresh CI DBs lack this prior state. RAISE NOTICE + RETURN instead of
  -- RAISE EXCEPTION makes the seed block skip cleanly when prerequisites are
  -- absent rather than failing Spring Boot's flywayInitializer chain.
  IF v_admin_id IS NULL OR v_cust_id IS NULL THEN
    RAISE NOTICE 'V20260603_01: skipping sample-data block (prerequisites missing: admin_id=% cust_id=%)', v_admin_id, v_cust_id;
    RETURN;
  END IF;

  -- 2a. raw_material_types (2 more; 1 RMT-F006-001 already exists)
  INSERT INTO raw_material_types (
    id, code, factory_id, name, unit, created_by, is_active, unit_price,
    min_stock, max_stock, is_abaca_packaging, created_at, updated_at
  ) VALUES
    ('RMT-F006-TEST-002', 'TEST002', 'F006', '测试调味料', 'kg', v_admin_id, true, 15.00, 10,  500,   false, v_now, v_now),
    ('RMT-F006-TEST-003', 'TEST003', 'F006', '测试包装',   '个', v_admin_id, true,  2.00, 100, 10000, false, v_now, v_now)
  ON CONFLICT (factory_id, code) DO NOTHING;

  -- 2b. suppliers (2)
  INSERT INTO suppliers (
    id, code, supplier_code, name, factory_id, created_by, is_active, version,
    contact_phone, contact_person, payment_terms, created_at, updated_at
  ) VALUES
    ('SUP-F006-TEST-001', 'SUP-TEST-001', 'SUP-TEST-001', '测试供应商甲', 'F006', v_admin_id, true, 0, '13911111111', '王供应', '货到30天', v_now, v_now),
    ('SUP-F006-TEST-002', 'SUP-TEST-002', 'SUP-TEST-002', '测试供应商乙', 'F006', v_admin_id, true, 0, '13922222222', '李供应', '货到60天', v_now, v_now)
  ON CONFLICT (id) DO NOTHING;

  -- 2c. product_types (2) — needed as BOM target + sales_order_items.product_type_id
  INSERT INTO product_types (
    id, code, factory_id, name, unit, created_by, is_active, unit_price, created_at, updated_at
  ) VALUES
    ('PT-F006-TEST-001', 'PT-TEST-001', 'F006', '测试成品甲', 'kg', v_admin_id, true, 50.00, v_now, v_now),
    ('PT-F006-TEST-002', 'PT-TEST-002', 'F006', '测试成品乙', '个', v_admin_id, true, 10.00, v_now, v_now)
  ON CONFLICT (id) DO NOTHING;

  -- 2d. BOM recipe + items (1 recipe with 2 components)
  INSERT INTO bom_recipes (
    id, factory_id, recipe_code, product_type_id, product_name, version, is_current,
    overall_yield_rate, output_quantity_per_unit, output_unit, status, source_type,
    created_at, updated_at
  ) VALUES
    ('BOM-F006-TEST-001', 'F006', 'BOM-TEST-001', 'PT-F006-TEST-001', '测试成品甲', 1, true,
     95.00, 1000, 'g', 'ACTIVE', 'MANUAL', v_now, v_now)
  ON CONFLICT (id) DO NOTHING;

  INSERT INTO bom_recipe_items (
    recipe_id, factory_id, material_type_id, material_name, standard_quantity, yield_rate,
    unit, material_category, sort_order, created_at, updated_at
  )
  SELECT 'BOM-F006-TEST-001', 'F006', 'RMT-F006-001',      '测试食材',   500, 98, 'g', 'RAW',       1, v_now, v_now
  WHERE NOT EXISTS (SELECT 1 FROM bom_recipe_items WHERE recipe_id = 'BOM-F006-TEST-001' AND material_type_id = 'RMT-F006-001');

  INSERT INTO bom_recipe_items (
    recipe_id, factory_id, material_type_id, material_name, standard_quantity, yield_rate,
    unit, material_category, sort_order, created_at, updated_at
  )
  SELECT 'BOM-F006-TEST-001', 'F006', 'RMT-F006-TEST-002', '测试调味料', 50,  99, 'g', 'AUXILIARY', 2, v_now, v_now
  WHERE NOT EXISTS (SELECT 1 FROM bom_recipe_items WHERE recipe_id = 'BOM-F006-TEST-001' AND material_type_id = 'RMT-F006-TEST-002');

  -- 2e. material_batches (8: 2 low-stock + 6 normal)
  -- Low-stock batches enable T4-B3 stock-insufficient four-tuple repro:
  --   available = receipt - used - reserved.  Low ones have available=1.
  INSERT INTO material_batches (
    id, batch_number, factory_id, material_type_id, supplier_id,
    inbound_date, receipt_quantity, reserved_quantity, used_quantity,
    quantity_unit, status, warehouse_id, unit_price, created_by, version, created_at, updated_at
  ) VALUES
    ('MB-F006-TEST-001', 'BATCH-LOW-001',    'F006', 'RMT-F006-001',      'SUP-F006-TEST-001', v_today, 10,   0, 9,   'kg', 'ACTIVE', v_wh_log, 12.00, v_admin_id, 0, v_now, v_now),
    ('MB-F006-TEST-002', 'BATCH-LOW-002',    'F006', 'RMT-F006-TEST-002', 'SUP-F006-TEST-001', v_today, 5,    0, 4,   'kg', 'ACTIVE', v_wh_log, 15.00, v_admin_id, 0, v_now, v_now),
    ('MB-F006-TEST-003', 'BATCH-NORMAL-001', 'F006', 'RMT-F006-001',      'SUP-F006-TEST-001', v_today, 1000, 0, 0,   'kg', 'ACTIVE', v_wh_log, 12.00, v_admin_id, 0, v_now, v_now),
    ('MB-F006-TEST-004', 'BATCH-NORMAL-002', 'F006', 'RMT-F006-001',      'SUP-F006-TEST-002', v_today, 800,  0, 0,   'kg', 'ACTIVE', v_wh_wks, 11.50, v_admin_id, 0, v_now, v_now),
    ('MB-F006-TEST-005', 'BATCH-NORMAL-003', 'F006', 'RMT-F006-TEST-002', 'SUP-F006-TEST-001', v_today, 500,  0, 50,  'kg', 'ACTIVE', v_wh_log, 15.00, v_admin_id, 0, v_now, v_now),
    ('MB-F006-TEST-006', 'BATCH-NORMAL-004', 'F006', 'RMT-F006-TEST-002', 'SUP-F006-TEST-002', v_today, 400,  0, 0,   'kg', 'ACTIVE', v_wh_wks, 14.50, v_admin_id, 0, v_now, v_now),
    ('MB-F006-TEST-007', 'BATCH-NORMAL-005', 'F006', 'RMT-F006-TEST-003', 'SUP-F006-TEST-001', v_today, 5000, 0, 200, '个', 'ACTIVE', v_wh_log,  2.00, v_admin_id, 0, v_now, v_now),
    ('MB-F006-TEST-008', 'BATCH-NORMAL-006', 'F006', 'RMT-F006-TEST-003', 'SUP-F006-TEST-002', v_today, 3000, 0, 0,   '个', 'ACTIVE', v_wh_log,  1.80, v_admin_id, 0, v_now, v_now)
  ON CONFLICT (id) DO NOTHING;

  -- 2f. purchase_orders + items (5)
  -- Multiple POs to same RMT-F006-001 from different suppliers at different unit prices
  -- (12.00 / 11.50 / 12.50) enables T3-14 三价对比 refresh repro.
  INSERT INTO purchase_orders (
    id, factory_id, order_number, supplier_id, purchase_type, order_date,
    expected_delivery_date, total_amount, tax_amount, status, created_by, version,
    created_at, updated_at
  ) VALUES
    ('PO-F006-TEST-001', 'F006', 'PO-TEST-001', 'SUP-F006-TEST-001', 'DIRECT', v_today - 30, v_today - 25, 12000.00, 1560.00, 'COMPLETED', v_admin_id, 0, v_now, v_now),
    ('PO-F006-TEST-002', 'F006', 'PO-TEST-002', 'SUP-F006-TEST-002', 'DIRECT', v_today - 20, v_today - 15, 11500.00, 1495.00, 'COMPLETED', v_admin_id, 0, v_now, v_now),
    ('PO-F006-TEST-003', 'F006', 'PO-TEST-003', 'SUP-F006-TEST-001', 'DIRECT', v_today - 10, v_today - 5,  12500.00, 1625.00, 'COMPLETED', v_admin_id, 0, v_now, v_now),
    ('PO-F006-TEST-004', 'F006', 'PO-TEST-004', 'SUP-F006-TEST-002', 'DIRECT', v_today - 5,  v_today + 5,   5400.00,  702.00, 'APPROVED',  v_admin_id, 0, v_now, v_now),
    ('PO-F006-TEST-005', 'F006', 'PO-TEST-005', 'SUP-F006-TEST-001', 'DIRECT', v_today,      v_today + 10,  9000.00, 1170.00, 'DRAFT',     v_admin_id, 0, v_now, v_now)
  ON CONFLICT (factory_id, order_number) DO NOTHING;

  INSERT INTO purchase_order_items (purchase_order_id, material_type_id, material_name, quantity, unit, unit_price, tax_rate, received_quantity)
  SELECT 'PO-F006-TEST-001', 'RMT-F006-001', '测试食材', 1000, 'kg', 12.00, 13.00, 1000
  WHERE NOT EXISTS (SELECT 1 FROM purchase_order_items WHERE purchase_order_id = 'PO-F006-TEST-001');

  INSERT INTO purchase_order_items (purchase_order_id, material_type_id, material_name, quantity, unit, unit_price, tax_rate, received_quantity)
  SELECT 'PO-F006-TEST-002', 'RMT-F006-001', '测试食材', 1000, 'kg', 11.50, 13.00, 1000
  WHERE NOT EXISTS (SELECT 1 FROM purchase_order_items WHERE purchase_order_id = 'PO-F006-TEST-002');

  INSERT INTO purchase_order_items (purchase_order_id, material_type_id, material_name, quantity, unit, unit_price, tax_rate, received_quantity)
  SELECT 'PO-F006-TEST-003', 'RMT-F006-001', '测试食材', 1000, 'kg', 12.50, 13.00, 1000
  WHERE NOT EXISTS (SELECT 1 FROM purchase_order_items WHERE purchase_order_id = 'PO-F006-TEST-003');

  INSERT INTO purchase_order_items (purchase_order_id, material_type_id, material_name, quantity, unit, unit_price, tax_rate, received_quantity)
  SELECT 'PO-F006-TEST-004', 'RMT-F006-TEST-002', '测试调味料', 360, 'kg', 15.00, 13.00, 0
  WHERE NOT EXISTS (SELECT 1 FROM purchase_order_items WHERE purchase_order_id = 'PO-F006-TEST-004');

  INSERT INTO purchase_order_items (purchase_order_id, material_type_id, material_name, quantity, unit, unit_price, tax_rate, received_quantity)
  SELECT 'PO-F006-TEST-005', 'RMT-F006-001', '测试食材', 750, 'kg', 12.00, 13.00, 0
  WHERE NOT EXISTS (SELECT 1 FROM purchase_order_items WHERE purchase_order_id = 'PO-F006-TEST-005');

  -- 2g. sales_orders + items (5)
  -- CONFIRMED status so warehouse fulfillment (T4-D5 WH-LOG 出货) can proceed.
  -- Valid SO statuses: DRAFT, CONFIRMED, PENDING_FINANCE_REVIEW, FINANCE_APPROVED,
  --                    FINANCE_REJECTED, PROCESSING, PARTIAL_DELIVERED, COMPLETED, CANCELLED
  INSERT INTO sales_orders (
    id, factory_id, order_number, customer_id, order_date,
    required_delivery_date, total_amount, status, created_by, created_at, updated_at
  ) VALUES
    ('SO-F006-TEST-001', 'F006', 'SO-TEST-001', v_cust_id, v_today - 5, v_today + 5,  5000.00, 'CONFIRMED', v_admin_id, v_now, v_now),
    ('SO-F006-TEST-002', 'F006', 'SO-TEST-002', v_cust_id, v_today - 3, v_today + 7,  3500.00, 'CONFIRMED', v_admin_id, v_now, v_now),
    ('SO-F006-TEST-003', 'F006', 'SO-TEST-003', v_cust_id, v_today - 2, v_today + 8,  7800.00, 'FINANCE_APPROVED', v_admin_id, v_now, v_now),
    ('SO-F006-TEST-004', 'F006', 'SO-TEST-004', v_cust_id, v_today,     v_today + 10, 2200.00, 'DRAFT',     v_admin_id, v_now, v_now),
    ('SO-F006-TEST-005', 'F006', 'SO-TEST-005', v_cust_id, v_today,     v_today + 14, 4400.00, 'CONFIRMED', v_admin_id, v_now, v_now)
  ON CONFLICT (id) DO NOTHING;

  INSERT INTO sales_order_items (sales_order_id, product_type_id, product_name, quantity, unit, unit_price, delivered_quantity)
  SELECT 'SO-F006-TEST-001', 'PT-F006-TEST-001', '测试成品甲', 100, 'kg', 50.00, 0
  WHERE NOT EXISTS (SELECT 1 FROM sales_order_items WHERE sales_order_id = 'SO-F006-TEST-001');

  INSERT INTO sales_order_items (sales_order_id, product_type_id, product_name, quantity, unit, unit_price, delivered_quantity)
  SELECT 'SO-F006-TEST-002', 'PT-F006-TEST-001', '测试成品甲', 70, 'kg', 50.00, 0
  WHERE NOT EXISTS (SELECT 1 FROM sales_order_items WHERE sales_order_id = 'SO-F006-TEST-002');

  INSERT INTO sales_order_items (sales_order_id, product_type_id, product_name, quantity, unit, unit_price, delivered_quantity)
  SELECT 'SO-F006-TEST-003', 'PT-F006-TEST-002', '测试成品乙', 780, '个', 10.00, 0
  WHERE NOT EXISTS (SELECT 1 FROM sales_order_items WHERE sales_order_id = 'SO-F006-TEST-003');

  INSERT INTO sales_order_items (sales_order_id, product_type_id, product_name, quantity, unit, unit_price, delivered_quantity)
  SELECT 'SO-F006-TEST-004', 'PT-F006-TEST-001', '测试成品甲', 44, 'kg', 50.00, 0
  WHERE NOT EXISTS (SELECT 1 FROM sales_order_items WHERE sales_order_id = 'SO-F006-TEST-004');

  INSERT INTO sales_order_items (sales_order_id, product_type_id, product_name, quantity, unit, unit_price, delivered_quantity)
  SELECT 'SO-F006-TEST-005', 'PT-F006-TEST-001', '测试成品甲', 88, 'kg', 50.00, 0
  WHERE NOT EXISTS (SELECT 1 FROM sales_order_items WHERE sales_order_id = 'SO-F006-TEST-005');

  RAISE NOTICE 'V20260603_01: F006 test data seeded on %', current_database();
END $$;
