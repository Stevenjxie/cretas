-- V20260820_06__food_safety_composite_indexes.sql
--
-- Sprint 8 P3 食品安全 — 复合 index 优化 query 性能 (2026-05-20)
--
-- Purpose: 进一步优化 hot path queries that V20260820_03~_05 base indexes 未覆盖:
--   1. HACCP monitoring 按 batch 时间倒序查 (召回追溯, large batch history)
--   2. Additive 按 name + category lookup (compliance check 时按 additive name 查)
--   3. Recall action 按 (type, status) 全 factory 聚合 (UI dashboard "今日待执行")

-- HACCP monitoring lookup by batch + time range (召回追溯 hot path).
-- 已有 (factory_id, batch_number) base index, 新加全局 batch + time 复合 index
-- 用于跨 factory 的 batch 时间排序场景 (e.g. supplier batch 跨工厂追溯).
CREATE INDEX IF NOT EXISTS idx_haccp_monitoring_batch_time
    ON haccp_monitoring_records(batch_number, monitoring_time DESC)
    WHERE deleted_at IS NULL;

-- Additive lookup by name (e.g. user 输入"亚硝酸钠"模糊查 INS code).
CREATE INDEX IF NOT EXISTS idx_additive_name_lookup
    ON additive_limits(additive_name, food_category, active)
    WHERE deleted_at IS NULL;

-- Recall action by (type, status) — UI dashboard "今日待执行行动" 全 factory 聚合.
CREATE INDEX IF NOT EXISTS idx_recall_actions_type_status
    ON recall_actions(action_type, status)
    WHERE deleted_at IS NULL;
