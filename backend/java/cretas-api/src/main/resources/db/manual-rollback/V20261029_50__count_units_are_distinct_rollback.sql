-- =============================================================================
-- V20261029_50 回滚: 把计数单位重新折回 pcs (即恢复 V20261029_48 的状态)
--
-- 用法 (手动执行, 不由 Flyway 跑):
--   sudo -u postgres psql -d cretas_prod_db \
--     -f V20261029_50__count_units_are_distinct_rollback.sql
--
-- ⚠️ 回滚这条**必须一并回滚写入侧**: 若代码仍按「只/个/件 保字面」落库
--    (UnitContractServiceImpl#storageUnit 规则 2), 单纯把数据折回 pcs, 下次保存 SKU
--    又会写回中文 —— 数据与写入侧必须同向。这正是 V20261029_32 当年「修好又漂回去」的成因。
--
-- ⚠️ 批次侧「纯翻译型」那部分**不回滚**: 把批次单位从档案的码改回中文没有意义
--    (两种写法本就等价), 且原值未单独留台账。
-- =============================================================================

\echo '--- 回滚前: 台账里计数那批有多少行, 其中多少仍可安全折回 ---'
SELECT b.table_name,
       count(*) AS 台账行数,
       count(*) FILTER (WHERE
         (b.table_name = 'raw_material_types'
            AND EXISTS (SELECT 1 FROM raw_material_types t WHERE t.id = b.row_id AND t.unit = b.old_unit))
         OR (b.table_name = 'product_types'
            AND EXISTS (SELECT 1 FROM product_types t WHERE t.id = b.row_id AND t.unit = b.old_unit))
       ) AS 可折回
FROM backup_sku_units_20260802 b
WHERE b.new_unit = 'pcs'
GROUP BY 1;

-- 只折回「当前值仍等于 V20261029_50 还原成的中文」的行 —— 之后被人手工改过的不覆盖
UPDATE raw_material_types t
SET unit = b.new_unit, updated_at = NOW()
FROM backup_sku_units_20260802 b
WHERE b.table_name = 'raw_material_types'
  AND b.row_id = t.id
  AND b.new_unit = 'pcs'
  AND t.unit = b.old_unit;

UPDATE product_types t
SET unit = b.new_unit, updated_at = NOW()
FROM backup_sku_units_20260802 b
WHERE b.table_name = 'product_types'
  AND b.row_id = t.id
  AND b.new_unit = 'pcs'
  AND t.unit = b.old_unit;

\echo '--- 回滚后: 计数单位分布 ---'
SELECT unit, count(*) FROM raw_material_types
WHERE deleted_at IS NULL AND unit IN ('pcs','件','个','只') GROUP BY unit ORDER BY 2 DESC;
