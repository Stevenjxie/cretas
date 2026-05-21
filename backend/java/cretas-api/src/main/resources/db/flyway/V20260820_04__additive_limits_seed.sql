-- V20260820_04__additive_limits_seed.sql
--
-- Sprint 8 P3 食品安全 — GB 2760-2014 食品添加剂限量库 (2026-05-20)
-- 法定: GB 2760-2014《食品安全国家标准 食品添加剂使用标准》
--
-- V1 seed: 卤味 / 熟肉制品 (08.02 类目) 常用 30 种添加剂.
-- 后续 Sprint 9+ 扩展更多类目 (调味料 / 乳制品 / 烘焙 / ...).
--
-- 系统级表 — 无 factory_id, 国标对所有 factory 统一.
-- ON CONFLICT DO NOTHING — 幂等 re-run safe.

CREATE TABLE IF NOT EXISTS additive_limits (
    id BIGSERIAL PRIMARY KEY,
    additive_name VARCHAR(100) NOT NULL,
    additive_code VARCHAR(50) NOT NULL,
    food_category VARCHAR(100) NOT NULL,
    max_limit DECIMAL(19,4) NOT NULL,
    unit VARCHAR(20) NOT NULL,
    regulation_ref VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP,
    CONSTRAINT uk_additive_code_category UNIQUE (additive_code, food_category)
);

CREATE INDEX IF NOT EXISTS idx_additive_limits_lookup
    ON additive_limits(additive_code, food_category, active)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE additive_limits IS 'GB 2760-2014 食品添加剂限量库 — Sprint 8 P3';

-- ===== Seed: GB 2760-2014 卤味/熟肉类 (08.02) Top 30 常用添加剂 =====
-- max_limit = 0 表示按需适量使用 (GB 2760 标记"适量"), 系统层不限量校验

INSERT INTO additive_limits (additive_name, additive_code, food_category, max_limit, unit, regulation_ref) VALUES
('亚硝酸钠', 'INS 250', '08.02 熟肉制品', 30, 'mg/kg', 'GB 2760-2014 表 A.1'),
('亚硝酸钾', 'INS 249', '08.02 熟肉制品', 30, 'mg/kg', 'GB 2760-2014 表 A.1'),
('山梨酸', 'INS 200', '08.02 熟肉制品', 750, 'mg/kg', 'GB 2760-2014 表 A.1'),
('山梨酸钾', 'INS 202', '08.02 熟肉制品', 1000, 'mg/kg', 'GB 2760-2014 表 A.1'),
('苯甲酸', 'INS 210', '08.02 熟肉制品', 1000, 'mg/kg', 'GB 2760-2014 表 A.1'),
('苯甲酸钠', 'INS 211', '08.02 熟肉制品', 1000, 'mg/kg', 'GB 2760-2014 表 A.1'),
('双乙酸钠', 'INS 262', '08.02 熟肉制品', 3000, 'mg/kg', 'GB 2760-2014 表 A.1'),
('乳酸链球菌素', 'INS 234', '08.02 熟肉制品', 500, 'mg/kg', 'GB 2760-2014 表 A.1'),
('那他霉素', 'INS 235', '08.02 熟肉制品', 6, 'mg/kg', 'GB 2760-2014 表 A.1'),
('抗坏血酸', 'INS 300', '08.02 熟肉制品', 1000, 'mg/kg', 'GB 2760-2014 表 A.1'),
('异抗坏血酸', 'INS 315', '08.02 熟肉制品', 1000, 'mg/kg', 'GB 2760-2014 表 A.1'),
('抗坏血酸钠', 'INS 301', '08.02 熟肉制品', 1000, 'mg/kg', 'GB 2760-2014 表 A.1'),
('磷酸氢二钠', 'INS 339', '08.02 熟肉制品', 5000, 'mg/kg', 'GB 2760-2014 表 A.1'),
('多聚磷酸盐', 'INS 451', '08.02 熟肉制品', 5000, 'mg/kg', 'GB 2760-2014 表 A.1'),
('卡拉胶', 'INS 407', '08.02 熟肉制品', 20000, 'mg/kg', 'GB 2760-2014 表 A.2'),
('黄原胶', 'INS 415', '08.02 熟肉制品', 20000, 'mg/kg', 'GB 2760-2014 表 A.2'),
('海藻酸钠', 'INS 401', '08.02 熟肉制品', 10000, 'mg/kg', 'GB 2760-2014 表 A.2'),
('谷氨酸钠 MSG', 'INS 621', '08.02 熟肉制品', 0, 'mg/kg', 'GB 2760-2014 适量使用'),
('5-肌苷酸二钠 IMP', 'INS 631', '08.02 熟肉制品', 0, 'mg/kg', 'GB 2760-2014 适量使用'),
('5-鸟苷酸二钠 GMP', 'INS 627', '08.02 熟肉制品', 0, 'mg/kg', 'GB 2760-2014 适量使用'),
('焦糖色 III类', 'INS 150c', '08.02 熟肉制品', 50000, 'mg/kg', 'GB 2760-2014 表 A.1'),
('胭脂红', 'INS 124', '08.02 熟肉制品', 25, 'mg/kg', 'GB 2760-2014 表 A.1'),
('柠檬黄', 'INS 102', '08.02 熟肉制品', 100, 'mg/kg', 'GB 2760-2014 表 A.1'),
('诱惑红', 'INS 129', '08.02 熟肉制品', 100, 'mg/kg', 'GB 2760-2014 表 A.1'),
('辣椒红', 'INS 160c', '08.02 熟肉制品', 0, 'mg/kg', 'GB 2760-2014 适量使用'),
('红曲红', 'INS NA', '08.02 熟肉制品', 0, 'mg/kg', 'GB 2760-2014 适量使用'),
('焦磷酸钠', 'INS 450', '08.02 熟肉制品', 5000, 'mg/kg', 'GB 2760-2014 表 A.1'),
('三聚磷酸钠', 'INS 451i', '08.02 熟肉制品', 5000, 'mg/kg', 'GB 2760-2014 表 A.1'),
('六偏磷酸钠', 'INS 452', '08.02 熟肉制品', 5000, 'mg/kg', 'GB 2760-2014 表 A.1'),
('丁基羟基茴香醚 BHA', 'INS 320', '08.02 熟肉制品', 200, 'mg/kg', 'GB 2760-2014 表 A.1'),
('二丁基羟基甲苯 BHT', 'INS 321', '08.02 熟肉制品', 200, 'mg/kg', 'GB 2760-2014 表 A.1')
ON CONFLICT (additive_code, food_category) DO NOTHING;
