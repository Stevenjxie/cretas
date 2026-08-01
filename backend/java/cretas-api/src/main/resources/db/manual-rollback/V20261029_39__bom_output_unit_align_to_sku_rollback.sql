-- 手工回滚: V20261029_39__bom_output_unit_align_to_sku.sql
--
-- Flyway 迁移是前向的, 这份文件按 **迁移前抓取的 pre-image** 逐 id 还原 output_unit。
-- pre-image 抓取时间: 2026-08-01 (prod cretas_prod_db), 共 17 行, 与迁移实际影响面一致。
--
-- 用法 (确认真的要回滚再执行):
--   psql -d cretas_prod_db -f V20261029_39__bom_output_unit_align_to_sku_rollback.sql
--
-- 每条都带 `AND output_unit = '<迁移后的值>'` 守卫: 若该行在回滚前又被人改成别的值,
-- 这条 UPDATE 命中 0 行而不是把新值覆盖掉 —— 宁可回滚不全, 也不静默丢别人的修改。
-- 执行后核对 ROLLBACK CHECK 段的计数。

BEGIN;
UPDATE bom_recipes SET output_unit = 'box' WHERE id = 'f4cec71b-ca70-4429-97fa-8c5c4177b1ac' AND output_unit = '盒';  -- F006 BOM-20260716-002 v5
UPDATE bom_recipes SET output_unit = 'box' WHERE id = '69c2813d-8d7e-46a5-b319-4dc8e46ede92' AND output_unit = '盒';  -- F006 BOM-20260723-001 v1
UPDATE bom_recipes SET output_unit = 'box' WHERE id = '2fb70b07-1816-4f35-9d91-9d91972516b5' AND output_unit = '盒';  -- F006 BOM-20260723-002 v2
UPDATE bom_recipes SET output_unit = 'box' WHERE id = '218187cc-5a7f-405a-8ab1-355265de2542' AND output_unit = '盒';  -- F006 BOM-20260724-001 v3
UPDATE bom_recipes SET output_unit = 'box' WHERE id = 'cab81411-3220-47ae-827a-877405bc23f7' AND output_unit = '盒';  -- F006 BOM-20260728-001 v1
UPDATE bom_recipes SET output_unit = 'box' WHERE id = 'f6dab9f9-ba46-4ba0-a153-b66af109bf6d' AND output_unit = '盒';  -- F006 BOM-20260728-002 v2
UPDATE bom_recipes SET output_unit = 'box' WHERE id = '09a164c6-0e33-4187-822a-d8f8788ca70a' AND output_unit = '盒';  -- F006 BOM-20260728-003 v3
UPDATE bom_recipes SET output_unit = 'box' WHERE id = '9a4480b2-5074-4a40-8ca1-62e3ccc11923' AND output_unit = '盒';  -- F006 BOM-20260728-004 v4
UPDATE bom_recipes SET output_unit = 'box' WHERE id = '40d3a154-1209-4a8a-b876-ed24ba5ac801' AND output_unit = '盒';  -- F006 BOM-20260729-001 v1
UPDATE bom_recipes SET output_unit = 'box' WHERE id = 'f5985654-ac47-4772-a558-407252877cfc' AND output_unit = '盒';  -- F006 BOM-20260730-001 v2
UPDATE bom_recipes SET output_unit = 'bag' WHERE id = 'f683b24e-f66f-4734-8ee1-568c1aa53a80' AND output_unit = '袋';  -- LIUSHANMEN BOM-20260727-001 v1
UPDATE bom_recipes SET output_unit = 'box' WHERE id = '7a9c7fc2-0dc7-4093-bd30-290bd4e0695e' AND output_unit = '盒';  -- LIUSHANMEN BOM-20260727-002 v1
UPDATE bom_recipes SET output_unit = 'bag' WHERE id = 'c94afea4-b2a7-46e0-a731-e2d77e9de682' AND output_unit = '袋';  -- LIUSHANMEN BOM-20260728-001 v2
UPDATE bom_recipes SET output_unit = 'bag' WHERE id = 'eb775685-08ae-469d-a7d8-b3eb9e00c4cb' AND output_unit = '袋';  -- LIUSHANMEN BOM-20260728-002 v3
UPDATE bom_recipes SET output_unit = 'bag' WHERE id = '3dbd147a-59c3-4e81-905a-21ce55aec25d' AND output_unit = '袋';  -- LIUSHANMEN BOM-20260728-003 v4
UPDATE bom_recipes SET output_unit = 'bag' WHERE id = 'b07a89b7-696d-4d98-9084-ac285ff37dce' AND output_unit = '袋';  -- LIUSHANMEN BOM-20260728-004 v5
UPDATE bom_recipes SET output_unit = 'bag' WHERE id = '940e7aa7-db04-443c-a0b9-b4c5ced9722a' AND output_unit = '袋';  -- LIUSHANMEN BOM-20260729-001 v6

-- ROLLBACK CHECK: 还原后应当重新出现 17 行英文码 output_unit
SELECT count(*) AS restored_ascii_rows
FROM bom_recipes
WHERE deleted_at IS NULL
  AND output_unit ~ '^[a-zA-Z]+$'
  AND lower(output_unit) IN ('box', 'bag');

COMMIT;
