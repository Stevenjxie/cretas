-- V20260920_01__restaurant_recipe_unit_price_cache.sql
-- #57 成本卡/出菜反推 — agg_restaurant_product_cost staleness 列 + grant 补齐。
-- (2026-06-04)
--
-- 背景: agg_restaurant_product_cost 已存在 (2026_04_24_gold_restaurant_ops.sql 建,
--   2026_04_24_recipe_product_source_pk.sql 改 PK 为 (factory_id, product_source_pk))。
--   它缓存每道菜的 food_cost。#57 在配方保存后需重算该表 (Java RecipeSavedEvent 触发),
--   本迁移加 last_recipe_updated_at 列让重算器/读路径判断缓存是否过期 (配方比缓存新 → stale)。
--
-- 本迁移 ⚠️ 仅 ALTER 既有表 (不新建表, 不改 RLS — RLS policy tenant_isolation 已存在)。
--
-- ⛔ GRANT DML (HARD, per feedback_smartbi_table_grant_gap): 历史迁移建表时 owner=postgres
--   可能漏 GRANT 给 smartbi_user → 写 (重算 upsert) 静默 permission denied。这里幂等补齐。
--   表无 BIGSERIAL/sequence (PK 是 (factory_id, product_source_pk) 复合, 无自增), 故无
--   sequence grant。
-- 版本 V20260920_01: origin/main smartbi migrations max V20260919_02, 取更大且已 collision-check。

-- 1) staleness 列: 该菜品配方最近一次更新时间 (Java 端写; 读路径比 computed_at 判 stale)。
ALTER TABLE agg_restaurant_product_cost
    ADD COLUMN IF NOT EXISTS last_recipe_updated_at TIMESTAMP;

-- 2) staleness 索引: 按 (factory_id, last_recipe_updated_at) 快速找出需重算的菜品。
CREATE INDEX IF NOT EXISTS idx_agg_product_cost_staleness
    ON agg_restaurant_product_cost (factory_id, last_recipe_updated_at);

-- 3) GRANT DML (幂等; 既有表可能只默认 SELECT)。
GRANT SELECT, INSERT, UPDATE, DELETE ON agg_restaurant_product_cost TO smartbi_user;

-- Rollback:
--   DROP INDEX IF EXISTS idx_agg_product_cost_staleness;
--   ALTER TABLE agg_restaurant_product_cost DROP COLUMN IF EXISTS last_recipe_updated_at;
