-- 手工回滚: V20261029_41__bom_output_unit_fen_to_he.sql
--
-- 按迁移前抓取的 pre-image 逐 id 把 output_unit 还原成「份」。
-- pre-image 抓取时间: 2026-08-01 (prod cretas_prod_db), 5 行, 与迁移实际影响面一致。
--
-- 每条带 `AND output_unit = '盒'` 守卫: 该行若在回滚前又被改成别的值,
-- 这条 UPDATE 命中 0 行而不是覆盖掉新值 —— 宁可回滚不全, 也不静默丢别人的修改。

BEGIN;
UPDATE bom_recipes SET output_unit = '份' WHERE id = '563ac56a-e0bc-460b-9ada-e3bf612956ee' AND output_unit = '盒';  -- LIUSHANMEN BOM-20260616-001 v1
UPDATE bom_recipes SET output_unit = '份' WHERE id = '8544d6fd-bd69-4697-917d-1e824ac696e2' AND output_unit = '盒';  -- LIUSHANMEN BOM-20260625-001 v1
UPDATE bom_recipes SET output_unit = '份' WHERE id = 'd5dd3bd6-da13-4d7f-83f4-a1cdb1bcda3c' AND output_unit = '盒';  -- LIUSHANMEN BOM-20260702-001 v1
UPDATE bom_recipes SET output_unit = '份' WHERE id = 'b9da79a1-59b3-4ce4-be5d-b9cdadfa3baa' AND output_unit = '盒';  -- LIUSHANMEN BOM-20260702-002 v1
UPDATE bom_recipes SET output_unit = '份' WHERE id = 'a9971278-08c5-44a8-8d2d-f693d8032040' AND output_unit = '盒';  -- LIUSHANMEN BOM-20260702-003 v1

-- ROLLBACK CHECK: 还原后「份」应重新变成 6 行 (5 条回滚 + 1 条悬空的从未被动)
SELECT count(*) AS fen_rows FROM bom_recipes WHERE deleted_at IS NULL AND output_unit = '份';

COMMIT;
