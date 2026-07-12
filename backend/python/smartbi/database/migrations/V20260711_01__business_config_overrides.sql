-- ============================================================
-- 餐饮 SmartBI 动态化架构 — 数据库迁移
-- 原始日期: 2026-04-08 (作者: 20260408_smartbi_restaurant_dynamic.sql)
-- 数据库: smartbi_db (test) / smartbi_prod_db (prod)
--
-- ⛔ 2026-07-11 重新落盘为 V*.sql: 原文件 `20260408_smartbi_restaurant_dynamic.sql`
-- 文件名不匹配迁移 runner 的 glob (`scripts/migrations/apply-smartbi-migrations.sh`
-- 只扫描 `V*.sql`), 导致这份 DDL 从未在任何环境真正 apply 过 —— prod 上
-- business_config_overrides 等 8 张表全部 MISSING (2026-07-11 核实确认: 0/8
-- 存在)。后果: business_config_overrides 缺失 → 依赖它的 resolver 读不到任何
-- override → `avg_ticket_vs_target` 对**所有**餐饮租户永久 skip (跨租户
-- infra bug, 不只影响 DEMO_REST)。
--
-- 本文件是内容对齐的 V-命名副本 (DDL 与原文件逐字相同, 全部
-- CREATE TABLE/INDEX IF NOT EXISTS 幂等), 让 runner 能正确发现 + apply +
-- 记入 smartbi_migrations tracker。原文件保留不删 (避免打乱其他可能引用它
-- 路径的文档/脚本), 但不再是权威来源 —— 这份 V20260711_01 才是。
--
-- 范围:
--   1. 共享层: business_config_overrides (4 层覆盖, domain 严格隔离)
--   2. 餐饮: restaurant_dish_alias (改进 1 命名归一)
--   3. 餐饮: restaurant_cogs_overrides (改进 6 渠道毛利率)
--   4. 餐饮 Layer A: restaurant_loss_factor_baselines (BOM 三层学习)
--   5. 餐饮 Layer A: restaurant_category_price_calibrated
--   6. 餐饮 Layer A: restaurant_substitution_log (替代记录, ASR + 手动)
--   7. 餐饮 Layer B: restaurant_cost_anomalies (异常告警)
--   8. 共享: alias_review_queue (半自动审核)
--
-- 隔离原则:
--   - business_config_overrides.domain 必填, 不允许跨 domain 查找
--   - config_key 强制带 domain 前缀 (CHECK 约束)
--   - 工厂线表前缀 factory_*, 餐饮线表前缀 restaurant_*, 共享层无前缀
-- ============================================================


-- ============================================================
-- 1. 共享层: 4 层配置覆盖 (含 domain 隔离)
-- ============================================================

CREATE TABLE IF NOT EXISTS business_config_overrides (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(64) NOT NULL,
    store_id VARCHAR(64) NULL,                          -- NULL = 工厂级覆盖, 非 NULL = 门店级
    domain VARCHAR(16) NOT NULL,                        -- 'restaurant' | 'factory'
    config_key VARCHAR(255) NOT NULL,                   -- 必须以 'restaurant.' 或 'factory.' 开头
    config_value JSONB NOT NULL,                        -- 灵活 schema (number/string/object)
    source VARCHAR(32) NOT NULL,                        -- 'benchmark_default'|'monthly_upload'|'manual_input'|'audit_confirmed'|'session_temporary'
    confidence VARCHAR(16) NULL,                        -- 'high'|'medium'|'low'
    effective_from DATE NULL,
    effective_to DATE NULL,
    created_by VARCHAR(64),
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    deleted_at TIMESTAMP NULL,

    -- 隔离铁律: domain 取值约束
    CONSTRAINT chk_config_domain
        CHECK (domain IN ('restaurant', 'factory')),

    -- 隔离铁律: config_key 必须以 domain 前缀开头, 防止数据污染
    CONSTRAINT chk_config_key_prefix
        CHECK (config_key LIKE domain || '.%')
);

CREATE INDEX IF NOT EXISTS idx_config_lookup
    ON business_config_overrides (factory_id, domain, config_key)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_config_unique
    ON business_config_overrides (factory_id, domain, config_key, COALESCE(store_id, ''), COALESCE(effective_from, '1900-01-01'::DATE))
    WHERE deleted_at IS NULL;

COMMENT ON TABLE business_config_overrides IS '4 层配置覆盖表 — 跨 domain 共享, 通过 domain 列严格隔离';
COMMENT ON COLUMN business_config_overrides.domain IS '严格隔离: restaurant 或 factory, 不允许 NULL 或跨 domain';
COMMENT ON COLUMN business_config_overrides.config_key IS '必须以 domain 前缀开头, 例: restaurant.cogs.美团外卖';


-- ============================================================
-- 2. 餐饮: 菜品命名归一 (改进 1 半自动审核结果)
-- ============================================================

CREATE TABLE IF NOT EXISTS restaurant_dish_alias (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(64) NOT NULL,
    original_name VARCHAR(255) NOT NULL,                -- 原始 SKU 名 (例: '#招牌青花椒鱼(微麻微辣)(一吃)#')
    canonical_name VARCHAR(255) NOT NULL,               -- 客户审核后的规范名 (例: '招牌青花椒鱼')
    confidence DECIMAL(3,2),                            -- 系统建议时的置信度 (0-1)
    review_source VARCHAR(32) NOT NULL,                 -- 'rule_layer'|'levenshtein'|'embedding'|'manual'
    reviewed_by VARCHAR(64),
    reviewed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_dish_alias_unique
    ON restaurant_dish_alias (factory_id, original_name);

CREATE INDEX IF NOT EXISTS idx_dish_alias_canonical
    ON restaurant_dish_alias (factory_id, canonical_name);

COMMENT ON TABLE restaurant_dish_alias IS '餐饮菜品命名归一 — 客户审核后持久化, 数据加载层 apply';


-- ============================================================
-- 3. 餐饮: COGS 分层覆盖 (改进 6 渠道毛利率)
-- ============================================================

CREATE TABLE IF NOT EXISTS restaurant_cogs_overrides (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(64) NOT NULL,
    store_id VARCHAR(64) NULL,
    scope VARCHAR(16) NOT NULL,                         -- 'channel'|'category'|'sku'
    scope_key VARCHAR(255) NOT NULL,                    -- '美团外卖' | '火锅锅底' | '招牌青花椒鱼'
    cogs_amount DECIMAL(12,2),                          -- 绝对值 (元)
    cogs_pct DECIMAL(5,4),                              -- 占比 (0-1)
    source VARCHAR(32) NOT NULL,                        -- 'category_baseline'|'monthly_calibrated'|'sku_form'|'manual_override'
    confidence VARCHAR(16),                             -- 'high'|'medium'|'low'
    effective_month DATE,
    notes TEXT,
    created_at TIMESTAMP DEFAULT NOW(),

    CONSTRAINT chk_cogs_scope CHECK (scope IN ('channel', 'category', 'sku'))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_cogs_overrides_unique
    ON restaurant_cogs_overrides (factory_id, COALESCE(store_id, ''), scope, scope_key, COALESCE(effective_month, '1900-01-01'::DATE));

COMMENT ON TABLE restaurant_cogs_overrides IS '餐饮 COGS 4 层覆盖 — 渠道/品类/SKU 三种粒度, 4 种来源';


-- ============================================================
-- 4. 餐饮 Layer A: 损耗系数 baseline
-- ============================================================

CREATE TABLE IF NOT EXISTS restaurant_loss_factor_baselines (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(64) NOT NULL,
    store_id VARCHAR(64) NOT NULL,
    category VARCHAR(128) NOT NULL,                     -- 例: '叶菜组' '肉类涮品' '锅底'
    month DATE NOT NULL,                                -- 月度聚合 (取每月 1 号)
    loss_pct DECIMAL(5,4) NOT NULL,                     -- 学到的损耗率 (0-1)
    sample_size INT NOT NULL,                           -- 该组当月数据点数
    confidence VARCHAR(16) NOT NULL,                    -- 'high' (≥6 月) | 'medium' (3-5 月) | 'low' (<3 月)
    r_squared DECIMAL(5,4),                             -- 拟合质量 (NULL 当样本不足)
    raw_loss_pct DECIMAL(5,4),                          -- 异常剔除前的原始值 (审计用)
    capped BOOLEAN DEFAULT FALSE,                       -- 是否被异常剔除限制
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_loss_factor_unique
    ON restaurant_loss_factor_baselines (factory_id, store_id, category, month);

CREATE INDEX IF NOT EXISTS idx_loss_factor_lookup
    ON restaurant_loss_factor_baselines (factory_id, store_id, category, month DESC);

COMMENT ON TABLE restaurant_loss_factor_baselines IS 'Layer A: 损耗系数按 (店 × 月份 × 品类) 自学习';


-- ============================================================
-- 5. 餐饮 Layer A: 类目均价校准
-- ============================================================

CREATE TABLE IF NOT EXISTS restaurant_category_price_calibrated (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(64) NOT NULL,
    category VARCHAR(128) NOT NULL,                     -- '叶菜组' '锅底'
    month DATE NOT NULL,
    avg_price_per_unit DECIMAL(12,4) NOT NULL,
    unit VARCHAR(16) NOT NULL,                          -- '元/份' '元/斤' '元/锅'
    sample_size INT NOT NULL,                           -- 当月该品类采购批次
    yaml_default DECIMAL(12,4),                         -- 行业 YAML 默认值, 用于对比
    delta_pct DECIMAL(5,4),                             -- (calibrated - default) / default
    confidence VARCHAR(16),
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_category_price_unique
    ON restaurant_category_price_calibrated (factory_id, category, month);

COMMENT ON TABLE restaurant_category_price_calibrated IS 'Layer A: 类目均价按月校准, 覆盖行业 YAML 默认值';


-- ============================================================
-- 6. 餐饮 Layer A: 替代记录 (ASR + 手动表单双路径)
-- ============================================================

CREATE TABLE IF NOT EXISTS restaurant_substitution_log (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(64) NOT NULL,
    store_id VARCHAR(64) NOT NULL,
    sku_name VARCHAR(255) NOT NULL,                     -- '回锅肉' (建议归一后的名)
    original_ingredient VARCHAR(128) NOT NULL,          -- '卷心菜'
    replacement_ingredient VARCHAR(128) NOT NULL,       -- '白菜'
    occurred_at DATE NOT NULL,                          -- 实际替代发生日期
    source VARCHAR(16) NOT NULL,                        -- 'voice_note'|'manual_form'|'imported'
    voice_transcript TEXT,                              -- 讯飞 ASR 原始转写文本 (审计用)
    confidence DECIMAL(3,2),                            -- ASR 置信度 (手动表单时为 1.0)
    notes TEXT,
    created_by VARCHAR(64),
    created_at TIMESTAMP DEFAULT NOW(),

    CONSTRAINT chk_sub_source CHECK (source IN ('voice_note', 'manual_form', 'imported'))
);

CREATE INDEX IF NOT EXISTS idx_substitution_lookup
    ON restaurant_substitution_log (factory_id, store_id, sku_name, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_substitution_aggregate
    ON restaurant_substitution_log (factory_id, store_id, sku_name, original_ingredient, replacement_ingredient);

COMMENT ON TABLE restaurant_substitution_log IS 'Layer A: 替代记录 — 讯飞 ASR + 手动表单双路径写入';


-- ============================================================
-- 7. 餐饮 Layer B: 异常告警 (持久化用于历史追溯)
-- ============================================================

CREATE TABLE IF NOT EXISTS restaurant_cost_anomalies (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(64) NOT NULL,
    store_id VARCHAR(64) NULL,
    anomaly_type VARCHAR(32) NOT NULL,                  -- 'mom_jump'|'cross_store_variance'|'benchmark_breach'
    severity VARCHAR(16) NOT NULL,                      -- 'red'|'yellow'|'info'
    sku_name VARCHAR(255),
    category VARCHAR(128),
    current_value DECIMAL(12,4),
    baseline_value DECIMAL(12,4),
    delta_pct DECIMAL(5,4),
    description_zh TEXT NOT NULL,
    suggested_action_zh TEXT,
    detected_month DATE NOT NULL,
    detected_at TIMESTAMP DEFAULT NOW(),
    resolved_at TIMESTAMP NULL,
    resolved_by VARCHAR(64),
    resolution_notes TEXT,

    CONSTRAINT chk_anomaly_type CHECK (anomaly_type IN ('mom_jump', 'cross_store_variance', 'benchmark_breach')),
    CONSTRAINT chk_anomaly_severity CHECK (severity IN ('red', 'yellow', 'info'))
);

CREATE INDEX IF NOT EXISTS idx_anomalies_unresolved
    ON restaurant_cost_anomalies (factory_id, severity, detected_at DESC)
    WHERE resolved_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_anomalies_history
    ON restaurant_cost_anomalies (factory_id, sku_name, detected_month DESC);

COMMENT ON TABLE restaurant_cost_anomalies IS 'Layer B: 动态异常告警 — 即使 Layer A 精度 ±10%, 仍能抓 ±50% 跳变';


-- ============================================================
-- 8. 共享: 半自动审核队列 (改进 1 命名归一审核)
-- ============================================================

CREATE TABLE IF NOT EXISTS alias_review_queue (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(64) NOT NULL,
    domain VARCHAR(16) NOT NULL,                        -- 隔离: 'restaurant'|'factory'
    proposal_type VARCHAR(32) NOT NULL,                 -- 'merge_dishes'|'merge_skus'
    proposal_data JSONB NOT NULL,                       -- 系统建议 (含 canonical 候选 + 原始名列表 + 置信度)
    status VARCHAR(16) DEFAULT 'pending',               -- 'pending'|'approved'|'rejected'|'modified'
    reviewed_by VARCHAR(64),
    reviewed_at TIMESTAMP,
    decision_data JSONB,                                -- 客户最终决定 (含拒绝/部分合并)
    created_at TIMESTAMP DEFAULT NOW(),

    CONSTRAINT chk_review_domain CHECK (domain IN ('restaurant', 'factory')),
    CONSTRAINT chk_review_status CHECK (status IN ('pending', 'approved', 'rejected', 'modified'))
);

CREATE INDEX IF NOT EXISTS idx_review_queue_pending
    ON alias_review_queue (factory_id, domain, status, created_at)
    WHERE status = 'pending';

COMMENT ON TABLE alias_review_queue IS '半自动命名归一审核队列 — 餐饮工厂共享, domain 隔离';


-- ============================================================
-- 触发器: 自动更新 updated_at
-- ============================================================

-- 自包含的 update_updated_at 函数 (如已存在则跳过)
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trigger_config_overrides_updated_at ON business_config_overrides;
CREATE TRIGGER trigger_config_overrides_updated_at
    BEFORE UPDATE ON business_config_overrides
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
