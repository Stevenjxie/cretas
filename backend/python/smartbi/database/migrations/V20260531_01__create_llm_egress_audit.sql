-- LLM 出境审计表 (P0 — 数据主权可证明). 2026-05-31.
--
-- 记录每次实际发往外部 LLM (DashScope/Zhipu...) 的出境事件: 哪个 call_site /
-- provider / model / 工厂, 是否脱敏 + 脱敏了多少敏感值, 脱敏后 prompt 的 sha256
-- (不存明文), 真实响应码。让"客户数据已脱敏出境 / 不喂公有 AI"可审计、可导出。
--
-- ⚠️ RLS 关键 (B1, 对齐 V20260515_01 防 Issue #590 复发):
--   后台 flush 用的 asyncpg 连接, pool setup (smartbi.tenant_ctx) 每次 checkout
--   都 SELECT set_config('app.factory_id', '__internal__', false)。所以 flush 时
--   GUC 永远是 '__internal__', 绝不会是 NULL/''。INSERT 策略**必须**把
--   '__internal__' 当作"无租户标记"放行, 否则每个 flush 周期被 RLS 拒绝, 审计表
--   一行都写不进 (这正是 smart_bi_llm_usage 踩过的 #590)。

CREATE TABLE IF NOT EXISTS smart_bi_llm_egress_audit (
    id              BIGSERIAL PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    call_site       TEXT NOT NULL,
    slot            TEXT,
    provider        TEXT NOT NULL,
    model           TEXT NOT NULL,
    factory_id      TEXT,
    sanitized       BOOLEAN NOT NULL DEFAULT FALSE,
    redacted_count  INTEGER NOT NULL DEFAULT 0,
    redacted_fields TEXT[],
    sent_field_keys TEXT[],
    data_window     TEXT,
    prompt_chars    INTEGER,
    prompt_sha256   TEXT,            -- 脱敏后 prompt 的 sha256, 不存明文
    status_code     INTEGER          -- 出境真实响应码 (200 / 403 / NULL=异常)
);

CREATE INDEX IF NOT EXISTS idx_llm_egress_audit_created  ON smart_bi_llm_egress_audit (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_llm_egress_audit_factory  ON smart_bi_llm_egress_audit (factory_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_llm_egress_audit_callsite ON smart_bi_llm_egress_audit (call_site, created_at DESC);

GRANT SELECT, INSERT ON TABLE smart_bi_llm_egress_audit TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE smart_bi_llm_egress_audit_id_seq TO smartbi_user;

ALTER TABLE smart_bi_llm_egress_audit ENABLE ROW LEVEL SECURITY;

-- INSERT 策略 — 与 V20260515_01 (smart_bi_llm_usage) 逐字同构, 含 __internal__ 三分支。
DROP POLICY IF EXISTS tenant_insert_egress ON smart_bi_llm_egress_audit;
CREATE POLICY tenant_insert_egress ON smart_bi_llm_egress_audit FOR INSERT WITH CHECK (
    -- Case 1: 系统行 (admin / migration / bg flush) — factory_id NULL 且 GUC 处于无租户标记
    (factory_id IS NULL
     AND (current_setting('app.factory_id', true) IS NULL
          OR current_setting('app.factory_id', true) = ''
          OR current_setting('app.factory_id', true) = '__internal__'))    -- ← B1 关键
    -- Case 2: 租户标记行 (bg flush 一条 llm_caller_context 带 factory_id 的记录)
    OR (factory_id IS NOT NULL AND (
        current_setting('app.factory_id', true) IS NULL
        OR current_setting('app.factory_id', true) = ''
        OR current_setting('app.factory_id', true) = '__internal__'         -- ← B1 关键
        OR factory_id = current_setting('app.factory_id', true)
    ))
);

-- SELECT 策略 — 租户只看自己 + 系统行; admin (GUC 无标记/__internal__) 看全部。
DROP POLICY IF EXISTS tenant_select_egress ON smart_bi_llm_egress_audit;
CREATE POLICY tenant_select_egress ON smart_bi_llm_egress_audit FOR SELECT USING (
    current_setting('app.factory_id', true) IS NULL
    OR current_setting('app.factory_id', true) = ''
    OR current_setting('app.factory_id', true) = '__internal__'
    OR factory_id = current_setting('app.factory_id', true)
    OR factory_id IS NULL
);

-- ── Verification (apply 后必跑, 防 #590) ─────────────────────────────────────
--   -- Test 1: bg flush sentinel 写系统行 (必 PASS — 这是 #590 修复点)
--   SET ROLE smartbi_user;
--   SELECT set_config('app.factory_id', '__internal__', false);
--   INSERT INTO smart_bi_llm_egress_audit (call_site, provider, model, factory_id, status_code)
--     VALUES ('verify-egress', 'dashscope', 'qwen-flash', NULL, 200);   -- 必 PASS
--
--   -- Test 2: bg flush 写 factory 标记行 (必 PASS)
--   SELECT set_config('app.factory_id', '__internal__', false);
--   INSERT INTO smart_bi_llm_egress_audit (call_site, provider, model, factory_id, status_code)
--     VALUES ('verify-egress', 'dashscope', 'qwen-flash', 'F001', 200);  -- 必 PASS
--
--   -- Test 3: 租户会话跨租户伪造 (必 FAIL)
--   SELECT set_config('app.factory_id', 'F001', false);
--   INSERT INTO smart_bi_llm_egress_audit (call_site, provider, model, factory_id)
--     VALUES ('verify-egress', 'dashscope', 'qwen-flash', 'F002');       -- 必 ERROR (RLS reject)
--
--   -- Cleanup (admin):
--   --   RESET ROLE; DELETE FROM smart_bi_llm_egress_audit WHERE call_site = 'verify-egress';
