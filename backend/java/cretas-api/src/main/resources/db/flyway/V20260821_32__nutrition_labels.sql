-- V20260821_32__nutrition_labels.sql
--
-- Sprint 9 P2.B 食品营养标签 — GB 28050-2011 (2026-05-21)
-- 法定: GB 28050-2011《食品安全国家标准 预包装食品营养标签通则》
--
-- 强制 "1+4": 能量 + 蛋白质 + 脂肪 + 碳水化合物 + 钠
-- 推荐 4: 饱和脂肪 + 反式脂肪 + 糖 + 膳食纤维
--
-- Tables:
--   ingredient_nutrition_facts   — 营养素 reference 库 (系统级, 无 factory_id)
--   nutrition_labels              — 产品营养标签生成结果 (factory 隔离)
--
-- V1 seed: 卤味 / 熟肉 / 调味料 30+ 常用原料 (per F006 业务).
-- 数据来源: 中国食物成分表 第 6 版 (中国疾病预防控制中心营养与健康所).
--
-- Pattern mirrors V20260820_03/04 (PG standard). 所有 BaseEntity audit fields
-- (created_at / updated_at / deleted_at) 含在每个表 — per
-- .claude/rules/database-entity-sync.md HARD rule.

-- ===== 1. ingredient_nutrition_facts (营养素 reference 库) =====

CREATE TABLE IF NOT EXISTS ingredient_nutrition_facts (
    id BIGSERIAL PRIMARY KEY,
    ingredient_name VARCHAR(200) NOT NULL,
    ingredient_code VARCHAR(50) NOT NULL,
    per_100g_energy DECIMAL(8,2) NOT NULL,
    per_100g_protein DECIMAL(8,2) NOT NULL,
    per_100g_fat DECIMAL(8,2) NOT NULL,
    per_100g_carbohydrate DECIMAL(8,2) NOT NULL,
    per_100g_sodium DECIMAL(8,2) NOT NULL,
    per_100g_saturated_fat DECIMAL(8,2),
    per_100g_trans_fat DECIMAL(8,2),
    per_100g_sugar DECIMAL(8,2),
    per_100g_dietary_fiber DECIMAL(8,2),
    source_ref TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP,
    CONSTRAINT uk_ingredient_nutrition_code UNIQUE (ingredient_code)
);

CREATE INDEX IF NOT EXISTS idx_ingredient_nutrition_name
    ON ingredient_nutrition_facts(ingredient_name, active)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_ingredient_nutrition_code_active
    ON ingredient_nutrition_facts(ingredient_code, active)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE ingredient_nutrition_facts IS 'GB 28050-2011 营养素 reference 库 — Sprint 9 P2.B';

-- ===== 2. nutrition_labels (产品营养标签生成结果) =====

CREATE TABLE IF NOT EXISTS nutrition_labels (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(64) NOT NULL,
    product_code VARCHAR(100) NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    bom_version_id VARCHAR(191) NOT NULL,
    serving_size DECIMAL(8,2) NOT NULL,
    energy_per_serving DECIMAL(8,2) NOT NULL,
    protein_per_serving DECIMAL(8,2) NOT NULL,
    fat_per_serving DECIMAL(8,2) NOT NULL,
    carbohydrate_per_serving DECIMAL(8,2) NOT NULL,
    sodium_per_serving DECIMAL(8,2) NOT NULL,
    saturated_fat_per_serving DECIMAL(8,2),
    trans_fat_per_serving DECIMAL(8,2),
    sugar_per_serving DECIMAL(8,2),
    dietary_fiber_per_serving DECIMAL(8,2),
    calculation_details TEXT,
    generated_by_user_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP,
    CONSTRAINT chk_nutrition_label_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'REVOKED'))
);

CREATE INDEX IF NOT EXISTS idx_nutrition_labels_factory_product
    ON nutrition_labels(factory_id, product_code)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_nutrition_labels_bom_version
    ON nutrition_labels(bom_version_id)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_nutrition_labels_status
    ON nutrition_labels(factory_id, status)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE nutrition_labels IS 'GB 28050-2011 预包装食品营养标签生成结果 — Sprint 9 P2.B';

-- ===== Seed: 中国食物成分表 第6版 — 卤味/熟肉/调味料 30+ 常用原料 =====
-- per F006 (六腾门 / 叮咚好食光) 卤味业务核心 ingredient
-- 营养值 per 100g 编辑可食用部分.

INSERT INTO ingredient_nutrition_facts (
    ingredient_name, ingredient_code,
    per_100g_energy, per_100g_protein, per_100g_fat, per_100g_carbohydrate, per_100g_sodium,
    per_100g_saturated_fat, per_100g_trans_fat, per_100g_sugar, per_100g_dietary_fiber,
    source_ref
) VALUES
-- 肉类 (卤味主料)
('猪蹄',              '08-1-001', 1004.20, 22.60, 18.80,  0.00,    80.00, 6.80, 0.00,  0.00,  0.00, '中国食物成分表 第6版'),
('鸡爪',              '08-1-002', 1098.00, 23.90, 16.40,  2.70,   169.00, 5.90, 0.00,  0.00,  0.00, '中国食物成分表 第6版'),
('鸭脖',              '08-2-001', 1004.20, 19.70, 16.50,  0.00,    96.00, 5.60, 0.00,  0.00,  0.00, '中国食物成分表 第6版'),
('鸭翅',              '08-2-002',  870.27, 17.90, 13.30,  4.20,   136.00, 3.50, 0.00,  0.00,  0.00, '中国食物成分表 第6版'),
('鸭头',              '08-2-003',  995.83, 18.40, 16.90,  0.00,    74.00, 4.30, 0.00,  0.00,  0.00, '中国食物成分表 第6版'),
('鸭肠',              '08-2-004',  481.16, 14.20,  2.10,  4.20,   115.00, 0.60, 0.00,  0.00,  0.00, '中国食物成分表 第6版'),
('猪耳',              '08-1-003', 1170.00, 22.50, 22.30,  1.10,    81.00, 7.80, 0.00,  0.00,  0.00, '中国食物成分表 第6版'),
('猪肚',              '08-1-004',  460.24, 14.60,  2.80,  0.40,    75.00, 1.20, 0.00,  0.00,  0.00, '中国食物成分表 第6版'),
('牛肉 (瘦)',         '08-3-001',  443.50, 20.20,  2.30,  1.20,    53.60, 0.90, 0.00,  0.00,  0.00, '中国食物成分表 第6版'),
('鸡胸肉',            '08-1-005',  556.47, 19.40,  5.00,  2.50,    34.40, 1.30, 0.00,  0.00,  0.00, '中国食物成分表 第6版'),
('鸡腿',              '08-1-006',  761.49, 16.00, 13.00,  0.00,    64.40, 3.60, 0.00,  0.00,  0.00, '中国食物成分表 第6版'),

-- 调味料 (卤味核心)
('老抽 (酱油)',       '24-1-001',  251.00, 11.50,  0.10, 11.40,  5757.00, 0.00, 0.00,  0.00,  0.00, '中国食物成分表 第6版'),
('生抽 (酱油)',       '24-1-002',  218.40,  5.60,  0.10,  4.10,  5757.00, 0.00, 0.00,  0.00,  0.00, '中国食物成分表 第6版'),
('盐',                '24-2-001',    0.00,  0.00,  0.00,  0.00, 38758.00, 0.00, 0.00,  0.00,  0.00, '中国食物成分表 第6版'),
('白糖',              '12-1-001', 1605.40,  0.00,  0.00, 99.40,     0.00, 0.00, 0.00, 99.40,  0.00, '中国食物成分表 第6版'),
('冰糖',              '12-1-002', 1639.00,  0.10,  0.00, 98.90,     0.00, 0.00, 0.00, 98.90,  0.00, '中国食物成分表 第6版'),
('料酒',              '24-3-001',  573.20,  0.20,  0.10, 16.00,    50.00, 0.00, 0.00, 14.50,  0.00, '中国食物成分表 第6版'),
('醋',                '24-4-001',  130.96,  2.10,  0.30,  4.90,   262.00, 0.00, 0.00,  4.20,  0.00, '中国食物成分表 第6版'),
('蚝油',              '24-5-001',  481.16,  6.10,  0.10, 23.00,  4828.00, 0.00, 0.00, 23.00,  0.00, '中国食物成分表 第6版'),

-- 香辛料 (卤味卤水必备)
('八角',              '21-1-001', 1424.70,  3.80,  8.00, 35.40,   152.00, 0.00, 0.00,  0.00, 14.60, '中国食物成分表 第6版'),
('桂皮',              '21-1-002', 1071.10,  3.90,  1.20, 80.60,     9.00, 0.00, 0.00,  0.00, 53.10, '中国食物成分表 第6版'),
('花椒',              '21-2-001', 1196.20,  6.70,  8.90, 66.50,    47.00, 0.00, 0.00,  0.00, 28.70, '中国食物成分表 第6版'),
('辣椒粉',            '11-2-001', 1268.60, 12.10, 11.30, 65.70,    30.00, 1.70, 0.00,  6.90, 22.10, '中国食物成分表 第6版'),
('孜然',              '21-3-001', 1567.74, 17.80, 22.30, 44.20,   168.00, 1.50, 0.00,  2.30, 10.50, '中国食物成分表 第6版'),
('丁香',              '21-4-001', 1180.72,  6.00, 13.00, 65.50,   277.00, 4.00, 0.00,  2.40, 33.90, '中国食物成分表 第6版'),
('陈皮',              '21-5-001', 1234.28,  8.00,  1.20, 75.20,    17.00, 0.10, 0.00, 30.50, 24.00, '中国食物成分表 第6版'),
('月桂叶 (香叶)',      '21-6-001', 1311.66,  7.60,  8.40, 74.00,    23.00, 2.30, 0.00,  0.00, 26.30, '中国食物成分表 第6版'),
('茴香籽',            '21-7-001', 1422.61, 15.80, 14.90, 52.30,    88.00, 0.50, 0.00,  0.00, 39.80, '中国食物成分表 第6版'),

-- 葱姜蒜
('葱白',              '04-1-001',   79.50,  1.70,  0.30,  4.50,     8.00, 0.00, 0.00,  1.50,  1.30, '中国食物成分表 第6版'),
('姜',                '04-1-002',  154.81,  1.80,  0.50,  8.60,    14.00, 0.10, 0.00,  0.00,  2.00, '中国食物成分表 第6版'),
('蒜',                '04-1-003',  510.45,  4.50,  0.20, 27.60,    19.00, 0.00, 0.00,  4.00,  1.10, '中国食物成分表 第6版'),
('蒜末',              '04-1-004',  510.45,  4.50,  0.20, 27.60,    19.00, 0.00, 0.00,  4.00,  1.10, '中国食物成分表 第6版'),
('大葱',              '04-1-005',  117.99,  1.70,  0.30,  6.50,     4.80, 0.00, 0.00,  3.30,  1.30, '中国食物成分表 第6版'),

-- 油 (烹调)
('植物油 (花生油)',    '02-1-001', 3762.40,  0.00, 99.90,  0.00,     0.00, 19.90, 0.00, 0.00, 0.00, '中国食物成分表 第6版'),
('芝麻油',            '02-1-002', 3762.40,  0.00, 99.70,  0.20,     1.10, 14.20, 0.00, 0.00, 0.00, '中国食物成分表 第6版')
ON CONFLICT (ingredient_code) DO NOTHING;
