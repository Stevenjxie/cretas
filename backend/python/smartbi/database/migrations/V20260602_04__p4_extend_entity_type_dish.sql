-- V20260602_04__p4_extend_entity_type_dish.sql
-- P4a 跨店菜名归一: 扩 entity_type CHECK 加 'dish' (3 张 entity_resolution 表)。
--
-- 决策 (spec §决策 1): canonical dish 用新 entity_type='dish' 而非复用 'product'。
-- 'product' 语义已固化为门店级 SKU 行 (dim_product.product_id); 'dish' 指向 canonical
-- (dim_canonical_dish.canonical_dish_id), 是比 dim_product 更高一层的跨店聚合。
-- 复用 'product' 会让 b_entity_id 同时指代两种东西 → transitive agent 串错。
--
-- 幂等: DROP CONSTRAINT IF EXISTS + ADD (处理 re-apply)。约束名为 PG 自动生成默认名
--   (<table>_entity_type_check, 已在 V20260427_01 / V20260501_03 实锤过)。
--
-- 注: spec 写 V20260602_02, 但已被 dim_store_review_alias 占用 (apply 于 prod);
--   V20260602_03 是 canonical_dish 表。本迁移用 V20260602_04 (依赖 _03 的 dim_canonical_dish)。

-- ── entity_resolution_history ──────────────────────────────────────
-- V20260426_01 baseline: CHECK (entity_type IN ('store','product','staff'))。加 'dish'。
ALTER TABLE entity_resolution_history
    DROP CONSTRAINT IF EXISTS entity_resolution_history_entity_type_check;
ALTER TABLE entity_resolution_history
    ADD CONSTRAINT entity_resolution_history_entity_type_check
        CHECK (entity_type IN ('store', 'product', 'staff', 'dish'));

-- ── entity_resolution_admin_queue ──────────────────────────────────
-- 当前 (V20260501_03) 9 值: store/product/staff/ingredient + shape_detection/
-- sheet_merge/period_inference/field_conflict。保留全部 + 加 'dish'。
ALTER TABLE entity_resolution_admin_queue
    DROP CONSTRAINT IF EXISTS entity_resolution_admin_queue_entity_type_check;
ALTER TABLE entity_resolution_admin_queue
    ADD CONSTRAINT entity_resolution_admin_queue_entity_type_check
        CHECK (entity_type IN (
            'store', 'product', 'staff', 'ingredient', 'dish',
            'shape_detection', 'sheet_merge', 'period_inference', 'field_conflict'
        ));

-- ── entity_resolution_labels ───────────────────────────────────────
-- V20260426_01 baseline: CHECK (entity_type IN ('store','product','staff'))。加 'dish'。
ALTER TABLE entity_resolution_labels
    DROP CONSTRAINT IF EXISTS entity_resolution_labels_entity_type_check;
ALTER TABLE entity_resolution_labels
    ADD CONSTRAINT entity_resolution_labels_entity_type_check
        CHECK (entity_type IN ('store', 'product', 'staff', 'dish'));

-- Rollback (仅当无 entity_type='dish' 行时安全):
--   ALTER TABLE entity_resolution_history
--     DROP CONSTRAINT entity_resolution_history_entity_type_check;
--   ALTER TABLE entity_resolution_history
--     ADD CONSTRAINT entity_resolution_history_entity_type_check
--       CHECK (entity_type IN ('store','product','staff'));
--   ALTER TABLE entity_resolution_admin_queue
--     DROP CONSTRAINT entity_resolution_admin_queue_entity_type_check;
--   ALTER TABLE entity_resolution_admin_queue
--     ADD CONSTRAINT entity_resolution_admin_queue_entity_type_check
--       CHECK (entity_type IN ('store','product','staff','ingredient',
--         'shape_detection','sheet_merge','period_inference','field_conflict'));
--   ALTER TABLE entity_resolution_labels
--     DROP CONSTRAINT entity_resolution_labels_entity_type_check;
--   ALTER TABLE entity_resolution_labels
--     ADD CONSTRAINT entity_resolution_labels_entity_type_check
--       CHECK (entity_type IN ('store','product','staff'));
