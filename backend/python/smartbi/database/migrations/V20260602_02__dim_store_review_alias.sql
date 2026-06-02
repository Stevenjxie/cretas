-- V20260602_02__dim_store_review_alias.sql
-- 评价门店名 → gold dim_store.store_id 别名映射桥 (P3 门店评分×营收)。
-- 评价数据(大众点评导出)门店名 (smart_bi_dynamic_data.row_data->>'评价门店') 与 POS
-- dim_store.name 0 精确匹配, 靠括号地标/品牌模糊匹配 + 人工确认。
-- 高置信自动入库, 低置信进确认队列, 复用实体解析毕业模式。
--
-- ⛔ 迁移 runner 以 postgres 超级用户跑 → 表归 postgres → 必须 GRANT 给 smartbi_user
-- (per feedback_smartbi_table_grant_gap: 漏 GRANT 会导致写路径 permission denied 被
--  fail-open 静默吞, 表 0 行无人发现; entity_resolution_history 已踩过 2 次)。
--
-- 注: spec 写的文件名 V20260602_01 已被 V20260602_01__grant_entity_resolution_writes.sql
-- 占用 (已 apply 于 prod), 故本迁移用 V20260602_02 (tracker keys on filename,
-- 重名会静默跳过)。

CREATE TABLE IF NOT EXISTS dim_store_review_alias (
    id                 BIGSERIAL PRIMARY KEY,
    factory_id         VARCHAR(50)  NOT NULL,
    review_store_name  TEXT         NOT NULL,            -- 评价侧原始门店名 (未归一, 保真)
    store_id           BIGINT,                           -- 映射到的 gold dim_store.store_id; NULL = 确认无对应(外卖卫星/鲜行者)
    confidence         NUMERIC(3,2) NOT NULL DEFAULT 0.0,-- 0.00-1.00
    match_method       VARCHAR(32)  NOT NULL,            -- 'landmark' | 'brand_landmark' | 'exact_norm' | 'admin' | 'no_match'
    decided_by         VARCHAR(32)  NOT NULL,            -- 'auto' | 'admin' | 'unmapped'
    landmark           TEXT,                             -- 抽到的地标 token (审计/调试用, 如 '五角场')
    created_at         TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP    NOT NULL DEFAULT NOW(),
    -- 防表爆炸 + 同名重复确认: 每 (factory, 评价名) 唯一绑定一个映射
    UNIQUE (factory_id, review_store_name)
);

ALTER TABLE dim_store_review_alias ENABLE ROW LEVEL SECURITY;
ALTER TABLE dim_store_review_alias FORCE ROW LEVEL SECURITY;
-- per V20260502_05 模式: USING + WITH CHECK 双绑, 含 __internal__ sentinel 旁路
-- (物化/内部任务以 SET app.factory_id='__internal__' 跨租户写, per #590 教训)。
-- DROP-then-CREATE 保证 re-apply 幂等 (CREATE POLICY 不支持 IF NOT EXISTS)。
DROP POLICY IF EXISTS tenant_isolation ON dim_store_review_alias;
CREATE POLICY tenant_isolation ON dim_store_review_alias
    USING (current_setting('app.factory_id', true) IS NULL
           OR current_setting('app.factory_id', true) = ''
           OR current_setting('app.factory_id', true) = '__internal__'
           OR factory_id = current_setting('app.factory_id', true))
    WITH CHECK (current_setting('app.factory_id', true) IS NULL
           OR current_setting('app.factory_id', true) = ''
           OR current_setting('app.factory_id', true) = '__internal__'
           OR factory_id = current_setting('app.factory_id', true));

CREATE INDEX IF NOT EXISTS idx_store_review_alias_lookup
    ON dim_store_review_alias (factory_id, store_id);
-- 确认队列视角: 列出待人工确认 (低置信 auto)
CREATE INDEX IF NOT EXISTS idx_store_review_alias_pending
    ON dim_store_review_alias (factory_id, created_at DESC)
    WHERE decided_by = 'auto' AND confidence < 0.90;

-- ⛔ GRANT (HARD, per feedback_smartbi_table_grant_gap): 不写则整个写路径死。
-- GRANT 幂等, 可安全重复 apply。runner 以 postgres 连 (表 owner) 故可授权。
GRANT SELECT, INSERT, UPDATE, DELETE ON dim_store_review_alias TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE dim_store_review_alias_id_seq TO smartbi_user;

COMMENT ON TABLE dim_store_review_alias IS
  '评价门店名 → gold dim_store.store_id 别名桥 (P3 门店评分×营收)。'
  'store_id NULL + decided_by=unmapped = 确认无 POS 对应 (外卖卫星店/鲜行者品牌)。'
  'Added 2026-06-02 per docs/superpowers/specs/2026-06-02-qhj-deep-analysis-p3-store-review-revenue.md';
