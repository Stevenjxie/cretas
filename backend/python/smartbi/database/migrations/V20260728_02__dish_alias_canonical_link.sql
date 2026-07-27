-- V20260728_02__dish_alias_canonical_link.sql
-- 餐饮 AI 飞轮回接 · 卡3 菜品主数据层 (spec §2.4)
-- 关联: docs/superpowers/specs/2026-07-28-restaurant-ai-flywheel-reconnect-plan.md §2.4
--
-- =============================================================================
-- ⚠️ 方案偏离说明 (organizer 决策, 2026-07-28) —— 复用而非新建
-- =============================================================================
-- spec §2.4 / 卡3 原文要求新建 `dish_master` + `dish_alias` 两张表。落地时核查发现
-- 本库**已有**同职能的两层结构, 且都在生产使用中:
--
--   1. `dim_canonical_dish` (V20260602_03) —— 跨店 canonical 菜品字典,
--      `canonical_dish_id BIGSERIAL` 就是 spec 要的"标准菜品 ID"; 已带 RLS +
--      GRANT + 人工确认纪律 (MEMORY #364: 绝不自动合并)。
--      => 这就是 `dish_master`, 无需新建。
--   2. `restaurant_dish_alias` (V20260711_01) —— 别名表, 已有
--      (factory_id, original_name) → canonical_name 映射 + confidence +
--      review_source + reviewed_by/reviewed_at, 已 GRANT (V20260711_02)。
--      => 这就是 `dish_alias`, 但**只映射到字符串名, 没有映射到稳定 ID**。
--
-- 活跃消费方: smartbi/canonical/{dish_canonicalizer,dish_confirm_service}.py,
-- entity_resolution/agents/*, shared/alias_normalizer.py, services/restaurant/*,
-- scripts/confirm_dish_canonical.py, alias_review_queue 人审队列。
--
-- 若照字面新建 dish_master/dish_alias, 库里会出现**第四套**菜品字典, 正是 spec §2.2
-- 要收敛的"五份互不知晓的概念定义"。故本迁移改为**补齐缺失的那一环**:
-- 把别名表接到稳定 ID 上, 使 spec §2.4 的核心诉求
-- 「resolver/晋升计划/定制表引用标准菜品 ID (晋升计划存 ID 而非原文名, 跨店稳定)」成立。
--
-- =============================================================================
-- 本迁移只做**纯增量**改动 (ADD COLUMN / CREATE INDEX), 零行为变化, 可重跑幂等:
--   * 不改表结构语义, 不删列, 不改既有唯一索引
--   * 既有行全部落在与今天完全相同的语义上 (见下方各列 DEFAULT 说明)
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. canonical_dish_id —— 别名 → 标准菜品 ID (本迁移的核心)
--    NULL = 尚未归一到 canonical 字典 (等价于今天的全部存量行), resolver 此时
--    按 canonical_name 原文查询, 行为与现状一致 (卡3 要求的"行为兼容")。
--    ON DELETE SET NULL: canonical 行被软退役/误建回滚时别名不被连坐删除。
-- -----------------------------------------------------------------------------
ALTER TABLE restaurant_dish_alias
    ADD COLUMN IF NOT EXISTS canonical_dish_id BIGINT
        REFERENCES dim_canonical_dish(canonical_dish_id) ON DELETE SET NULL;

-- -----------------------------------------------------------------------------
-- 2. store_id —— spec §2.4 的"store 维度"
--    NULL = 该别名对整个租户生效 (存量行语义, 保持不变)。
--    非 NULL = 门店级别名, 供 resolver 做"门店优先, 回落租户级"两段匹配。
--
--    ⛔ 注意 (Wave 1 边界, 有意为之):
--    既有唯一索引 idx_dish_alias_unique (factory_id, original_name) **保持不动**,
--    因为 shared/alias_normalizer.py:290 的写路径用
--    `ON CONFLICT (factory_id, original_name) DO UPDATE` 做 UPSERT ——
--    把它改成 partial index 会让该 ON CONFLICT 推断失败, 直接打断客户确认写入。
--    因此 Wave 1 内仍是"每 (租户, 原文名) 一条别名"; 同一原文名在不同门店映射到
--    **不同**标准菜的场景属 Wave 2 (需同步改 alias_normalizer 的 ON CONFLICT 子句)。
--    本列先落地, 使门店级数据可表达、resolver 可读, 不改写入侧唯一性约束。
-- -----------------------------------------------------------------------------
ALTER TABLE restaurant_dish_alias
    ADD COLUMN IF NOT EXISTS store_id VARCHAR(64);

-- -----------------------------------------------------------------------------
-- 3. status —— 人审三态 (卡3 要求"语义辅助初匹配产候选, 落 pending 状态待人审确认")
--    DEFAULT 'confirmed': 存量行都是客户审核后写入的 (V20260711_01 表注释:
--    "客户审核后持久化"), 故默认 confirmed 保持现状语义不变。
--    机器初匹配产出的新候选一律显式写 'pending', 人审通过才转 'confirmed'。
--    ⛔ resolver 只认 'confirmed' —— pending 候选绝不影响线上答案 (fail-closed)。
-- -----------------------------------------------------------------------------
ALTER TABLE restaurant_dish_alias
    ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'confirmed';

ALTER TABLE restaurant_dish_alias
    DROP CONSTRAINT IF EXISTS chk_dish_alias_status;
ALTER TABLE restaurant_dish_alias
    ADD CONSTRAINT chk_dish_alias_status
    CHECK (status IN ('pending', 'confirmed', 'rejected'));

-- -----------------------------------------------------------------------------
-- 4. 索引
-- -----------------------------------------------------------------------------
-- resolver 反查: 一个标准菜有哪些别名 (跨店聚合时用)
CREATE INDEX IF NOT EXISTS idx_dish_alias_canonical_id
    ON restaurant_dish_alias (factory_id, canonical_dish_id)
    WHERE canonical_dish_id IS NOT NULL;

-- 人审队列: 待确认候选 (数量远小于全表, partial index 省空间)
CREATE INDEX IF NOT EXISTS idx_dish_alias_pending
    ON restaurant_dish_alias (factory_id, created_at DESC)
    WHERE status = 'pending';

-- 门店级别名查找 (resolver 两段匹配的第一段)
CREATE INDEX IF NOT EXISTS idx_dish_alias_store
    ON restaurant_dish_alias (factory_id, store_id, original_name)
    WHERE store_id IS NOT NULL;

-- -----------------------------------------------------------------------------
-- 5. ⛔ GRANT (HARD RULE, per V20260428_03 惯例 / #390 feedback_smartbi_table_grant_gap)
--    migration runner 以 postgres 超级用户跑 → 对象 owner=postgres。漏 GRANT 会
--    "permission denied for table" 被 fail-open 静默吞 → 表永远空无人发现
--    (V20260708_02 / V20260711_02 都是补 GRANT 的事后迁移, 已踩过两次)。
--
--    ALTER TABLE ADD COLUMN 继承表级 GRANT, 故严格说本迁移无新增授权需求;
--    这里仍显式重授一次 —— GRANT 幂等可安全重复, 且能顺带修复任何 schema drift
--    (prod/test 之间 GRANT 不一致的历史遗留)。
--    dim_canonical_dish 的 SELECT 是别名→标准 ID 解析的前置权限, 一并确认。
-- -----------------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE, DELETE ON restaurant_dish_alias TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE restaurant_dish_alias_id_seq TO smartbi_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON dim_canonical_dish TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE dim_canonical_dish_canonical_dish_id_seq TO smartbi_user;

-- -----------------------------------------------------------------------------
-- 6. 注释
-- -----------------------------------------------------------------------------
COMMENT ON COLUMN restaurant_dish_alias.canonical_dish_id IS
    '标准菜品 ID → dim_canonical_dish。NULL=尚未归一, resolver 回落 canonical_name 原文匹配。'
    '晋升计划/定制表应引用本 ID 而非原文名 (跨店稳定), per spec 2026-07-28 §2.4。';
COMMENT ON COLUMN restaurant_dish_alias.store_id IS
    '门店级别名作用域。NULL=租户级 (存量行语义)。Wave 1 内写入侧唯一性仍是 (factory_id, original_name)。';
COMMENT ON COLUMN restaurant_dish_alias.status IS
    'pending=机器初匹配候选待人审 / confirmed=人审通过(存量行默认) / rejected=人审否决。'
    'resolver 只认 confirmed — pending 绝不影响线上答案。';

-- =============================================================================
-- Rollback:
--   DROP INDEX IF EXISTS idx_dish_alias_store;
--   DROP INDEX IF EXISTS idx_dish_alias_pending;
--   DROP INDEX IF EXISTS idx_dish_alias_canonical_id;
--   ALTER TABLE restaurant_dish_alias DROP CONSTRAINT IF EXISTS chk_dish_alias_status;
--   ALTER TABLE restaurant_dish_alias DROP COLUMN IF EXISTS status;
--   ALTER TABLE restaurant_dish_alias DROP COLUMN IF EXISTS store_id;
--   ALTER TABLE restaurant_dish_alias DROP COLUMN IF EXISTS canonical_dish_id;
-- =============================================================================
