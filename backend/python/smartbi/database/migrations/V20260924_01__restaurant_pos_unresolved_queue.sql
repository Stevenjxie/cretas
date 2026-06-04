-- V20260924_01__restaurant_pos_unresolved_queue.sql
-- 餐饮 #61 Phase 1 — POS 菜品名称解析未解析队列 (admin 裁决用)。
--
-- 背景: dim_product_alias 在 cretas_db (财务 ETL Stage 3 从 cretas_pool 读)，
--   但未解析队列 + 审计是分析/admin 关注点，放 smartbi_db (runner 管理 + entity_resolution
--   基础设施同源)。详见 docs/superpowers/specs/2026-06-04-restaurant-pos-name-resolution-design.md。
--
-- key = normalized_name (与财务 ETL Stage 3 alias 查找一致: ETL 用 dim_product.normalized_name
--   匹配 product_types.name / dim_product_alias.pos_name)。display_name 仅供 admin UI 展示。
--
-- 幂等: CREATE TABLE IF NOT EXISTS + UNIQUE(factory_id, pos_name) 配 ON CONFLICT。

CREATE TABLE IF NOT EXISTS restaurant_pos_unresolved_queue (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(100) NOT NULL,
    -- normalized_name (ETL Stage 3 的解析 key) — UNIQUE 自然键
    pos_name TEXT NOT NULL,
    -- POS 原始展示名 (dim_product.name) 供 admin UI 可读性
    display_name TEXT,
    occurrence_count INTEGER NOT NULL DEFAULT 0,
    -- 该 POS 菜名累计营收 = 未解析 = 无 COGS 行 = 利润被高估的风险敞口
    revenue_at_risk NUMERIC(14,2) NOT NULL DEFAULT 0,
    -- 系统给出的最佳候选 (L3/L4)，admin 一键确认用
    best_candidate_id VARCHAR(100),
    best_candidate_name TEXT,
    best_confidence NUMERIC(3,2),
    status VARCHAR(20) NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'confirmed', 'rejected', 'skipped')),
    admin_user VARCHAR(100),
    admin_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    UNIQUE (factory_id, pos_name)
);

-- RLS: 租户隔离 (app.factory_id GUC，与所有 smartbi 表一致)
ALTER TABLE restaurant_pos_unresolved_queue ENABLE ROW LEVEL SECURITY;
ALTER TABLE restaurant_pos_unresolved_queue FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON restaurant_pos_unresolved_queue;
CREATE POLICY tenant_isolation ON restaurant_pos_unresolved_queue
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));

-- 主查询: admin UI 列 pending，按风险敞口降序
CREATE INDEX IF NOT EXISTS idx_pos_unresolved_pending
    ON restaurant_pos_unresolved_queue (factory_id, status, revenue_at_risk DESC)
    WHERE status = 'pending';

-- GRANT DML + sequence (recurring grant-gap — 新 smartbi 表必带，否则 fail-open 静默 0 行)
GRANT SELECT, INSERT, UPDATE, DELETE ON restaurant_pos_unresolved_queue TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE restaurant_pos_unresolved_queue_id_seq TO smartbi_user;

-- Rollback:
--   DROP TABLE IF EXISTS restaurant_pos_unresolved_queue;
