-- V20260910_01__gold_read_cache.sql
-- WS1 Task 2: result cache for the Gold read endpoints (/api/smartbi/gold/*).
--
-- qhj 餐饮 analytics 默认聚合"全部历史"(无日期范围)。全历史聚合每次扫全表偏重,
-- 故对成形的 Gold read response 做 SHA256-keyed 缓存。key 含角色 (role) → 不同
-- RBAC 视角(剥价/不剥价)各自缓存, 不跨角色泄漏。TTL 默认 6h, 上传新数据时上层
-- invalidate (narrative_cache 同模式)。
--
-- ⛔ 迁移 runner 以 postgres 超级用户跑 → 表归 postgres → 必须 GRANT 给 smartbi_user
-- (per feedback_smartbi_table_grant_gap: 漏 GRANT 会导致写路径 permission denied 被
--  fail-open 静默吞, 表 0 行无人发现; entity_resolution_history 已踩过 2 次)。
--
-- RLS: ENABLE + FORCE + 单 tenant_isolation 策略 (含 __internal__ sentinel 旁路, 供
-- 物化/内部任务跨租户操作, per #590)。另加 allow_prune_expired (DELETE) 让 cron
-- 在无租户上下文下清过期行 (per V20260426_04 模式)。

CREATE TABLE IF NOT EXISTS gold_read_cache (
    factory_id  VARCHAR(64) NOT NULL,
    cache_key   VARCHAR(64) NOT NULL,           -- SHA256 hexdigest of (endpoint|start|end|role|params)
    payload     JSONB       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (factory_id, cache_key)
);

CREATE INDEX IF NOT EXISTS idx_gold_read_cache_expires
    ON gold_read_cache (expires_at);

ALTER TABLE gold_read_cache ENABLE ROW LEVEL SECURITY;
ALTER TABLE gold_read_cache FORCE ROW LEVEL SECURITY;

-- DROP-then-CREATE 保证 re-apply 幂等 (CREATE POLICY 不支持 IF NOT EXISTS)。
DROP POLICY IF EXISTS tenant_isolation ON gold_read_cache;
CREATE POLICY tenant_isolation ON gold_read_cache
    USING (current_setting('app.factory_id', true) IS NULL
           OR current_setting('app.factory_id', true) = ''
           OR current_setting('app.factory_id', true) = '__internal__'
           OR factory_id = current_setting('app.factory_id', true))
    WITH CHECK (current_setting('app.factory_id', true) IS NULL
           OR current_setting('app.factory_id', true) = ''
           OR current_setting('app.factory_id', true) = '__internal__'
           OR factory_id = current_setting('app.factory_id', true));

-- cron pruner 在 __internal__ 上下文下也能 DELETE 过期行 (per V20260426_04)。
DROP POLICY IF EXISTS allow_prune_expired ON gold_read_cache;
CREATE POLICY allow_prune_expired ON gold_read_cache
    FOR DELETE
    USING (expires_at < NOW());

-- ⛔ GRANT (HARD, per feedback_smartbi_table_grant_gap): 不写则整个写路径死。
-- GRANT 幂等, 可安全重复 apply。runner 以 postgres 连 (表 owner) 故可授权。
GRANT SELECT, INSERT, UPDATE, DELETE ON gold_read_cache TO smartbi_user;

COMMENT ON TABLE gold_read_cache IS
  'Gold read 端点 (/api/smartbi/gold/*) 成形 response 缓存。'
  'cache_key = SHA256(endpoint|start|end|role|params) — role 入 key 防跨角色泄漏。'
  'TTL 默认 6h; 上传新数据时上层 invalidate。Added 2026-09-10 per qhj analytics WS1 Task 2.';
