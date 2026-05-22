-- Canvas-Thresholds Phase A P0-1 — factory_thresholds table + seed.
--
-- Provides per-factory overlay for ~19 hard-coded thresholds across 6 service classes:
--   1. InventoryHealthAnalysisServiceImpl  (8 keys: turnover/expiry/loss/aging/...)
--   2. IotDataServiceImpl                   (5 keys: cold-chain/normal/humidity)
--   3. CustomerCreditCheckServiceImpl       (1 key: warning ratio)
--   4. RecursiveBomExpansionService         (1 key: max depth)
--   5. ShortageAnalysisServiceImpl          (1 key: production lead days)
--   6. ComplexityRouterImpl                 (3 keys: fast / analysis / multi-agent)
--
-- factory_id semantics:
--   '*' = global default (resolver falls back here when no per-factory row found)
--   F001/F006/... = per-factory override (highest priority)
--
-- Resolver service (ThresholdResolverServiceImpl) reads with Caffeine cache TTL 5 min.

CREATE TABLE factory_thresholds (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

  factory_id VARCHAR(50) NOT NULL,
  threshold_key VARCHAR(100) NOT NULL,

  category VARCHAR(20) NOT NULL,
  value_type VARCHAR(20) NOT NULL,
  threshold_value VARCHAR(100) NOT NULL,
  default_value VARCHAR(100) NOT NULL,

  description VARCHAR(500),
  unit VARCHAR(20),
  min_value VARCHAR(100),
  max_value VARCHAR(100),

  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  version BIGINT NOT NULL DEFAULT 0,

  -- BaseEntity audit columns
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
  deleted_at TIMESTAMP NULL
);

-- Partial unique index — same (factory_id, threshold_key) cannot have two non-deleted rows.
-- Mirrors V20260622_99 partial-index Bug #3 fix pattern.
CREATE UNIQUE INDEX idx_factory_thresholds_unique
  ON factory_thresholds (factory_id, threshold_key)
  WHERE deleted_at IS NULL;

CREATE INDEX idx_factory_thresholds_factory_category
  ON factory_thresholds (factory_id, category)
  WHERE deleted_at IS NULL;

CREATE INDEX idx_factory_thresholds_key
  ON factory_thresholds (threshold_key)
  WHERE deleted_at IS NULL;

COMMENT ON TABLE factory_thresholds IS
  'Canvas-Thresholds Phase A — per-factory + global threshold overlay for 6 service classes.';
COMMENT ON COLUMN factory_thresholds.factory_id IS
  'Tenant scope; ''*'' = global fallback used when no per-factory row exists.';
COMMENT ON COLUMN factory_thresholds.threshold_value IS
  'Stored as VARCHAR; parsed at runtime via value_type (INTEGER / DECIMAL / DOUBLE / STRING).';
COMMENT ON COLUMN factory_thresholds.default_value IS
  'Service-class hard-coded baseline; used by /reset-to-default endpoint.';

-- ==================== Seed: global defaults (factory_id = '*') ====================
-- 19 baseline rows (matching the hard-coded constants in the 6 service classes).

INSERT INTO factory_thresholds
  (factory_id, threshold_key, category, value_type, threshold_value, default_value,
   description, unit, min_value, max_value)
VALUES
  -- INVENTORY (11 rows — InventoryHealthAnalysisServiceImpl)
  ('*', 'inventory.turnover.red',          'INVENTORY', 'DECIMAL', '6',  '6',
   '周转率红色预警阈值', '次/年',  '0',   '1000'),
  ('*', 'inventory.turnover.yellow',       'INVENTORY', 'DECIMAL', '12', '12',
   '周转率黄色预警阈值', '次/年',  '0',   '1000'),
  ('*', 'inventory.expiry.red',            'INVENTORY', 'DECIMAL', '15', '15',
   '临期风险率红色预警阈值', '%',  '0',   '100'),
  ('*', 'inventory.expiry.yellow',         'INVENTORY', 'DECIMAL', '10', '10',
   '临期风险率黄色预警阈值', '%',  '0',   '100'),
  ('*', 'inventory.loss.red',              'INVENTORY', 'DECIMAL', '5',  '5',
   '损耗率红色预警阈值', '%',     '0',   '100'),
  ('*', 'inventory.loss.yellow',           'INVENTORY', 'DECIMAL', '2',  '2',
   '损耗率黄色预警阈值', '%',     '0',   '100'),
  ('*', 'inventory.aging.fresh_days',      'INVENTORY', 'INTEGER', '30', '30',
   '库龄新鲜段上限', '天',         '0',   '3650'),
  ('*', 'inventory.aging.normal_days',     'INVENTORY', 'INTEGER', '60', '60',
   '库龄正常段上限', '天',         '0',   '3650'),
  ('*', 'inventory.aging.warning_days',    'INVENTORY', 'INTEGER', '90', '90',
   '库龄预警段上限', '天',         '0',   '3650'),
  ('*', 'inventory.expiry.warning_days',   'INVENTORY', 'INTEGER', '30', '30',
   '临期预警天数', '天',           '0',   '365'),
  ('*', 'inventory.expiry.high_risk_days', 'INVENTORY', 'INTEGER', '7',  '7',
   '高风险临期阈值', '天',         '0',   '365'),

  -- IOT (5 rows — IotDataServiceImpl)
  ('*', 'iot.cold_chain.temp_max',         'IOT',       'DOUBLE',  '-18.0', '-18.0',
   '冷链温度上限', '°C',           '-273', '0'),
  ('*', 'iot.normal.temp_min',             'IOT',       'DOUBLE',  '0.0',   '0.0',
   '常温温度下限', '°C',           '-50',  '100'),
  ('*', 'iot.normal.temp_max',             'IOT',       'DOUBLE',  '25.0',  '25.0',
   '常温温度上限', '°C',           '-50',  '100'),
  ('*', 'iot.humidity.min',                'IOT',       'DOUBLE',  '40.0',  '40.0',
   '湿度下限', '%',                '0',    '100'),
  ('*', 'iot.humidity.max',                'IOT',       'DOUBLE',  '70.0',  '70.0',
   '湿度上限', '%',                '0',    '100'),

  -- CREDIT (1 row — CustomerCreditCheckServiceImpl)
  ('*', 'credit.warning_ratio',            'CREDIT',    'DECIMAL', '0.80',  '0.80',
   '信用额度接近预警比 (used/limit)', '比例', '0',    '1'),

  -- BOM (1 row — RecursiveBomExpansionService)
  ('*', 'bom.max_depth',                   'BOM',       'INTEGER', '10',    '10',
   'BOM 递归展开最大深度', '级',   '1',    '50'),

  -- SHORTAGE (1 row — ShortageAnalysisServiceImpl)
  ('*', 'shortage.production_lead_days',   'SHORTAGE',  'INTEGER', '7',     '7',
   '生产建议默认 lead time', '天', '0',    '365'),

  -- AI (3 rows — ComplexityRouterImpl)
  ('*', 'ai.complexity.fast_threshold',    'AI',        'DOUBLE',  '0.3',   '0.3',
   'AI 复杂度 fast mode 阈值', '比例', '0', '1'),
  ('*', 'ai.complexity.analysis_threshold', 'AI',       'DOUBLE',  '0.6',   '0.6',
   'AI 复杂度 analysis mode 阈值', '比例', '0', '1'),
  ('*', 'ai.complexity.multi_agent_threshold', 'AI',    'DOUBLE',  '0.8',   '0.8',
   'AI 复杂度 multi-agent mode 阈值', '比例', '0', '1');

-- ==================== Seed: F001 + F006 per-factory copies ====================
-- 复制全局默认到 F001 / F006 各 22 行, 便于 UI 直接编辑. 删除/禁用任一行 resolver 自动回退到 '*'.

INSERT INTO factory_thresholds
  (factory_id, threshold_key, category, value_type, threshold_value, default_value,
   description, unit, min_value, max_value)
SELECT 'F001', threshold_key, category, value_type, threshold_value, default_value,
       description, unit, min_value, max_value
FROM factory_thresholds
WHERE factory_id = '*';

INSERT INTO factory_thresholds
  (factory_id, threshold_key, category, value_type, threshold_value, default_value,
   description, unit, min_value, max_value)
SELECT 'F006', threshold_key, category, value_type, threshold_value, default_value,
       description, unit, min_value, max_value
FROM factory_thresholds
WHERE factory_id = '*';
