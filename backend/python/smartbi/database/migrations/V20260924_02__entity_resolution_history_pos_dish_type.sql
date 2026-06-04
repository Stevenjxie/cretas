-- V20260924_02__entity_resolution_history_pos_dish_type.sql
-- 餐饮 #61 Phase 1 — entity_resolution_history.entity_type CHECK 加 'pos_dish'。
--
-- pos_dish 审计: admin 确认 POS 菜名 → product_type 绑定时，除了写 cretas dim_product_alias
--   (实际解析数据)，再写一行 smartbi entity_resolution_history 作审计 (PR #389 graduation 模式)。
--
-- b_entity_id 是 BIGINT NOT NULL，但 product_types.id 是 VARCHAR(100) UUID — 不匹配。
--   故 pos_dish 审计行用确定性正 BIGINT 代理 (md5 hash → bigint，resolver 端算)，
--   真实 string product_type_id 存 b_name + reasoning。pos_dish 用独立 id 空间，
--   不参与 TransitiveAgent 跨类型整数链 (正确 — 它们不是 dim_* 整数实体)。
--
-- 当前 (V20260602_04) entity_resolution_history CHECK: ('store','product','staff','dish')。
--   保留全部 + 加 'pos_dish'。仅 history 表 (queue/labels 不需要 — pos_dish 审计只走 history，
--   未解析裁决走独立 restaurant_pos_unresolved_queue 而非 entity_resolution_admin_queue)。
--
-- 幂等: DROP CONSTRAINT IF EXISTS + ADD (约束名为 PG 自动生成默认名，已在历史迁移实锤过)。

ALTER TABLE entity_resolution_history
    DROP CONSTRAINT IF EXISTS entity_resolution_history_entity_type_check;
ALTER TABLE entity_resolution_history
    ADD CONSTRAINT entity_resolution_history_entity_type_check
        CHECK (entity_type IN ('store', 'product', 'staff', 'dish', 'pos_dish'));

-- Rollback (仅当无 entity_type='pos_dish' 行时安全):
--   ALTER TABLE entity_resolution_history
--     DROP CONSTRAINT entity_resolution_history_entity_type_check;
--   ALTER TABLE entity_resolution_history
--     ADD CONSTRAINT entity_resolution_history_entity_type_check
--       CHECK (entity_type IN ('store','product','staff','dish'));
