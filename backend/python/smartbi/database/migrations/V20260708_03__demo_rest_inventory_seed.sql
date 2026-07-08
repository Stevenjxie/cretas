-- Demo restaurant inventory-warning seed (RESTAURANT_OPS_INVENTORY_WARNING,
-- 2026-07-08 restaurant intent tiered-routing follow-up: 库存预警 + 排班建议).
--
-- DEMO_REST's dim_ingredient / dim_store are BOTH EMPTY (verified against
-- prod), so this seed is self-contained -- it inserts its own
-- dim_ingredient rows + thresholds + one snapshot day, rather than
-- depending on any pre-existing ETL data. No new tables here: reuses
-- fact_inventory_snapshot + dim_ingredient_threshold + dim_ingredient,
-- all already GRANTed to smartbi_user (V20260428_03__b_silver_grants.sql).
--
-- Fixed ingredient_id space (90001-90012) avoids colliding with any future
-- ETL-populated ingredient_id sequence values for DEMO_REST. `id` columns
-- on the threshold/snapshot rows are also explicit (mirroring the
-- ingredient_id space) so ON CONFLICT (id) DO NOTHING is a real idempotency
-- guard even though store_id is NULL on every row (a NULL-inclusive natural
-- unique constraint would not reliably detect a re-run as a conflict).
--
-- snapshot_date is a FIXED date (2026-07-07), not CURRENT_DATE -- the
-- resolver reads MAX(snapshot_date) per factory, so a fixed date keeps the
-- seed usable indefinitely without depending on "today" staying close to
-- the day this migration was written.
--
-- Three tiers by design (12 ingredients), computed as
-- stock_qty vs reorder_point / safe_stock_qty in resolve_inventory_warning:
--   HIGH   (stock_qty < reorder_point):          活鱼 / 黄豆芽 / 嫩豆花       (3)
--   MEDIUM (reorder_point <= stock_qty < safe):  毛肚 / 鸭血 / 宽粉 / 藕片    (4)
--   OK     (stock_qty >= safe_stock_qty):        青花椒底料 / 木耳 / 香菜 / 花椒油 / 干辣椒 (5)

INSERT INTO dim_ingredient
    (ingredient_id, factory_id, source_pk, name, normalized_name, category, code, unit, unit_price, is_active, created_at, updated_at)
VALUES
    (90001, 'DEMO_REST', 'DEMO_INV_001', '活鱼',       '活鱼',       '水产',   'INV-001', '斤', 18.00, TRUE, NOW(), NOW()),
    (90002, 'DEMO_REST', 'DEMO_INV_002', '青花椒底料', '青花椒底料', '调料',   'INV-002', 'kg', 32.00, TRUE, NOW(), NOW()),
    (90003, 'DEMO_REST', 'DEMO_INV_003', '黄豆芽',     '黄豆芽',     '蔬菜',   'INV-003', 'kg', 3.50,  TRUE, NOW(), NOW()),
    (90004, 'DEMO_REST', 'DEMO_INV_004', '嫩豆花',     '嫩豆花',     '豆制品', 'INV-004', 'kg', 6.00,  TRUE, NOW(), NOW()),
    (90005, 'DEMO_REST', 'DEMO_INV_005', '毛肚',       '毛肚',       '肉类',   'INV-005', 'kg', 42.00, TRUE, NOW(), NOW()),
    (90006, 'DEMO_REST', 'DEMO_INV_006', '鸭血',       '鸭血',       '肉类',   'INV-006', 'kg', 16.00, TRUE, NOW(), NOW()),
    (90007, 'DEMO_REST', 'DEMO_INV_007', '宽粉',       '宽粉',       '主食',   'INV-007', 'kg', 8.00,  TRUE, NOW(), NOW()),
    (90008, 'DEMO_REST', 'DEMO_INV_008', '藕片',       '藕片',       '蔬菜',   'INV-008', 'kg', 5.50,  TRUE, NOW(), NOW()),
    (90009, 'DEMO_REST', 'DEMO_INV_009', '木耳',       '木耳',       '蔬菜',   'INV-009', 'kg', 14.00, TRUE, NOW(), NOW()),
    (90010, 'DEMO_REST', 'DEMO_INV_010', '香菜',       '香菜',       '蔬菜',   'INV-010', 'kg', 9.00,  TRUE, NOW(), NOW()),
    (90011, 'DEMO_REST', 'DEMO_INV_011', '花椒油',     '花椒油',     '调料',   'INV-011', 'L',  28.00, TRUE, NOW(), NOW()),
    (90012, 'DEMO_REST', 'DEMO_INV_012', '干辣椒',     '干辣椒',     '调料',   'INV-012', 'kg', 22.00, TRUE, NOW(), NOW())
ON CONFLICT (ingredient_id) DO NOTHING;

INSERT INTO dim_ingredient_threshold
    (id, factory_id, ingredient_id, store_id, safe_stock_qty, reorder_point, max_stock_qty, unit, set_by, created_at, updated_at)
VALUES
    (90001, 'DEMO_REST', 90001, NULL, 50, 20, 100, '斤', 'demo-seed', NOW(), NOW()),
    (90002, 'DEMO_REST', 90002, NULL, 30, 10, 80,  'kg', 'demo-seed', NOW(), NOW()),
    (90003, 'DEMO_REST', 90003, NULL, 40, 15, 90,  'kg', 'demo-seed', NOW(), NOW()),
    (90004, 'DEMO_REST', 90004, NULL, 25, 10, 60,  'kg', 'demo-seed', NOW(), NOW()),
    (90005, 'DEMO_REST', 90005, NULL, 35, 15, 70,  'kg', 'demo-seed', NOW(), NOW()),
    (90006, 'DEMO_REST', 90006, NULL, 30, 12, 60,  'kg', 'demo-seed', NOW(), NOW()),
    (90007, 'DEMO_REST', 90007, NULL, 50, 20, 100, 'kg', 'demo-seed', NOW(), NOW()),
    (90008, 'DEMO_REST', 90008, NULL, 40, 15, 90,  'kg', 'demo-seed', NOW(), NOW()),
    (90009, 'DEMO_REST', 90009, NULL, 15, 5,  40,  'kg', 'demo-seed', NOW(), NOW()),
    (90010, 'DEMO_REST', 90010, NULL, 10, 3,  30,  'kg', 'demo-seed', NOW(), NOW()),
    (90011, 'DEMO_REST', 90011, NULL, 20, 8,  50,  'L',  'demo-seed', NOW(), NOW()),
    (90012, 'DEMO_REST', 90012, NULL, 25, 10, 60,  'kg', 'demo-seed', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

INSERT INTO fact_inventory_snapshot
    (id, factory_id, upload_id, ingredient_id, store_id, snapshot_date, stock_qty, unit, safe_stock_qty, reorder_point, created_at)
VALUES
    (90001, 'DEMO_REST', NULL, 90001, NULL, '2026-07-07', 8,  '斤', 50, 20, NOW()),
    (90002, 'DEMO_REST', NULL, 90002, NULL, '2026-07-07', 45, 'kg', 30, 10, NOW()),
    (90003, 'DEMO_REST', NULL, 90003, NULL, '2026-07-07', 5,  'kg', 40, 15, NOW()),
    (90004, 'DEMO_REST', NULL, 90004, NULL, '2026-07-07', 3,  'kg', 25, 10, NOW()),
    (90005, 'DEMO_REST', NULL, 90005, NULL, '2026-07-07', 20, 'kg', 35, 15, NOW()),
    (90006, 'DEMO_REST', NULL, 90006, NULL, '2026-07-07', 18, 'kg', 30, 12, NOW()),
    (90007, 'DEMO_REST', NULL, 90007, NULL, '2026-07-07', 30, 'kg', 50, 20, NOW()),
    (90008, 'DEMO_REST', NULL, 90008, NULL, '2026-07-07', 25, 'kg', 40, 15, NOW()),
    (90009, 'DEMO_REST', NULL, 90009, NULL, '2026-07-07', 20, 'kg', 15, 5,  NOW()),
    (90010, 'DEMO_REST', NULL, 90010, NULL, '2026-07-07', 15, 'kg', 10, 3,  NOW()),
    (90011, 'DEMO_REST', NULL, 90011, NULL, '2026-07-07', 25, 'L',  20, 8,  NOW()),
    (90012, 'DEMO_REST', NULL, 90012, NULL, '2026-07-07', 40, 'kg', 25, 10, NOW())
ON CONFLICT (id) DO NOTHING;
