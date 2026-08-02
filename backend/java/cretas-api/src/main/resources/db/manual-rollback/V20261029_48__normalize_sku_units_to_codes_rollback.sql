-- =============================================================================
-- V20261029_48 回滚: 把 SKU 单位还原成归一前的原值
--
-- 用法 (手动执行, 不由 Flyway 跑):
--   sudo -u postgres psql -d cretas_prod_db \
--     -f V20261029_48__normalize_sku_units_to_codes_rollback.sql
--
-- ⚠️ 只还原**当前值仍等于迁移写入值**的行:
--    迁移之后若有人手工改过某个 SKU 的单位, 那是比台账更新的事实, 不覆盖。
--
-- ⚠️ 回滚这条**通常还要一并回滚写入侧**: 若代码仍在把输入归一成码
--    (RawMaterialTypeServiceImpl#normalizeInventoryUnit 返回 normalized.code()),
--    单纯把数据改回中文, 下次保存 SKU 又会被写回码 —— 这正是 V20261029_32
--    当年「修好又漂回去」的成因。数据与写入侧必须同向。
-- =============================================================================

\echo '--- 回滚前: 台账多少行, 其中多少仍可安全还原 ---'
SELECT b.table_name,
       count(*) AS 台账行数,
       count(*) FILTER (WHERE
         (b.table_name = 'raw_material_types'
            AND EXISTS (SELECT 1 FROM raw_material_types t WHERE t.id = b.row_id AND t.unit = b.new_unit))
         OR (b.table_name = 'product_types'
            AND EXISTS (SELECT 1 FROM product_types t WHERE t.id = b.row_id AND t.unit = b.new_unit))
       ) AS 可还原
FROM backup_sku_units_20260802 b
GROUP BY 1;

UPDATE raw_material_types t
SET unit = b.old_unit, updated_at = NOW()
FROM backup_sku_units_20260802 b
WHERE b.table_name = 'raw_material_types' AND b.row_id = t.id AND t.unit = b.new_unit;

UPDATE product_types t
SET unit = b.old_unit, updated_at = NOW()
FROM backup_sku_units_20260802 b
WHERE b.table_name = 'product_types' AND b.row_id = t.id AND t.unit = b.new_unit;

\echo '--- 回滚后核对: 应回到中文为主 ---'
SELECT '原料' AS 表,
       count(*) FILTER (WHERE unit ~ '[一-龥]') AS 中文,
       count(*) FILTER (WHERE unit !~ '[一-龥]') AS 英文
FROM raw_material_types WHERE deleted_at IS NULL AND unit IS NOT NULL AND unit <> ''
UNION ALL
SELECT '成品',
       count(*) FILTER (WHERE unit ~ '[一-龥]'),
       count(*) FILTER (WHERE unit !~ '[一-龥]')
FROM product_types WHERE deleted_at IS NULL AND unit IS NOT NULL AND unit <> '';

-- 台账刻意保留, 使回滚可重复执行。确认不再需要后手动:
--   DROP TABLE backup_sku_units_20260802;
