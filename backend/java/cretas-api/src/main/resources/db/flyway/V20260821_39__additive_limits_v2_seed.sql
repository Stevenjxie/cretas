-- V20260821_39__additive_limits_v2_seed.sql
--
-- Sprint 9 P2.F 食品行业法定 — 添加剂限量 V2 (2026-05-21)
-- 法定: GB 2760-2014《食品安全国家标准 食品添加剂使用标准》
--
-- V2 升级 (基于 V1 V20260820_04 31 卤味 seed):
--   1. 跨食品类目扩展 — 烘焙 (07.02) + 乳制品 (01.xx) + 调味料 (12.xx) + 饮料 (14.xx)
--   2. 添加 aliases JSONB 字段 — 支持智能 fuzzy 匹配 (用户用俗名 / 化学名 / 英文名)
--   3. 50+ 新 entries (per F006 业务 + Cretas 食品垂直差异化)
--
-- ON CONFLICT (additive_code, food_category) DO NOTHING — 幂等 re-run safe, 不破坏 V1.
--
-- Pattern mirrors V20260820_04. 全表新加 aliases 列 nullable, 历史 entry 默认 NULL.

-- ===== 1. ALTER TABLE — 添加 aliases 字段 (V2 enhance) =====

ALTER TABLE additive_limits
    ADD COLUMN IF NOT EXISTS aliases JSONB;

COMMENT ON COLUMN additive_limits.aliases IS 'Sprint 9 P2.F — 添加剂俗名/化学名/英文名列表 JSONB array, e.g. ["亚硝酸钠","硝酸盐","Sodium Nitrite"]. Tool fuzzy 匹配用';

-- ===== 2. 回填 V1 entries 的 aliases (常用俗名/英文) =====

UPDATE additive_limits SET aliases = '["亚硝酸钠","Sodium Nitrite","硝酸钠"]'::jsonb
    WHERE additive_code = 'INS 250' AND aliases IS NULL;
UPDATE additive_limits SET aliases = '["山梨酸钾","Potassium Sorbate","钾盐山梨酸"]'::jsonb
    WHERE additive_code = 'INS 202' AND aliases IS NULL;
UPDATE additive_limits SET aliases = '["山梨酸","Sorbic Acid"]'::jsonb
    WHERE additive_code = 'INS 200' AND aliases IS NULL;
UPDATE additive_limits SET aliases = '["苯甲酸钠","Sodium Benzoate","安息香酸钠"]'::jsonb
    WHERE additive_code = 'INS 211' AND aliases IS NULL;
UPDATE additive_limits SET aliases = '["苯甲酸","Benzoic Acid","安息香酸"]'::jsonb
    WHERE additive_code = 'INS 210' AND aliases IS NULL;
UPDATE additive_limits SET aliases = '["味精","谷氨酸钠","MSG","Monosodium Glutamate"]'::jsonb
    WHERE additive_code = 'INS 621' AND aliases IS NULL;
UPDATE additive_limits SET aliases = '["卡拉胶","Carrageenan","角叉菜胶"]'::jsonb
    WHERE additive_code = 'INS 407' AND aliases IS NULL;
UPDATE additive_limits SET aliases = '["VC","维生素 C","Vitamin C","抗坏血酸"]'::jsonb
    WHERE additive_code = 'INS 300' AND aliases IS NULL;
UPDATE additive_limits SET aliases = '["BHA","丁基羟基茴香醚","Butylated Hydroxyanisole"]'::jsonb
    WHERE additive_code = 'INS 320' AND aliases IS NULL;
UPDATE additive_limits SET aliases = '["BHT","二丁基羟基甲苯","Butylated Hydroxytoluene"]'::jsonb
    WHERE additive_code = 'INS 321' AND aliases IS NULL;

-- ===== 3. V2 Seed: 跨食品类目扩展 50+ 国标 entries =====
-- max_limit = 0 表示按需适量使用 (GB 2760 标记"适量"), 系统层不限量校验

-- ----- 3.1 烘焙类 (07.02 糕点 + 07.03 饼干) — 15 项 -----

INSERT INTO additive_limits (additive_name, additive_code, food_category, max_limit, unit, regulation_ref, aliases) VALUES
('双乙酸钠', 'INS 262', '07.02 糕点', 4000, 'mg/kg', 'GB 2760-2014 表 A.1',
    '["双乙酸钠","Sodium Diacetate","醋酸钠双盐"]'::jsonb),
('丙酸钙', 'INS 282', '07.02 糕点', 2500, 'mg/kg', 'GB 2760-2014 表 A.1',
    '["丙酸钙","Calcium Propionate"]'::jsonb),
('丙酸钠', 'INS 281', '07.02 糕点', 2500, 'mg/kg', 'GB 2760-2014 表 A.1',
    '["丙酸钠","Sodium Propionate"]'::jsonb),
('焦亚硫酸钠', 'INS 223', '07.02 糕点', 50, 'mg/kg', 'GB 2760-2014 表 A.1',
    '["焦亚硫酸钠","Sodium Metabisulfite","SMS"]'::jsonb),
('苯甲酸钠', 'INS 211', '07.02 糕点', 1000, 'mg/kg', 'GB 2760-2014 表 A.1',
    '["苯甲酸钠","Sodium Benzoate"]'::jsonb),
('维生素 C', 'INS 300', '07.02 糕点', 0, 'mg/kg', 'GB 2760-2014 适量使用',
    '["VC","维生素 C","Vitamin C","抗坏血酸"]'::jsonb),
('维生素 E', 'INS 307', '07.02 糕点', 0, 'mg/kg', 'GB 2760-2014 适量使用',
    '["VE","维生素 E","Vitamin E","生育酚"]'::jsonb),
('茶多酚', 'INS NA-CT', '07.02 糕点', 400, 'mg/kg', 'GB 2760-2014 表 A.1',
    '["茶多酚","Tea Polyphenols","TP"]'::jsonb),
('单, 双甘油脂肪酸酯', 'INS 471', '07.02 糕点', 0, 'mg/kg', 'GB 2760-2014 适量使用',
    '["甘油脂肪酸酯","Mono- and Diglycerides","乳化剂"]'::jsonb),
('硬脂酰乳酸钠 SSL', 'INS 481', '07.02 糕点', 5000, 'mg/kg', 'GB 2760-2014 表 A.1',
    '["SSL","硬脂酰乳酸钠","Sodium Stearoyl Lactylate"]'::jsonb),
('溴酸钾 (已禁用)', 'INS 924', '07.02 糕点', 0, 'mg/kg', 'GB 2760-2014 已废止 — 不得使用',
    '["溴酸钾","Potassium Bromate","KBrO3"]'::jsonb),
('柠檬酸', 'INS 330', '07.02 糕点', 0, 'mg/kg', 'GB 2760-2014 适量使用',
    '["柠檬酸","Citric Acid"]'::jsonb),
('磷酸氢钙', 'INS 341ii', '07.02 糕点', 15000, 'mg/kg', 'GB 2760-2014 表 A.1',
    '["磷酸氢钙","Dicalcium Phosphate"]'::jsonb),
('过氧化苯甲酰', 'INS 928', '07.03 饼干', 0, 'mg/kg', 'GB 2760-2014 已废止 — 2011 起禁用',
    '["过氧化苯甲酰","Benzoyl Peroxide","BPO","面粉漂白剂"]'::jsonb),
('碳酸氢钠', 'INS 500', '07.02 糕点', 0, 'mg/kg', 'GB 2760-2014 适量使用',
    '["小苏打","碳酸氢钠","Baking Soda","NaHCO3"]'::jsonb)
ON CONFLICT (additive_code, food_category) DO NOTHING;

-- ----- 3.2 乳制品类 (01.05 酸乳 + 01.06 干酪 + 01.03 调制乳) — 10 项 -----

INSERT INTO additive_limits (additive_name, additive_code, food_category, max_limit, unit, regulation_ref, aliases) VALUES
('苯甲酸钠', 'INS 211', '01.05 酸乳', 500, 'mg/kg', 'GB 2760-2014 表 A.1',
    '["苯甲酸钠","Sodium Benzoate"]'::jsonb),
('山梨酸钾', 'INS 202', '01.05 酸乳', 1000, 'mg/kg', 'GB 2760-2014 表 A.1',
    '["山梨酸钾","Potassium Sorbate"]'::jsonb),
('那他霉素', 'INS 235', '01.06 干酪', 10, 'mg/kg', 'GB 2760-2014 表 A.1 (奶酪表面)',
    '["那他霉素","Natamycin","纳他霉素"]'::jsonb),
('双乙酸钠', 'INS 262', '01.06 干酪', 1000, 'mg/kg', 'GB 2760-2014 表 A.1',
    '["双乙酸钠","Sodium Diacetate"]'::jsonb),
('抗坏血酸', 'INS 300', '01.03 调制乳', 0, 'mg/kg', 'GB 2760-2014 适量使用',
    '["VC","抗坏血酸","Vitamin C"]'::jsonb),
('卡拉胶', 'INS 407', '01.03 调制乳', 1500, 'mg/kg', 'GB 2760-2014 表 A.1',
    '["卡拉胶","Carrageenan"]'::jsonb),
('果胶', 'INS 440', '01.05 酸乳', 0, 'mg/kg', 'GB 2760-2014 适量使用',
    '["果胶","Pectin"]'::jsonb),
('海藻酸钠', 'INS 401', '01.05 酸乳', 5000, 'mg/kg', 'GB 2760-2014 表 A.1',
    '["海藻酸钠","Sodium Alginate"]'::jsonb),
('黄原胶', 'INS 415', '01.05 酸乳', 0, 'mg/kg', 'GB 2760-2014 适量使用',
    '["黄原胶","Xanthan Gum","汉生胶"]'::jsonb),
('阿斯巴甜', 'INS 951', '01.05 酸乳', 1000, 'mg/kg', 'GB 2760-2014 表 A.1',
    '["阿斯巴甜","Aspartame","APM"]'::jsonb)
ON CONFLICT (additive_code, food_category) DO NOTHING;

-- ----- 3.3 调味料类 (12.04 酱油 + 12.03 醋 + 12.10 复合调味料) — 12 项 -----

INSERT INTO additive_limits (additive_name, additive_code, food_category, max_limit, unit, regulation_ref, aliases) VALUES
('苯甲酸钠', 'INS 211', '12.04 酱油', 1000, 'mg/kg', 'GB 2760-2014 表 A.1',
    '["苯甲酸钠","Sodium Benzoate"]'::jsonb),
('山梨酸钾', 'INS 202', '12.04 酱油', 1000, 'mg/kg', 'GB 2760-2014 表 A.1',
    '["山梨酸钾","Potassium Sorbate"]'::jsonb),
('焦糖色 III类', 'INS 150c', '12.04 酱油', 0, 'mg/kg', 'GB 2760-2014 适量使用',
    '["焦糖色","Caramel III","糖色"]'::jsonb),
('三氯蔗糖', 'INS 955', '12.04 酱油', 250, 'mg/kg', 'GB 2760-2014 表 A.1',
    '["三氯蔗糖","Sucralose","蔗糖素"]'::jsonb),
('谷氨酸钠 MSG', 'INS 621', '12.10 复合调味料', 0, 'mg/kg', 'GB 2760-2014 适量使用',
    '["味精","谷氨酸钠","MSG"]'::jsonb),
('5-肌苷酸二钠 IMP', 'INS 631', '12.10 复合调味料', 0, 'mg/kg', 'GB 2760-2014 适量使用',
    '["IMP","肌苷酸钠","Disodium Inosinate"]'::jsonb),
('5-鸟苷酸二钠 GMP', 'INS 627', '12.10 复合调味料', 0, 'mg/kg', 'GB 2760-2014 适量使用',
    '["GMP","鸟苷酸钠","Disodium Guanylate"]'::jsonb),
('苯甲酸钠', 'INS 211', '12.03 醋', 1000, 'mg/kg', 'GB 2760-2014 表 A.1',
    '["苯甲酸钠","Sodium Benzoate"]'::jsonb),
('山梨酸钾', 'INS 202', '12.03 醋', 1000, 'mg/kg', 'GB 2760-2014 表 A.1',
    '["山梨酸钾","Potassium Sorbate"]'::jsonb),
('焦糖色 IV类', 'INS 150d', '12.04 酱油', 0, 'mg/kg', 'GB 2760-2014 适量使用',
    '["焦糖色","Caramel IV"]'::jsonb),
('双乙酸钠', 'INS 262', '12.10 复合调味料', 2500, 'mg/kg', 'GB 2760-2014 表 A.1',
    '["双乙酸钠","Sodium Diacetate"]'::jsonb),
('安赛蜜', 'INS 950', '12.04 酱油', 500, 'mg/kg', 'GB 2760-2014 表 A.1',
    '["安赛蜜","Acesulfame K","乙酰磺胺酸钾","AK 糖"]'::jsonb)
ON CONFLICT (additive_code, food_category) DO NOTHING;

-- ----- 3.4 饮料类 (14.02 果蔬汁 + 14.04 碳酸 + 14.05 茶饮) — 15 项 -----

INSERT INTO additive_limits (additive_name, additive_code, food_category, max_limit, unit, regulation_ref, aliases) VALUES
('苯甲酸钠', 'INS 211', '14.04 碳酸饮料', 200, 'mg/kg', 'GB 2760-2014 表 A.1',
    '["苯甲酸钠","Sodium Benzoate"]'::jsonb),
('山梨酸钾', 'INS 202', '14.04 碳酸饮料', 500, 'mg/kg', 'GB 2760-2014 表 A.1',
    '["山梨酸钾","Potassium Sorbate"]'::jsonb),
('阿斯巴甜', 'INS 951', '14.04 碳酸饮料', 600, 'mg/kg', 'GB 2760-2014 表 A.1',
    '["阿斯巴甜","Aspartame","APM"]'::jsonb),
('三氯蔗糖', 'INS 955', '14.04 碳酸饮料', 250, 'mg/kg', 'GB 2760-2014 表 A.1',
    '["三氯蔗糖","Sucralose","蔗糖素"]'::jsonb),
('安赛蜜', 'INS 950', '14.04 碳酸饮料', 300, 'mg/kg', 'GB 2760-2014 表 A.1',
    '["安赛蜜","Acesulfame K","AK 糖"]'::jsonb),
('糖精钠', 'INS 954', '14.04 碳酸饮料', 150, 'mg/kg', 'GB 2760-2014 表 A.1',
    '["糖精钠","Sodium Saccharin","糖精"]'::jsonb),
('焦糖色 IV类', 'INS 150d', '14.04 碳酸饮料', 0, 'mg/kg', 'GB 2760-2014 适量使用',
    '["焦糖色","Caramel IV","可乐色"]'::jsonb),
('维生素 C', 'INS 300', '14.02 果蔬汁', 0, 'mg/kg', 'GB 2760-2014 适量使用',
    '["VC","抗坏血酸","Vitamin C"]'::jsonb),
('柠檬酸', 'INS 330', '14.02 果蔬汁', 0, 'mg/kg', 'GB 2760-2014 适量使用',
    '["柠檬酸","Citric Acid"]'::jsonb),
('果胶', 'INS 440', '14.02 果蔬汁', 0, 'mg/kg', 'GB 2760-2014 适量使用',
    '["果胶","Pectin"]'::jsonb),
('胭脂红', 'INS 124', '14.04 碳酸饮料', 50, 'mg/kg', 'GB 2760-2014 表 A.1',
    '["胭脂红","Ponceau 4R","胭脂红 4R"]'::jsonb),
('柠檬黄', 'INS 102', '14.04 碳酸饮料', 100, 'mg/kg', 'GB 2760-2014 表 A.1',
    '["柠檬黄","Tartrazine","酒石黄"]'::jsonb),
('诱惑红', 'INS 129', '14.04 碳酸饮料', 100, 'mg/kg', 'GB 2760-2014 表 A.1',
    '["诱惑红","Allura Red","Allura Red AC"]'::jsonb),
('日落黄', 'INS 110', '14.04 碳酸饮料', 100, 'mg/kg', 'GB 2760-2014 表 A.1',
    '["日落黄","Sunset Yellow FCF"]'::jsonb),
('茶多酚', 'INS NA-CT', '14.05 茶饮料', 0, 'mg/kg', 'GB 2760-2014 适量使用',
    '["茶多酚","Tea Polyphenols","TP"]'::jsonb)
ON CONFLICT (additive_code, food_category) DO NOTHING;

-- ===== 4. 验证 (COMMENT) =====
-- SELECT food_category, COUNT(*) FROM additive_limits WHERE deleted_at IS NULL GROUP BY food_category ORDER BY food_category;
-- 预期: 08.02 熟肉制品=31 (V1) + 07.02/07.03/01.05/01.06/01.03/12.04/12.03/12.10/14.04/14.02/14.05 = 52 V2 = 83 total
