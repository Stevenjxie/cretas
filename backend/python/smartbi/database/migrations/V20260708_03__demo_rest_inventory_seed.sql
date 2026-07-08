-- Demo restaurant inventory-warning seed (RESTAURANT_OPS_INVENTORY_WARNING,
-- 2026-07-08 restaurant intent tiered-routing follow-up: 库存预警 + 排班建议).
--
-- Reuses fact_inventory_snapshot + dim_ingredient_threshold + dim_ingredient
-- (no new tables), all already GRANTed to smartbi_user
-- (V20260428_03__b_silver_grants.sql).
--
-- ⚠️ Collision-robust rewrite (2026-07-08): DEMO_REST's dim_ingredient is NOT
-- empty in prod -- it already holds ~53 ETL rows, at least one of which
-- (鸭血) shares a normalized_name with this seed. dim_ingredient carries
-- UNIQUE (factory_id, normalized_name), and BOTH fact_inventory_snapshot and
-- dim_ingredient_threshold have a FK on ingredient_id -> dim_ingredient. A
-- naive `ON CONFLICT (ingredient_id) DO NOTHING` on dim_ingredient would skip
-- the colliding row (its 9000x id never created) and then the threshold /
-- snapshot rows referencing that 9000x ingredient_id would abort on the FK.
--
-- Fix:
--   1. dim_ingredient uses no-target `ON CONFLICT DO NOTHING` -- skips on the
--      PK dup OR the (factory_id, normalized_name) / (factory_id, source_pk)
--      unique, so a name that already exists is left untouched.
--   2. threshold + snapshot resolve ingredient_id BY normalized_name (JOIN
--      dim_ingredient) instead of the hardcoded 9000x space, so a demo
--      ingredient that already exists (e.g. 鸭血) attaches to its REAL
--      ingredient_id. This is future-proof against any further ETL-populated
--      name overlap, and satisfies both the FK and the resolver's INNER JOIN
--      (fact_inventory_snapshot s JOIN dim_ingredient i ON i.ingredient_id =
--      s.ingredient_id). The 11 non-colliding names are inserted by step 1
--      above and are visible to the JOIN within the same migration txn.
--
-- Fixed ingredient_id space (90001-90012) for the newly-inserted rows avoids
-- colliding with any future ETL-populated ingredient_id sequence values for
-- DEMO_REST. The threshold / snapshot `id` PKs (also 9000x) make
-- `ON CONFLICT (id) DO NOTHING` a real idempotency guard even though store_id
-- is NULL on every row (a NULL-inclusive natural unique constraint would not
-- reliably detect a re-run as a conflict).
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
ON CONFLICT DO NOTHING;

INSERT INTO dim_ingredient_threshold
    (id, factory_id, ingredient_id, store_id, safe_stock_qty, reorder_point, max_stock_qty, unit, set_by, created_at, updated_at)
SELECT v.id, 'DEMO_REST', di.ingredient_id, NULL, v.safe, v.reorder, v.maxq, v.unit, 'demo-seed', NOW(), NOW()
FROM (VALUES
    (90001, '活鱼',       50, 20, 100, '斤'),
    (90002, '青花椒底料', 30, 10, 80,  'kg'),
    (90003, '黄豆芽',     40, 15, 90,  'kg'),
    (90004, '嫩豆花',     25, 10, 60,  'kg'),
    (90005, '毛肚',       35, 15, 70,  'kg'),
    (90006, '鸭血',       30, 12, 60,  'kg'),
    (90007, '宽粉',       50, 20, 100, 'kg'),
    (90008, '藕片',       40, 15, 90,  'kg'),
    (90009, '木耳',       15, 5,  40,  'kg'),
    (90010, '香菜',       10, 3,  30,  'kg'),
    (90011, '花椒油',     20, 8,  50,  'L'),
    (90012, '干辣椒',     25, 10, 60,  'kg')
) AS v(id, nname, safe, reorder, maxq, unit)
JOIN dim_ingredient di
  ON di.factory_id = 'DEMO_REST' AND di.normalized_name = v.nname
ON CONFLICT (id) DO NOTHING;

INSERT INTO fact_inventory_snapshot
    (id, factory_id, upload_id, ingredient_id, store_id, snapshot_date, stock_qty, unit, safe_stock_qty, reorder_point, created_at)
SELECT v.id, 'DEMO_REST', NULL, di.ingredient_id, NULL, DATE '2026-07-07', v.stock, v.unit, v.safe, v.reorder, NOW()
FROM (VALUES
    (90001, '活鱼',       8,  '斤', 50, 20),
    (90002, '青花椒底料', 45, 'kg', 30, 10),
    (90003, '黄豆芽',     5,  'kg', 40, 15),
    (90004, '嫩豆花',     3,  'kg', 25, 10),
    (90005, '毛肚',       20, 'kg', 35, 15),
    (90006, '鸭血',       18, 'kg', 30, 12),
    (90007, '宽粉',       30, 'kg', 50, 20),
    (90008, '藕片',       25, 'kg', 40, 15),
    (90009, '木耳',       20, 'kg', 15, 5),
    (90010, '香菜',       15, 'kg', 10, 3),
    (90011, '花椒油',     25, 'L',  20, 8),
    (90012, '干辣椒',     40, 'kg', 25, 10)
) AS v(id, nname, stock, unit, safe, reorder)
JOIN dim_ingredient di
  ON di.factory_id = 'DEMO_REST' AND di.normalized_name = v.nname
ON CONFLICT (id) DO NOTHING;
