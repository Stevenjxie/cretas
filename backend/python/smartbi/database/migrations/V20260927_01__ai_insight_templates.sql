-- V20260927_01__ai_insight_templates.sql
-- 图表自动洞察 (Chart Auto-Insight) Phase 1 — 洞察模板蒸馏库。
--
-- 背景: 混合三层 resolver (前端 Tier1 rules-first 瞬时 + 后端 Tier2 库查/LLM +
--   Tier3 蒸馏提升). 此表是 Tier2a/Tier3 的核心存储:
--   - Tier2b: LLM 首次命中 → 捕获结构化模板 (is_active=false, proposal_count++)
--   - Tier3: proposal_count >= PROMOTE_THRESHOLD AND 全参数化验证 AND 防毒 → is_active=true
--   - Tier2a: is_active=true 模板直接填占位符 → 0 LLM 成本瞬时答案
--
-- 签名 (signature_hash): SHA256(chartType|xDim|yMetric|aggregation|domain|dataPattern|permissionTier|factoryId)
--   permissionTier 进签名确保跨权限边界不共享模板 (RBAC 红线 §2.6)。
--
-- 模板参数化: insight_template.finding_tpl 等必须全 {slot} 占位符
--   (validate_template_parameterization 拒绝含字面品名/绝对数字的模板)。
--   proposal_count 按 finding_tpl 规范化哈希去重统计 (多数投票)。
--
-- 演示租户: PROMOTE_THRESHOLD=1 → 首次 LLM 后立即提升 → 刷新即 Tier2a 瞬时命中 (15s 闭环)。
--
-- collision check: git ls-tree origin/main backend/python/smartbi/database/migrations/ |
--   grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | tail -1 → V20260926_01 (frontier V20260926).
--   V20260927_01 严格高于现有最大版本, out-of-order=false 不会静默跳过。
--
-- 幂等: CREATE TABLE IF NOT EXISTS + UNIQUE(signature_hash, factory_id) 配 ON CONFLICT。

CREATE TABLE IF NOT EXISTS ai_insight_templates (
    id                 BIGSERIAL     PRIMARY KEY,

    -- 租户隔离 (RLS 依赖此列, Python 端从 JWT 取 factoryId 写入, 不信请求体)
    factory_id         VARCHAR(100)  NOT NULL,

    -- SHA256 hex: chartType|xDim|yMetric|aggregation|domain|dataPattern|permissionTier|factoryId
    -- permissionTier (finance_visible/price_hidden/finance_hidden) 进签名确保 RBAC 隔离
    signature_hash     VARCHAR(64)   NOT NULL,

    -- 完整签名分量 JSON, 供 Tier3 蒸馏防毒守卫及 admin 可读性
    -- {chartType, xDim, yMetric, aggregation, domain, dataPattern, permissionTier}
    chart_signature    JSONB         NOT NULL,

    -- 结构化参数化模板, Tier2b LLM 返结构化 JSON (非散文)
    -- {finding_tpl, implication_tpl?, suggestion_tpl?, slots[]}
    -- 示例: {"finding_tpl": "营收最高 {topName} 占 {topShare}%, 是末位 {botName} 的 {ratio} 倍",
    --         "slots": ["topName","topShare","botName","ratio"]}
    insight_template   JSONB         NOT NULL,

    -- 权限标记: null=无约束 / 'finance:read_write'=含绝对¥值需高权限
    -- Tier2a 填充前校验 caller 权限; 不足 → 降级到无绝对值变体
    required_permission VARCHAR(100),

    -- LLM_FALLBACK (Tier2b 初始捕获) / FIXED_TEMPLATE (手工录入)
    source_type        VARCHAR(30)   NOT NULL
        CHECK (source_type IN ('LLM_FALLBACK', 'FIXED_TEMPLATE')),

    -- LLM 给出的置信度 (0.0000~1.0000); FIXED_TEMPLATE 可 null
    confidence         NUMERIC(5,4),

    -- Tier2a 命中计数 (用于成本曲线统计, 不参与提升判断)
    hit_count          INTEGER       NOT NULL DEFAULT 0,

    -- 提升投票数: 同签名+规范化 finding_tpl 哈希去重后的 LLM 提案数
    -- >= PROMOTE_THRESHOLD AND 全参数化验证 AND 防毒 → is_active=true
    proposal_count     INTEGER       NOT NULL DEFAULT 0,

    -- false=候选未提升 (Tier2b 捕获但未达阈值或未过防毒)
    -- true=已提升, Tier2a 直接命中 (is_active 即缓存键)
    is_active          BOOLEAN       NOT NULL DEFAULT FALSE,

    -- 含 suggestion_tpl 的模板需人工核实后才自动提升 (防错洞察永久入库)
    -- 仅 finding_tpl 模板可纯自动提升 (is_verified=false 亦可)
    is_verified        BOOLEAN       NOT NULL DEFAULT FALSE,

    created_at         TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP     NOT NULL DEFAULT NOW(),

    -- 模板与租户的联合唯一键: 同一租户同签名只存一份 (ON CONFLICT DO UPDATE 更新计数)
    CONSTRAINT uk_ait_sig_factory UNIQUE (signature_hash, factory_id)
);

-- RLS: 租户隔离 (app.factory_id GUC, 与所有 smartbi 表一致)
ALTER TABLE ai_insight_templates ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_insight_templates FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON ai_insight_templates;
CREATE POLICY tenant_isolation ON ai_insight_templates
    USING  (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));

-- 主查询索引: Tier2a 按签名查活跃模板 (高频热路径)
CREATE INDEX IF NOT EXISTS idx_ait_sig
    ON ai_insight_templates (signature_hash);

-- 管理/蒸馏查询索引: 按租户+状态列待提升候选
CREATE INDEX IF NOT EXISTS idx_ait_factory_active
    ON ai_insight_templates (factory_id, is_active);

-- GRANT DML + sequence (recurring grant-gap — 新 smartbi 表必带, 否则 fail-open 静默 0 行)
GRANT SELECT, INSERT, UPDATE, DELETE ON ai_insight_templates TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE ai_insight_templates_id_seq TO smartbi_user;

-- Rollback:
--   DROP TABLE IF EXISTS ai_insight_templates;
