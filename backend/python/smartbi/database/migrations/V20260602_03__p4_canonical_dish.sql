-- V20260602_03__p4_canonical_dish.sql
-- P4a 跨店菜名归一: dim_canonical_dish — 1 canonical dish ← N dim_product (跨门店)。
--
-- 客户最大最难问题: 8 店把同一道菜叫不同名 ("招牌青花椒鱼(单人份)" vs "青花椒味鱼"),
-- 系统当成 N 道不同菜, 销量/营收/评价按碎片菜名分散失真。本表建跨店 canonical 菜品字典。
--
-- ⛔ CRITICAL — 绝不自动合并 (per MEMORY #364 / spec §R1 P0):
--   本表行 + dim_product.canonical_dish_id 链接 **只能** 经人工确认 (CLI confirm_dish_canonical)
--   写入。离线 canonicalizer / agent 高置信结果只 PROPOSE 进 entity_resolution_admin_queue,
--   绝不直接写本表 membership。错合并 (红烧牛肉 ≠ 红烧牛腩) 静默污染所有按 canonical 的分析,
--   难以发现。
--
-- ⛔ GRANT DML (per #390 / feedback_smartbi_table_grant_gap): 迁移 runner 以 postgres 超级
--   用户跑 → 表归 postgres → 不 GRANT 给 smartbi_user 则 INSERT/UPDATE = permission denied,
--   被 fail-open 静默吞 → 表 0 行无人发现 (entity_resolution_history 已踩过 2 次)。
--
-- 注: spec 写 V20260602_01/_02, 但二者均已 apply 于 prod
--   (V01=grant_entity_resolution_writes, V02=dim_store_review_alias)。tracker keys on filename,
--   重名会静默跳过, 故本迁移用 V20260602_03。
--
-- 关联: docs/superpowers/specs/2026-06-02-qhj-deep-analysis-p4-dish-canonicalization.md §4.1 / §5 (P4a)

-- =============================================================================
-- dim_canonical_dish — 跨店 canonical 菜品字典
-- =============================================================================
CREATE TABLE IF NOT EXISTS dim_canonical_dish (
    canonical_dish_id  BIGSERIAL PRIMARY KEY,
    factory_id         VARCHAR(50) NOT NULL,
    canonical_name     VARCHAR(500) NOT NULL,   -- 客户确认的规范展示名
    normalized_key     VARCHAR(500) NOT NULL,   -- 规则层归一 key (去 5 类后缀 + 繁简 + 标点), 去重用
    category           VARCHAR(100),            -- 继承自 dish_classifier 主品类 (可空)
    member_count       INT NOT NULL DEFAULT 0,  -- 当前挂了几个 dim_product (审计/UI 肉眼核用)
    status             VARCHAR(20) NOT NULL DEFAULT 'active'
                       CHECK (status IN ('active', 'merged_away', 'retired')),
    merged_into_id     BIGINT REFERENCES dim_canonical_dish(canonical_dish_id),  -- 误建后软回退指向
    created_by         VARCHAR(50),             -- 'admin' / 'rule_seed' (P4a 全人工, 无 'agent')
    created_at         TIMESTAMP DEFAULT NOW(),
    updated_at         TIMESTAMP DEFAULT NOW(),
    -- 同 (factory, 规则归一 key) 唯一: 防同一道菜被建多个 canonical 行。
    CONSTRAINT uq_canonical_dish_factory_normkey UNIQUE (factory_id, normalized_key)
);

ALTER TABLE dim_canonical_dish ENABLE ROW LEVEL SECURITY;
ALTER TABLE dim_canonical_dish FORCE ROW LEVEL SECURITY;
-- per dim_store_review_alias (V20260602_02) 模式: USING + WITH CHECK 双绑, 含 __internal__
-- sentinel 旁路 (物化/内部离线任务以 SET app.factory_id='__internal__' 跨租户写, per #590)。
-- DROP-then-CREATE 保证 re-apply 幂等 (CREATE POLICY 不支持 IF NOT EXISTS)。
DROP POLICY IF EXISTS tenant_isolation ON dim_canonical_dish;
CREATE POLICY tenant_isolation ON dim_canonical_dish
    USING (current_setting('app.factory_id', true) IS NULL
           OR current_setting('app.factory_id', true) = ''
           OR current_setting('app.factory_id', true) = '__internal__'
           OR factory_id = current_setting('app.factory_id', true))
    WITH CHECK (current_setting('app.factory_id', true) IS NULL
           OR current_setting('app.factory_id', true) = ''
           OR current_setting('app.factory_id', true) = '__internal__'
           OR factory_id = current_setting('app.factory_id', true));

DROP TRIGGER IF EXISTS trg_canonical_dish_touch ON dim_canonical_dish;
CREATE TRIGGER trg_canonical_dish_touch BEFORE UPDATE ON dim_canonical_dish
    FOR EACH ROW EXECUTE FUNCTION silver_touch_updated_at();

CREATE INDEX IF NOT EXISTS idx_canonical_dish_factory_cat
    ON dim_canonical_dish (factory_id, category);
-- 活跃 canonical 视角 (UI 列表 / 回填查询):
CREATE INDEX IF NOT EXISTS idx_canonical_dish_factory_active
    ON dim_canonical_dish (factory_id, status)
    WHERE status = 'active';

-- =============================================================================
-- dim_product 加 canonical_dish_id 外键列 (NULL = 尚未归一)
-- 不改 dim_product 唯一键 (避免动现有 silver writer / agg 外键, blast radius 大)。
-- 加 NULL 列 + ON DELETE SET NULL → 现有 INSERT/UPSERT 不写该列 (默认 NULL), 零行为变化。
-- =============================================================================
ALTER TABLE dim_product
    ADD COLUMN IF NOT EXISTS canonical_dish_id BIGINT
        REFERENCES dim_canonical_dish(canonical_dish_id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_dim_product_canonical
    ON dim_product (factory_id, canonical_dish_id)
    WHERE canonical_dish_id IS NOT NULL;

-- ⛔ GRANT DML (HARD, per #390): 不写则整个写路径死 (permission denied 被 fail-open 吞)。
-- GRANT 幂等, 可安全重复 apply。runner 以 postgres 连 (表 owner) 故可授权。
-- BIGSERIAL 自动建序列, 命名规则 <table>_<column>_seq = dim_canonical_dish_canonical_dish_id_seq。
GRANT SELECT, INSERT, UPDATE, DELETE ON dim_canonical_dish TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE dim_canonical_dish_canonical_dish_id_seq TO smartbi_user;
-- dim_product 已有 GRANT (silver_dimensions), 加列继承表级 GRANT, 无需重 GRANT。
-- 部署后确认列权限: SELECT has_column_privilege('smartbi_user','dim_product','canonical_dish_id','UPDATE');

COMMENT ON TABLE dim_canonical_dish IS
  '跨店 canonical 菜品字典 (P4a 菜名归一)。1 canonical dish ← N dim_product。'
  '绝不自动合并 — membership 只经人工确认 CLI 写入 (per MEMORY #364)。'
  'merged_into_id 支持误建软回退; member_count 供 UI 肉眼核成员。'
  'Added 2026-06-02 per docs/superpowers/specs/2026-06-02-qhj-deep-analysis-p4-dish-canonicalization.md';

-- Rollback:
--   ALTER TABLE dim_product DROP COLUMN IF EXISTS canonical_dish_id;
--   DROP TABLE IF EXISTS dim_canonical_dish;
