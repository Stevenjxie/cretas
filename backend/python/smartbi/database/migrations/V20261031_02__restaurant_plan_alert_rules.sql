-- 2026-07-29 spec §3.1 卡 C1 — 预警计划化 (P1: SALES_SUMMARY 环比预警).
--
-- 统一原则 R1: 交互问答 / 缓存 / 晋升 / 预警 全部执行同一种 sealed QuerySpec.
-- 这张表存的是「定时执行的计划 + 阈值规则」中的**计划与阈值**部分.
--
-- WHY A DEDICATED TABLE (三个被否掉的替代方案, 别再重新提):
--   1. 坐 `ai_promoted_routes`: 装不下. 那张表 PK 是 (domain, normalized_phrase)
--      —— 键是「用户问句」, 预警规则挤进去会和聊天晋升路由撞 PK 互相 UPSERT
--      覆盖; `chk_ai_promoted_routes_source` 只放 ('flywheel','manual_seed');
--      没有 enabled/severity/threshold 列; **没有 DELETE policy** (那张表明写
--      "retiring a promotion is a reviewed superuser operation"), 租户停不掉
--      自己的预警; `hit_count` 注释明写 runtime read path 只读.
--   2. 坐 `business_config_overrides`: 那张表**全部 migration 零 RLS 痕迹**,
--      隔离全靠每条查询手写 WHERE factory_id. 预警规则决定「查什么数、给谁
--      推送」, 是租户级安全对象, 不能放在没有 RLS 兜底的表上.
--   3. 给 `business_config_overrides` 补 RLS: 它有现役消费方
--      (health_check_metrics.py 读 avg_ticket override / DynamicConfigResolver),
--      对活表 FORCE RLS 会让所有不设 GUC 的既有读取方直接读到 0 行 = prod 级破坏.
--
-- R1 仍然守住: 共享的是**计划契约**, 不是同一张表 —— `plan_json` 与
--   `ai_promoted_routes.plan_json` 是**同一种格式** (raw `_t3_llm_parse` 输出),
--   同一个 `_PLAN_VERSION`, 同一个 `_semantic_spec_from_t3` 编译器, 同一个
--   `_plan_rejection_reason` 校验器, 同一条 relative-time 存储约束.
--
-- WHAT `plan_json` HOLDS (load-bearing, read before editing a row):
--   RAW planner output contract — NOT a sealed QuerySpec. Time MUST stay a
--   structured/relative description (`time_range` 相对短语, 或 null); a concrete
--   date here would be replayed verbatim tomorrow and silently alert on a stale
--   window. `chk_plan_alert_rules_plan_no_dates` 在 DB 层再挡一道.
--
-- Version collision check against origin/main frontier:
--   git ls-tree origin/main backend/python/smartbi/database/migrations/ \
--     | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | tail -1
--   -> V20261031_01 (卡 A chat_session_state_summary), 所以 V20261031_02 在
--      frontier 之上. (V-series 是单调计数器, 不是墙上日期.)
--
-- Idempotent: CREATE ... IF NOT EXISTS / DROP POLICY IF EXISTS.

CREATE TABLE IF NOT EXISTS restaurant_plan_alert_rules (
    factory_id      VARCHAR(64)  NOT NULL,
    rule_code       VARCHAR(64)  NOT NULL,
    domain          VARCHAR(32)  NOT NULL DEFAULT 'restaurant',
    rule_name       TEXT         NOT NULL,
    -- 被回放的问句种子. 与 plan_json 一起喂给 _semantic_spec_from_t3, 等价于
    -- 晋升路由的 normalized_phrase —— 但这里它不是匹配键 (规则由 rule_code
    -- 标识), 只是编译计划时 resolver 需要的原文上下文.
    query_text      TEXT         NOT NULL,
    plan_json       JSONB        NOT NULL,
    plan_version    VARCHAR(64)  NOT NULL DEFAULT 'restaurant-query-plan-v2',
    -- 取数路径 (dotted) into the resolver execution receipt, e.g.
    -- 'comparison.revenue_change_pct'. P1 只允许 comparison.* (见 Python 侧
    -- _ALLOWED_METRIC_PATHS) —— 因为只有 meta.comparison 提供了稳定的数值出口
    -- 且自带 primary_no_data / baseline_no_data 诚实标记. kpis 不能用: 键名
    -- 分裂 (title/value/rawValue vs label/value/unit)、value 是格式化字符串、
    -- 10 个槽位硬编码 rawValue:0、下标随请求变、title 由数据拼、RBAC 双 None.
    metric_path     VARCHAR(64)  NOT NULL,
    threshold_op    VARCHAR(8)   NOT NULL,
    threshold_value NUMERIC      NOT NULL,
    severity        VARCHAR(16)  NOT NULL DEFAULT 'warning',
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    source          VARCHAR(32)  NOT NULL DEFAULT 'manual_seed',
    reviewed_by     VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT restaurant_plan_alert_rules_pkey
        PRIMARY KEY (factory_id, rule_code),
    CONSTRAINT chk_plan_alert_rules_code
        CHECK (rule_code <> '' AND rule_code = btrim(rule_code)),
    CONSTRAINT chk_plan_alert_rules_op
        CHECK (threshold_op IN ('lt', 'lte', 'gt', 'gte')),
    -- 只有 critical / warning 会成为 standing alert. 'info' 在
    -- RestaurantHealthAlertBridgeService#mapSeverity 里映射成 null 被跳过,
    -- 存一条永远不触发的规则等于骗人, 所以 DB 层直接不允许.
    CONSTRAINT chk_plan_alert_rules_severity
        CHECK (severity IN ('critical', 'warning')),
    CONSTRAINT chk_plan_alert_rules_source
        CHECK (source IN ('manual_seed', 'flywheel')),
    CONSTRAINT chk_plan_alert_rules_plan_object
        CHECK (jsonb_typeof(plan_json) = 'object'),
    -- 与 _plan_rejection_reason 的 'plan_contains_resolved_dates' 同义, DB 层
    -- 兜底: sealed spec 的具体日期永远不该被存下来.
    CONSTRAINT chk_plan_alert_rules_plan_no_dates
        CHECK (NOT (plan_json ? 'date_range')),
    -- planner 契约里 time_range 的 'absolute' 类型带 start/end 具体 ISO 日期
    -- (见 _parse_t3_time_range 的 absolute 分支). 交互问答里那是合法的 —— 用户
    -- 就问了那个区间; 但**定时**预警存了它, 明天照样对着同一个死窗口发预警,
    -- 永远不会前进. 相对/命名时间才有意义.
    CONSTRAINT chk_plan_alert_rules_time_not_absolute
        CHECK (COALESCE(plan_json->'time_range'->>'type', '') <> 'absolute')
);

-- 热读路径: "这个租户在这个计划契约版本下所有启用的规则".
CREATE INDEX IF NOT EXISTS idx_plan_alert_rules_factory_enabled
    ON restaurant_plan_alert_rules (factory_id, domain, plan_version)
    WHERE enabled;

-- ##########################################################################
-- ##  READ THIS BEFORE ADDING ANY CODE PATH THAT TOUCHES THIS TABLE.      ##
-- ##  '__internal__' MEANS THE OPPOSITE HERE THAN IT DOES EVERYWHERE ELSE.##
-- ##########################################################################
--
-- 1. ON THIS TABLE, '__internal__' IS FULL VISIBILITY AND FULL WRITE ACCESS.
--    Same deliberate asymmetry as V20261030_01 (ai_promoted_routes): the seed
--    CLI (scripts/restaurant-plan-alert-seed.py) and a future platform ops
--    console must operate across tenants.
--
-- 2. THIS IS THE REVERSE OF THE PLATFORM CONVENTION. The pool setup callback
--    pins app.factory_id to '__internal__' when no tenant is in context so an
--    RLS table returns ZERO ROWS -- the "safe default". On this table the
--    sentinel is a skeleton key instead of a locked door.
--
-- 3. THEREFORE: ANY NEW CODE THAT READS OR WRITES restaurant_plan_alert_rules
--    MUST SET app.factory_id EXPLICITLY (transaction-local) FOR THE TENANT IT
--    IS ACTING FOR. It may NOT rely on the pool's default. The runtime read
--    path (smartbi/gold/restaurant/plan_alert.py::load_plan_alert_rules) pins
--    the sweeping tenant inside an explicit `async with conn.transaction()` --
--    a bare `set_config(..., true)` outside a transaction is silently
--    discarded on this codebase's asyncpg pool and the row filter would then
--    fall back to the pool default '__internal__' = EVERY tenant's rules.
--
-- Unlike ai_promoted_routes there is NO 'global' scope: an alert rule always
-- belongs to exactly one tenant (it decides what gets queried and who gets
-- pushed), so the read policy is a plain tenant match.
ALTER TABLE restaurant_plan_alert_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE restaurant_plan_alert_rules FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS restaurant_plan_alert_rules_read ON restaurant_plan_alert_rules;
CREATE POLICY restaurant_plan_alert_rules_read ON restaurant_plan_alert_rules
    FOR SELECT
    USING (
        factory_id = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    );

DROP POLICY IF EXISTS restaurant_plan_alert_rules_insert ON restaurant_plan_alert_rules;
CREATE POLICY restaurant_plan_alert_rules_insert ON restaurant_plan_alert_rules
    FOR INSERT
    WITH CHECK (
        factory_id = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    );

DROP POLICY IF EXISTS restaurant_plan_alert_rules_update ON restaurant_plan_alert_rules;
CREATE POLICY restaurant_plan_alert_rules_update ON restaurant_plan_alert_rules
    FOR UPDATE
    USING (
        factory_id = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    )
    WITH CHECK (
        factory_id = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    );

-- Unlike ai_promoted_routes, DELETE IS granted + policied. 预警是会反复触发的
-- 长期承诺, 租户必须能停用 (enabled=false) 和删除自己的规则 —— 这正是
-- ai_promoted_routes 装不下预警规则的原因之一.
DROP POLICY IF EXISTS restaurant_plan_alert_rules_delete ON restaurant_plan_alert_rules;
CREATE POLICY restaurant_plan_alert_rules_delete ON restaurant_plan_alert_rules
    FOR DELETE
    USING (
        factory_id = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    );

-- GRANT DML (recurring grant-gap — the runner applies DDL as the postgres
-- superuser and does NOT auto-grant; a new smartbi table without this line
-- fails open with "permission denied for table" on every read.  Mirrors
-- V20261030_01 / V20260708_02 / V20260428_03.)
-- No sequence grant: the PK is natural (factory_id, rule_code).
GRANT SELECT, INSERT, UPDATE, DELETE ON restaurant_plan_alert_rules TO smartbi_user;

COMMENT ON TABLE restaurant_plan_alert_rules IS
    'spec §3.1 预警计划化: 定时执行的 relative-time QuerySpec + 阈值规则. plan_json 与 ai_promoted_routes.plan_json 同格式 (raw planner contract), 每次 sweep 对 TODAY 重新编译.';
COMMENT ON COLUMN restaurant_plan_alert_rules.plan_json IS
    'Raw planner output contract (_t3_llm_parse shape). Never a sealed spec, never a concrete date range.';
COMMENT ON COLUMN restaurant_plan_alert_rules.metric_path IS
    'Dotted path into the resolver execution receipt. P1 allowlist: comparison.* only (the one stable numeric outlet; kpis key names are not uniform).';
COMMENT ON COLUMN restaurant_plan_alert_rules.rule_code IS
    'Stable per-tenant rule id. Becomes the standing-alert businessEntityId as plan_alert:<rule_code> -- period-free by construction, which is what keeps dedup working.';

-- Verification (run after apply):
--   SELECT factory_id, rule_code, metric_path, threshold_op, threshold_value,
--          severity, enabled, plan_json->>'intent'
--     FROM restaurant_plan_alert_rules ORDER BY factory_id, rule_code;
--   SET ROLE smartbi_user;
--   SELECT set_config('app.factory_id', 'DEMO_REST', false);
--   SELECT count(*) FROM restaurant_plan_alert_rules;  -- expect only DEMO_REST rows
--   RESET ROLE;
--
-- Rollback:
--   DROP TABLE IF EXISTS restaurant_plan_alert_rules;
--   (the runtime fails open to "no rules" when the table is missing, so the
--    health-check report keeps working with DiagnosticsEngine diagnoses only)
