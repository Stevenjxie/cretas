-- V20260926_01__twodfire_sync_state.sql
-- 二维火 (2dfire) POS ingest adapter SKELETON — incremental sync cursor store.
--
-- 背景: smartbi/ingestion/twodfire_adapter.py 是 二维火 开放平台 POS API 的
--   ready-to-fill 骨架 (尚无真实凭证). 未来 configured sync 路径需要记录每个
--   (factory_id, shop_id) 上次同步到哪 (last_sync_at) + 增量游标 (cursor) 以便
--   下次只拉新数据, 不重复整段历史. 此表是那个游标存储.
--
-- 现状: 表已建但 SKELETON 不写它 (sync 未实现, 只返回"未配置"死胡同).
--   先建表是为了 (a) RLS/grant 模式与项目其它 smartbi 表对齐, (b) 完成集成时
--   只需填 endpoint + 写游标, 不用再加 migration.
--
-- cursor: JSONB — 二维火 可能用 page token / last_order_id / timestamp 任一种
--   增量机制 (待 docs 确认), JSONB 容纳任意游标形状不锁死 schema.
--
-- 幂等: CREATE TABLE IF NOT EXISTS + UNIQUE(factory_id, shop_id) 配 ON CONFLICT.

CREATE TABLE IF NOT EXISTS twodfire_sync_state (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(100) NOT NULL,
    -- 二维火 门店 id (TWODFIRE_SHOP_ID) — 一个 factory 可能对多个 二维火 门店
    shop_id VARCHAR(128) NOT NULL,
    -- 上次成功同步完成的时间点 (用于增量窗口起点)
    last_sync_at TIMESTAMP,
    -- 增量游标 — 形状由 二维火 API 决定 (page token / last id / ts), 待 docs 确认
    cursor JSONB,
    -- 最近一次同步状态, 便于 admin 排查
    last_status VARCHAR(20) NOT NULL DEFAULT 'never'
        CHECK (last_status IN ('never', 'ok', 'partial', 'error')),
    last_error TEXT,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    UNIQUE (factory_id, shop_id)
);

-- RLS: 租户隔离 (app.factory_id GUC, 与所有 smartbi 表一致)
ALTER TABLE twodfire_sync_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE twodfire_sync_state FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON twodfire_sync_state;
CREATE POLICY tenant_isolation ON twodfire_sync_state
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));

-- 主查询: 按 (factory, shop) 取游标 — UNIQUE 已建索引, 此处补按 factory 列举.
CREATE INDEX IF NOT EXISTS idx_twodfire_sync_state_factory
    ON twodfire_sync_state (factory_id);

-- GRANT DML + sequence (recurring grant-gap — 新 smartbi 表必带, 否则 fail-open 静默 0 行)
GRANT SELECT, INSERT, UPDATE, DELETE ON twodfire_sync_state TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE twodfire_sync_state_id_seq TO smartbi_user;

-- Rollback:
--   DROP TABLE IF EXISTS twodfire_sync_state;
