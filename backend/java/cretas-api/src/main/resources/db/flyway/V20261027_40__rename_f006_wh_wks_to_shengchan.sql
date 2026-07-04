-- 仓管防呆: F006 (六扇门测试租户) WH-WKS 仓库名从种子值 '鲜棉仓' 改为直白 '生产仓'.
--
-- 背景: V20260424_08__factory_warehouses.sql 全局给每个 factory seed WH-WKS/'鲜棉仓',
-- 对不熟悉行业术语的仓管员不够直白 (Steve: 仓管防呆, 避免选错仓).
-- 前端默认标签已同步改为 '生产仓' (web-admin/src/api/factoryWarehouse.ts
-- WAREHOUSE_TYPE_LABELS.WORKSHOP + WAREHOUSE_TYPE_DEFAULTS.WORKSHOP.name), 本迁移
-- 只补 F006 这条已落库的历史行 (新工厂走前端默认已经是 '生产仓', 不需要迁移).
--
-- 范围: 仅 F006 (测试租户), 且 name 精确匹配种子值 '鲜棉仓' 才改 —
-- 幂等 + 不影响其他工厂 / 不影响 LIUSHANMEN(六扇门正式客户, 独立工厂 ID, 未受影响)
-- 的盐化双仓模板 (LIUSHANMEN_SALTED_TEMPLATE 前端常量仍保留 'WH-WKS'/'鲜棉仓'
-- 不在本迁移范围内, 那是不同的名称语境/独立配置).
UPDATE factory_warehouses
SET name = '生产仓', updated_at = NOW()
WHERE factory_id = 'F006' AND code = 'WH-WKS' AND name = '鲜棉仓';
