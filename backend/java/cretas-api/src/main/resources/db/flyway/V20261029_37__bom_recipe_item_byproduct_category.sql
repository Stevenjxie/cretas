-- BOM 配方内容第四类「副产」 (2026-07-31)
--
-- 「枚举加了值, DB CHECK 白名单没跟上」漂移类的第 9 次发作 —— 但这次是**加值前**先发现的,
-- 不是等客户撞 500。发现经过: Task 5 计划写的是纯前端 (加「副产」页签 + 「添加副产」按钮),
-- 接手时去 prod 实测约束, 才看到:
--     chk_bri_category CHECK (material_category IN ('RAW','AUXILIARY','PACKAGING'))
-- 也就是说照计划做完, 「添加副产」100% 保存失败。
--
-- ⚠️ 通用门禁 EnumCheckConstraintDriftTest 看不见这一条: 它扫的是 @Enumerated 字段, 而
--    BomRecipeItem.materialCategory 是裸 String。所以本次靠 BomByproductCategoryMigrationContractTest 盯。
--
-- 🔴 存量影响 = 0: 放宽前不可能存在 BYPRODUCT 行 (旧约束就是这么禁的), 纯加值不可能让
--    既有 169 行 (prod 实测, 含 42 行 PACKAGING) 中的任何一行违反新约束, 也不改动任何数字。
--
-- 副产行是**产出声明**不是投入: 它记「这个配方预计产出哪个副产 SKU、多少量」, 因此
--    BomRecipeServiceImpl.recomputeFamilyCosts 明确跳过 BYPRODUCT 行 (见该方法内注释) ——
--    否则副产 SKU 没有采购价 → itemCost 为 null → 整个 family 的标准成本被判定为「不完整」。

ALTER TABLE bom_recipe_items DROP CONSTRAINT IF EXISTS chk_bri_category;

ALTER TABLE bom_recipe_items ADD CONSTRAINT chk_bri_category
    CHECK (material_category IN ('RAW', 'AUXILIARY', 'PACKAGING', 'BYPRODUCT'));

COMMENT ON COLUMN bom_recipe_items.material_category IS
    'RAW / AUXILIARY / PACKAGING = 投入; BYPRODUCT = 产出的副产声明(SKU + 预计产出量), 不计入成本池';
