-- =============================================================================
-- E2E Test Factory Seed: F_E2E_TEST
-- =============================================================================
-- Idempotent via ON CONFLICT DO NOTHING (or WHERE NOT EXISTS for bom_items).
-- Run twice safely. All IDs are deterministic UUIDs to avoid drift across runs.
--
-- Tables populated (in FK order):
--   factories → users → customers → suppliers → factory_warehouses
--   → raw_material_types → product_types → bom_items
--
-- Verification (run after applying):
--   SELECT
--     (SELECT COUNT(*) FROM factories          WHERE id='F_E2E_TEST')            AS factory_ct,
--     (SELECT COUNT(*) FROM users              WHERE factory_id='F_E2E_TEST')    AS user_ct,
--     (SELECT COUNT(*) FROM customers          WHERE factory_id='F_E2E_TEST')    AS customer_ct,
--     (SELECT COUNT(*) FROM suppliers          WHERE factory_id='F_E2E_TEST')    AS supplier_ct,
--     (SELECT COUNT(*) FROM factory_warehouses WHERE factory_id='F_E2E_TEST')    AS wh_ct,
--     (SELECT COUNT(*) FROM raw_material_types WHERE factory_id='F_E2E_TEST')    AS mat_ct,
--     (SELECT COUNT(*) FROM product_types      WHERE factory_id='F_E2E_TEST')    AS product_ct;
-- Expected: 1 / 5 / 3 / 3 / 2 / 40 / 5
-- =============================================================================

BEGIN;

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. FACTORY
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO factories (
    id, name, type, level, address, industry,
    is_active, manually_verified, ai_weekly_quota,
    created_at, updated_at
)
VALUES (
    'F_E2E_TEST',
    '六扇门 E2E 测试工厂',
    'FACTORY',
    0,
    '江苏省昆山市',
    '食品加工',
    true,
    false,
    20,
    NOW(),
    NOW()
)
ON CONFLICT (id) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. USERS  (5 roles, BCrypt hash of '123456')
-- Note: username is globally unique (no factory_id in UNIQUE constraint)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO users (
    factory_id, username, password_hash, full_name,
    role_code, is_active, platform_type,
    created_at, updated_at
)
VALUES
    ('F_E2E_TEST', 'e2e_super_admin',   '$2b$12$kNRuzD4ZSBttEir6cbwlteBTw7kq2lyz6aQnrwac1sn4i/eTLaRse', 'E2E 超管',     'factory_super_admin',   true, 'web,mobile', NOW(), NOW()),
    ('F_E2E_TEST', 'e2e_sales_mgr',     '$2b$12$kNRuzD4ZSBttEir6cbwlteBTw7kq2lyz6aQnrwac1sn4i/eTLaRse', 'E2E 销售经理', 'sales_manager',         true, 'web,mobile', NOW(), NOW()),
    ('F_E2E_TEST', 'e2e_purchase_mgr',  '$2b$12$kNRuzD4ZSBttEir6cbwlteBTw7kq2lyz6aQnrwac1sn4i/eTLaRse', 'E2E 采购经理', 'procurement_manager',   true, 'web,mobile', NOW(), NOW()),
    ('F_E2E_TEST', 'e2e_warehouse_ops', '$2b$12$kNRuzD4ZSBttEir6cbwlteBTw7kq2lyz6aQnrwac1sn4i/eTLaRse', 'E2E 仓管',     'warehouse_worker',      true, 'web,mobile', NOW(), NOW()),
    ('F_E2E_TEST', 'e2e_workshop_sup',  '$2b$12$kNRuzD4ZSBttEir6cbwlteBTw7kq2lyz6aQnrwac1sn4i/eTLaRse', 'E2E 车间主管', 'workshop_supervisor',   true, 'web,mobile', NOW(), NOW())
ON CONFLICT (username) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. CUSTOMERS  (3, deterministic UUIDs)
-- Note: customers.code is unique per factory_id
-- Note: customers table has NO tax_rate column — tax rate lives on bom_items
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO customers (
    id, factory_id, code, customer_code, name,
    is_active, created_by, current_balance,
    created_at, updated_at
)
SELECT
    id, factory_id, code, customer_code, name,
    is_active, created_by, current_balance,
    NOW(), NOW()
FROM (VALUES
    ('e2e-cus-dingxian-00000000000000001', 'F_E2E_TEST', 'CUS_DINGXIAN', 'CUS_DINGXIAN', '鼎鲜火锅义乌分公司', true,
     (SELECT id FROM users WHERE username='e2e_super_admin'), 0::numeric),
    ('e2e-cus-yunhai-000000000000000001', 'F_E2E_TEST', 'CUS_YUNHAI',   'CUS_YUNHAI',   '云海小龙虾电商',     true,
     (SELECT id FROM users WHERE username='e2e_super_admin'), 0::numeric),
    ('e2e-cus-zhangsan-0000000000000001', 'F_E2E_TEST', 'CUS_ZHANGSAN', 'CUS_ZHANGSAN', '张三 (个人)',         true,
     (SELECT id FROM users WHERE username='e2e_super_admin'), 0::numeric)
) AS v(id, factory_id, code, customer_code, name, is_active, created_by, current_balance)
ON CONFLICT (factory_id, code) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- 4. SUPPLIERS  (3, deterministic UUIDs)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO suppliers (
    id, factory_id, code, supplier_code, name,
    is_active, created_by, current_balance,
    created_at, updated_at
)
SELECT
    id, factory_id, code, supplier_code, name,
    is_active, created_by, current_balance,
    NOW(), NOW()
FROM (VALUES
    ('e2e-sup-tyson-000000000000000001', 'F_E2E_TEST', 'SUP_TYSON',   'SUP_TYSON',   '泰森禽业',   true,
     (SELECT id FROM users WHERE username='e2e_super_admin'), 0::numeric),
    ('e2e-sup-haitian-0000000000000001', 'F_E2E_TEST', 'SUP_HAITIAN', 'SUP_HAITIAN', '海天调料',   true,
     (SELECT id FROM users WHERE username='e2e_super_admin'), 0::numeric),
    ('e2e-sup-zhixiang-000000000000001', 'F_E2E_TEST', 'SUP_ZHIXIANG','SUP_ZHIXIANG','纸箱大王',   true,
     (SELECT id FROM users WHERE username='e2e_super_admin'), 0::numeric)
) AS v(id, factory_id, code, supplier_code, name, is_active, created_by, current_balance)
ON CONFLICT (factory_id, code) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- 5. FACTORY WAREHOUSES  (2)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO factory_warehouses (
    id, factory_id, code, name, type, is_active,
    created_at, updated_at
)
-- 2026-06-01 修 G2: code 必须用后端 canonical WH-LOG / WH-WKS (WarehouseCodes.java),
-- 不是 WH_LOGISTICS / WH_WORKSHOP。后端 confirm-receive / FIFO 按 WH-LOG/WH-WKS 查双仓,
-- 用错 code → 'Factory 缺少 warehouse seed [WH-LOG]' 500。真实工厂 (F001/F006) 都用这俩 code。
VALUES
    ('e2e-wh-logistics-00000000000001', 'F_E2E_TEST', 'WH-LOG', '物流仓', 'LOGISTICS', true, NOW(), NOW()),
    ('e2e-wh-workshop-00000000000001',  'F_E2E_TEST', 'WH-WKS', '鲜棉仓', 'WORKSHOP',  true, NOW(), NOW())
ON CONFLICT (factory_id, code) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- 6. RAW MATERIAL TYPES  (40 total, deterministic UUIDs)
--    Fish (10): MAT_F001–MAT_F010
--    Seasoning (15): MAT_S001–MAT_S015
--    Packaging (15): MAT_P001–MAT_P015
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO raw_material_types (
    id, factory_id, code, name, category, unit,
    is_active, created_by,
    created_at, updated_at
)
SELECT id, factory_id, code, name, category, unit, is_active, created_by, NOW(), NOW()
FROM (VALUES
    -- ── Fish (10) ──────────────────────────────────────────────────────────
    ('e2e-mat-f001-000000000000000001','F_E2E_TEST','MAT_F001','草鱼片',   '鱼类','公斤',true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-f002-000000000000000001','F_E2E_TEST','MAT_F002','黑鱼片',   '鱼类','公斤',true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-f003-000000000000000001','F_E2E_TEST','MAT_F003','鲈鱼片',   '鱼类','公斤',true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-f004-000000000000000001','F_E2E_TEST','MAT_F004','巴沙鱼片', '鱼类','公斤',true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-f005-000000000000000001','F_E2E_TEST','MAT_F005','鮰鱼片',   '鱼类','公斤',true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-f006-000000000000000001','F_E2E_TEST','MAT_F006','去骨鸡腿肉','禽肉','公斤',true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-f007-000000000000000001','F_E2E_TEST','MAT_F007','牛肉糜',   '肉类','公斤',true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-f008-000000000000000001','F_E2E_TEST','MAT_F008','猪肉糜',   '肉类','公斤',true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-f009-000000000000000001','F_E2E_TEST','MAT_F009','虾仁',     '海鲜','公斤',true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-f010-000000000000000001','F_E2E_TEST','MAT_F010','牛腱子',   '肉类','公斤',true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    -- ── Seasoning (15) ────────────────────────────────────────────────────
    ('e2e-mat-s001-000000000000000001','F_E2E_TEST','MAT_S001','酸菜',   '调辅料','公斤',true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-s002-000000000000000001','F_E2E_TEST','MAT_S002','剁椒',   '调辅料','公斤',true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-s003-000000000000000001','F_E2E_TEST','MAT_S003','花椒',   '调辅料','公斤',true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-s004-000000000000000001','F_E2E_TEST','MAT_S004','干辣椒', '调辅料','公斤',true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-s005-000000000000000001','F_E2E_TEST','MAT_S005','生姜',   '调辅料','公斤',true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-s006-000000000000000001','F_E2E_TEST','MAT_S006','大蒜',   '调辅料','公斤',true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-s007-000000000000000001','F_E2E_TEST','MAT_S007','食盐',   '调辅料','公斤',true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-s008-000000000000000001','F_E2E_TEST','MAT_S008','酱油',   '调辅料','公升',true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-s009-000000000000000001','F_E2E_TEST','MAT_S009','料酒',   '调辅料','公升',true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-s010-000000000000000001','F_E2E_TEST','MAT_S010','淀粉',   '调辅料','公斤',true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-s011-000000000000000001','F_E2E_TEST','MAT_S011','白糖',   '调辅料','公斤',true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-s012-000000000000000001','F_E2E_TEST','MAT_S012','鸡蛋',   '调辅料','个',  true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-s013-000000000000000001','F_E2E_TEST','MAT_S013','香菜',   '调辅料','公斤',true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-s014-000000000000000001','F_E2E_TEST','MAT_S014','葱段',   '调辅料','公斤',true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-s015-000000000000000001','F_E2E_TEST','MAT_S015','味精',   '调辅料','公斤',true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    -- ── Packaging (15) ────────────────────────────────────────────────────
    ('e2e-mat-p001-000000000000000001','F_E2E_TEST','MAT_P001','500g 规格袋','包材','个',  true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-p002-000000000000000001','F_E2E_TEST','MAT_P002','300g 规格袋','包材','个',  true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-p003-000000000000000001','F_E2E_TEST','MAT_P003','1kg 规格袋', '包材','个',  true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-p004-000000000000000001','F_E2E_TEST','MAT_P004','2kg 规格袋', '包材','个',  true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-p005-000000000000000001','F_E2E_TEST','MAT_P005','外箱 小',    '包材','个',  true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-p006-000000000000000001','F_E2E_TEST','MAT_P006','外箱 中',    '包材','个',  true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-p007-000000000000000001','F_E2E_TEST','MAT_P007','外箱 大',    '包材','个',  true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-p008-000000000000000001','F_E2E_TEST','MAT_P008','标签 A',     '包材','张',  true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-p009-000000000000000001','F_E2E_TEST','MAT_P009','标签 B',     '包材','张',  true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-p010-000000000000000001','F_E2E_TEST','MAT_P010','封口膜',     '包材','米',  true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-p011-000000000000000001','F_E2E_TEST','MAT_P011','冰袋',       '包材','个',  true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-p012-000000000000000001','F_E2E_TEST','MAT_P012','保温棉',     '包材','片',  true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-p013-000000000000000001','F_E2E_TEST','MAT_P013','胶带 60m',   '包材','卷',  true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-p014-000000000000000001','F_E2E_TEST','MAT_P014','泡沫箱',     '包材','个',  true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-mat-p015-000000000000000001','F_E2E_TEST','MAT_P015','气泡膜',     '包材','米',  true,(SELECT id FROM users WHERE username='e2e_super_admin'))
) AS v(id, factory_id, code, name, category, unit, is_active, created_by)
ON CONFLICT (factory_id, code) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- 7. PRODUCT TYPES  (5 SKUs, deterministic UUIDs)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO product_types (
    id, factory_id, code, name, category, unit,
    unit_price, product_category,
    is_active, created_by,
    created_at, updated_at
)
SELECT id, factory_id, code, name, category, unit,
       unit_price, product_category,
       is_active, created_by,
       NOW(), NOW()
FROM (VALUES
    ('e2e-sku-scy500-0000000000000001','F_E2E_TEST','SKU_SCY500','酸菜鱼 500g', '鱼类速食','袋', 25.00::numeric,'FINISHED_PRODUCT',true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-sku-djy500-0000000000000001','F_E2E_TEST','SKU_DJY500','剁椒鱼 500g', '鱼类速食','袋', 28.00::numeric,'FINISHED_PRODUCT',true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-sku-hjy300-0000000000000001','F_E2E_TEST','SKU_HJY300','花椒鱼 300g', '鱼类速食','袋', 18.00::numeric,'FINISHED_PRODUCT',true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-sku-nrw1k-00000000000000001','F_E2E_TEST','SKU_NRW1K', '鲜牛肉丸 1kg','肉丸',    '袋', 55.00::numeric,'FINISHED_PRODUCT',true,(SELECT id FROM users WHERE username='e2e_super_admin')),
    ('e2e-sku-jtw2k-00000000000000001','F_E2E_TEST','SKU_JTW2K', '去骨鸡腿 2kg','禽肉',    '袋', 72.00::numeric,'FINISHED_PRODUCT',true,(SELECT id FROM users WHERE username='e2e_super_admin'))
) AS v(id, factory_id, code, name, category, unit, unit_price, product_category, is_active, created_by)
ON CONFLICT (factory_id, code) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- 8. BOM ITEMS  (5 BOMs × 5-7 items each = 29 total rows)
-- bom_items has no unique constraint in PG — use WHERE NOT EXISTS for idempotency
-- material_category: RAW=原料, AUXILIARY=辅料, PACKAGING=包材
-- ─────────────────────────────────────────────────────────────────────────────

-- ── BOM 1: SKU_SCY500 (酸菜鱼 500g) ─────────────────────────────────────────
INSERT INTO bom_items (factory_id, product_type_id, material_type_id,
    product_name, material_name, standard_quantity, unit, material_category, sort_order,
    yield_rate, tax_rate, created_at, updated_at)
SELECT v.factory_id, p.id, m.id,
    v.product_name, v.material_name, v.standard_quantity, v.unit, v.material_category, v.sort_order,
    100.00, 0.00, NOW(), NOW()
FROM (VALUES
    ('F_E2E_TEST','酸菜鱼 500g','SKU_SCY500','草鱼片',   'MAT_F001',0.4000::numeric,'公斤','RAW',       1),
    ('F_E2E_TEST','酸菜鱼 500g','SKU_SCY500','酸菜',     'MAT_S001',0.0800::numeric,'公斤','AUXILIARY',  2),
    ('F_E2E_TEST','酸菜鱼 500g','SKU_SCY500','花椒',     'MAT_S003',0.0050::numeric,'公斤','AUXILIARY',  3),
    ('F_E2E_TEST','酸菜鱼 500g','SKU_SCY500','食盐',     'MAT_S007',0.0080::numeric,'公斤','AUXILIARY',  4),
    ('F_E2E_TEST','酸菜鱼 500g','SKU_SCY500','500g 规格袋','MAT_P001',1.0000::numeric,'个', 'PACKAGING',  5),
    ('F_E2E_TEST','酸菜鱼 500g','SKU_SCY500','外箱 小',  'MAT_P005',0.0500::numeric,'个', 'PACKAGING',  6)
) AS v(factory_id, product_name, product_code, material_name, material_code, standard_quantity, unit, material_category, sort_order)
JOIN product_types      p ON p.factory_id = v.factory_id AND p.code = v.product_code
JOIN raw_material_types m ON m.factory_id = v.factory_id AND m.code = v.material_code
WHERE NOT EXISTS (
    SELECT 1 FROM bom_items b
    WHERE b.factory_id = v.factory_id
      AND b.product_type_id = p.id
      AND b.material_type_id = m.id
      AND b.deleted_at IS NULL
);

-- ── BOM 2: SKU_DJY500 (剁椒鱼 500g) ─────────────────────────────────────────
INSERT INTO bom_items (factory_id, product_type_id, material_type_id,
    product_name, material_name, standard_quantity, unit, material_category, sort_order,
    yield_rate, tax_rate, created_at, updated_at)
SELECT v.factory_id, p.id, m.id,
    v.product_name, v.material_name, v.standard_quantity, v.unit, v.material_category, v.sort_order,
    100.00, 0.00, NOW(), NOW()
FROM (VALUES
    ('F_E2E_TEST','剁椒鱼 500g','SKU_DJY500','黑鱼片',   'MAT_F002',0.4000::numeric,'公斤','RAW',       1),
    ('F_E2E_TEST','剁椒鱼 500g','SKU_DJY500','剁椒',     'MAT_S002',0.1000::numeric,'公斤','AUXILIARY',  2),
    ('F_E2E_TEST','剁椒鱼 500g','SKU_DJY500','生姜',     'MAT_S005',0.0100::numeric,'公斤','AUXILIARY',  3),
    ('F_E2E_TEST','剁椒鱼 500g','SKU_DJY500','食盐',     'MAT_S007',0.0080::numeric,'公斤','AUXILIARY',  4),
    ('F_E2E_TEST','剁椒鱼 500g','SKU_DJY500','500g 规格袋','MAT_P001',1.0000::numeric,'个', 'PACKAGING',  5),
    ('F_E2E_TEST','剁椒鱼 500g','SKU_DJY500','外箱 小',  'MAT_P005',0.0500::numeric,'个', 'PACKAGING',  6)
) AS v(factory_id, product_name, product_code, material_name, material_code, standard_quantity, unit, material_category, sort_order)
JOIN product_types      p ON p.factory_id = v.factory_id AND p.code = v.product_code
JOIN raw_material_types m ON m.factory_id = v.factory_id AND m.code = v.material_code
WHERE NOT EXISTS (
    SELECT 1 FROM bom_items b
    WHERE b.factory_id = v.factory_id
      AND b.product_type_id = p.id
      AND b.material_type_id = m.id
      AND b.deleted_at IS NULL
);

-- ── BOM 3: SKU_HJY300 (花椒鱼 300g) ─────────────────────────────────────────
INSERT INTO bom_items (factory_id, product_type_id, material_type_id,
    product_name, material_name, standard_quantity, unit, material_category, sort_order,
    yield_rate, tax_rate, created_at, updated_at)
SELECT v.factory_id, p.id, m.id,
    v.product_name, v.material_name, v.standard_quantity, v.unit, v.material_category, v.sort_order,
    100.00, 0.00, NOW(), NOW()
FROM (VALUES
    ('F_E2E_TEST','花椒鱼 300g','SKU_HJY300','鲈鱼片',   'MAT_F003',0.2400::numeric,'公斤','RAW',       1),
    ('F_E2E_TEST','花椒鱼 300g','SKU_HJY300','花椒',     'MAT_S003',0.0100::numeric,'公斤','AUXILIARY',  2),
    ('F_E2E_TEST','花椒鱼 300g','SKU_HJY300','干辣椒',   'MAT_S004',0.0150::numeric,'公斤','AUXILIARY',  3),
    ('F_E2E_TEST','花椒鱼 300g','SKU_HJY300','食盐',     'MAT_S007',0.0050::numeric,'公斤','AUXILIARY',  4),
    ('F_E2E_TEST','花椒鱼 300g','SKU_HJY300','300g 规格袋','MAT_P002',1.0000::numeric,'个', 'PACKAGING',  5),
    ('F_E2E_TEST','花椒鱼 300g','SKU_HJY300','外箱 小',  'MAT_P005',0.0500::numeric,'个', 'PACKAGING',  6)
) AS v(factory_id, product_name, product_code, material_name, material_code, standard_quantity, unit, material_category, sort_order)
JOIN product_types      p ON p.factory_id = v.factory_id AND p.code = v.product_code
JOIN raw_material_types m ON m.factory_id = v.factory_id AND m.code = v.material_code
WHERE NOT EXISTS (
    SELECT 1 FROM bom_items b
    WHERE b.factory_id = v.factory_id
      AND b.product_type_id = p.id
      AND b.material_type_id = m.id
      AND b.deleted_at IS NULL
);

-- ── BOM 4: SKU_NRW1K (鲜牛肉丸 1kg) ─────────────────────────────────────────
INSERT INTO bom_items (factory_id, product_type_id, material_type_id,
    product_name, material_name, standard_quantity, unit, material_category, sort_order,
    yield_rate, tax_rate, created_at, updated_at)
SELECT v.factory_id, p.id, m.id,
    v.product_name, v.material_name, v.standard_quantity, v.unit, v.material_category, v.sort_order,
    100.00, 0.00, NOW(), NOW()
FROM (VALUES
    ('F_E2E_TEST','鲜牛肉丸 1kg','SKU_NRW1K','牛肉糜',   'MAT_F007',0.8000::numeric,'公斤','RAW',       1),
    ('F_E2E_TEST','鲜牛肉丸 1kg','SKU_NRW1K','食盐',     'MAT_S007',0.0120::numeric,'公斤','AUXILIARY',  2),
    ('F_E2E_TEST','鲜牛肉丸 1kg','SKU_NRW1K','淀粉',     'MAT_S010',0.0400::numeric,'公斤','AUXILIARY',  3),
    ('F_E2E_TEST','鲜牛肉丸 1kg','SKU_NRW1K','鸡蛋',     'MAT_S012',2.0000::numeric,'个', 'AUXILIARY',  4),
    ('F_E2E_TEST','鲜牛肉丸 1kg','SKU_NRW1K','1kg 规格袋','MAT_P003',1.0000::numeric,'个', 'PACKAGING',  5),
    ('F_E2E_TEST','鲜牛肉丸 1kg','SKU_NRW1K','外箱 小',  'MAT_P005',0.0500::numeric,'个', 'PACKAGING',  6)
) AS v(factory_id, product_name, product_code, material_name, material_code, standard_quantity, unit, material_category, sort_order)
JOIN product_types      p ON p.factory_id = v.factory_id AND p.code = v.product_code
JOIN raw_material_types m ON m.factory_id = v.factory_id AND m.code = v.material_code
WHERE NOT EXISTS (
    SELECT 1 FROM bom_items b
    WHERE b.factory_id = v.factory_id
      AND b.product_type_id = p.id
      AND b.material_type_id = m.id
      AND b.deleted_at IS NULL
);

-- ── BOM 5: SKU_JTW2K (去骨鸡腿 2kg) ─────────────────────────────────────────
INSERT INTO bom_items (factory_id, product_type_id, material_type_id,
    product_name, material_name, standard_quantity, unit, material_category, sort_order,
    yield_rate, tax_rate, created_at, updated_at)
SELECT v.factory_id, p.id, m.id,
    v.product_name, v.material_name, v.standard_quantity, v.unit, v.material_category, v.sort_order,
    100.00, 0.00, NOW(), NOW()
FROM (VALUES
    ('F_E2E_TEST','去骨鸡腿 2kg','SKU_JTW2K','去骨鸡腿肉','MAT_F006',1.9500::numeric,'公斤','RAW',       1),
    ('F_E2E_TEST','去骨鸡腿 2kg','SKU_JTW2K','食盐',      'MAT_S007',0.0100::numeric,'公斤','AUXILIARY',  2),
    ('F_E2E_TEST','去骨鸡腿 2kg','SKU_JTW2K','料酒',      'MAT_S009',0.0200::numeric,'公升','AUXILIARY',  3),
    ('F_E2E_TEST','去骨鸡腿 2kg','SKU_JTW2K','2kg 规格袋','MAT_P004',1.0000::numeric,'个', 'PACKAGING',  4),
    ('F_E2E_TEST','去骨鸡腿 2kg','SKU_JTW2K','外箱 中',   'MAT_P006',0.0500::numeric,'个', 'PACKAGING',  5)
) AS v(factory_id, product_name, product_code, material_name, material_code, standard_quantity, unit, material_category, sort_order)
JOIN product_types      p ON p.factory_id = v.factory_id AND p.code = v.product_code
JOIN raw_material_types m ON m.factory_id = v.factory_id AND m.code = v.material_code
WHERE NOT EXISTS (
    SELECT 1 FROM bom_items b
    WHERE b.factory_id = v.factory_id
      AND b.product_type_id = p.id
      AND b.material_type_id = m.id
      AND b.deleted_at IS NULL
);

-- ─────────────────────────────────────────────────────────────────────────────
-- 8b. MATERIAL BATCHES (库存)  — 2026-06-01 修 G3: 开始生产 (start) 校验原料库存,
--     seed 只建了 raw_material_types (类型) 没建 material_batches (实际库存) → 开始生产
--     报 '原料库存不足, 可用 0'。给每个 raw material 建一个 AVAILABLE 批次到 WH-LOG (物流仓),
--     量充足 (1000 单位) 覆盖任何 BOM 需求。可用 = receipt - used - reserved。
--     material_type_id 引用 seed 自己的 raw_material_types.id; warehouse_id = WH-LOG 的 seed id。
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO material_batches (
    id, factory_id, material_type_id, batch_number, warehouse_id,
    receipt_quantity, used_quantity, reserved_quantity, quantity_unit,
    inbound_date, status, created_by, created_at, updated_at, version
)
SELECT
    'e2e-mb-' || m.code || '-0000000000001',
    'F_E2E_TEST',
    m.id,
    'E2E-MB-' || m.code,
    'e2e-wh-logistics-00000000000001',   -- WH-LOG (物流仓)
    1000.0000, 0.0000, 0.0000, m.unit,
    CURRENT_DATE, 'AVAILABLE',
    (SELECT id FROM users WHERE username = 'e2e_super_admin'),
    NOW(), NOW(), 0
FROM raw_material_types m
WHERE m.factory_id = 'F_E2E_TEST' AND m.deleted_at IS NULL
ON CONFLICT (id) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- 9. VERIFICATION QUERY
-- ─────────────────────────────────────────────────────────────────────────────
DO $$
DECLARE
    v_factory_ct  INT;
    v_user_ct     INT;
    v_customer_ct INT;
    v_supplier_ct INT;
    v_wh_ct       INT;
    v_mat_ct      INT;
    v_product_ct  INT;
    v_bom_ct      INT;
BEGIN
    SELECT COUNT(*) INTO v_factory_ct  FROM factories          WHERE id='F_E2E_TEST';
    SELECT COUNT(*) INTO v_user_ct     FROM users              WHERE factory_id='F_E2E_TEST';
    SELECT COUNT(*) INTO v_customer_ct FROM customers          WHERE factory_id='F_E2E_TEST';
    SELECT COUNT(*) INTO v_supplier_ct FROM suppliers          WHERE factory_id='F_E2E_TEST';
    SELECT COUNT(*) INTO v_wh_ct       FROM factory_warehouses WHERE factory_id='F_E2E_TEST';
    SELECT COUNT(*) INTO v_mat_ct      FROM raw_material_types WHERE factory_id='F_E2E_TEST' AND deleted_at IS NULL;
    SELECT COUNT(*) INTO v_product_ct  FROM product_types      WHERE factory_id='F_E2E_TEST' AND deleted_at IS NULL;
    SELECT COUNT(*) INTO v_bom_ct      FROM bom_items bi
        JOIN product_types p ON p.id = bi.product_type_id
        WHERE p.factory_id = 'F_E2E_TEST' AND bi.deleted_at IS NULL;

    RAISE NOTICE 'F_E2E_TEST seed verification:';
    RAISE NOTICE '  factory_ct=% (expect 1)', v_factory_ct;
    RAISE NOTICE '  user_ct=% (expect 5)',    v_user_ct;
    RAISE NOTICE '  customer_ct=% (expect 3)', v_customer_ct;
    RAISE NOTICE '  supplier_ct=% (expect 3)', v_supplier_ct;
    RAISE NOTICE '  wh_ct=% (expect 2)',       v_wh_ct;
    RAISE NOTICE '  mat_ct=% (expect 40)',     v_mat_ct;
    RAISE NOTICE '  product_ct=% (expect >=5)', v_product_ct;
    RAISE NOTICE '  bom_item_ct=% (expect >=29)', v_bom_ct;

    IF v_factory_ct  != 1  THEN RAISE EXCEPTION 'factory_ct mismatch: expected 1, got %', v_factory_ct;  END IF;
    IF v_user_ct     != 5  THEN RAISE EXCEPTION 'user_ct mismatch: expected 5, got %',    v_user_ct;     END IF;
    IF v_customer_ct < 3   THEN RAISE EXCEPTION 'customer_ct too low: expected >=3, got %', v_customer_ct; END IF;
    IF v_supplier_ct < 3   THEN RAISE EXCEPTION 'supplier_ct too low: expected >=3, got %', v_supplier_ct; END IF;
    IF v_wh_ct       != 2  THEN RAISE EXCEPTION 'wh_ct mismatch: expected 2, got %',      v_wh_ct;       END IF;
    IF v_mat_ct      < 40  THEN RAISE EXCEPTION 'mat_ct too low: expected >=40, got %',    v_mat_ct;      END IF;
    IF v_product_ct  < 5   THEN RAISE EXCEPTION 'product_ct too low: expected >=5, got %', v_product_ct;  END IF;
    IF v_bom_ct      < 29  THEN RAISE EXCEPTION 'bom_item_ct too low: expected >=29, got %', v_bom_ct;    END IF;

    RAISE NOTICE 'All checks PASSED.';
END;
$$;

COMMIT;
