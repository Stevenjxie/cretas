-- 把 BOM 配方的产出单位 (bom_recipes.output_unit) 对齐到它自己产出 SKU 的单位。
--
-- 背景: V20261029_32__unit_codes_to_chinese.sql 把计数/包装英文码中文化时, 覆盖了
--   raw_material_types / product_types / material_batches / workflow_task_ports 四张表,
--   **唯独漏了 bom_recipes.output_unit**。
--   于是 SKU 侧 bag→袋、box→盒 改成了中文, BOM 侧留在英文码 ——
--   客户看到的「报工单位 袋 / BOM 单位 bag」这类不一致, 正是那条 migration 自己造出来的。
--
--   代码侧已安全(#2077/#2079 让单位比较统一走权威别名表, 所以不会再误拦),
--   但数据侧仍然是脏的: 同一件事在两张表里写着两种字面值。本迁移收敛数据。
--
-- 判据与 V20261029_32 的第 2/3 段一致 —— **对齐到该 SKU 自己的单位, 而不是名录通名**。
--   (名录里 pcs 的通名是「件」, 但一批「温氏黄油鸡」的正确单位是该物料配的「只」;
--    同理 BOM 的产出单位应当等于它产出的那个 SKU 的单位。)
--
-- 四条护栏:
--   1. 只改**当前值是纯 ASCII 码**的行 —— 判据落在当前值上, 中文值一律不动。
--   2. 只改 COUNT / 包装类; WEIGHT / VOLUME (kg、g、L、ml) 一律不碰。
--      它们是国际计量符号, 与 V20261029_32 的取舍保持一致。
--   3. 只在**产出 SKU 自己已经是中文**时才对齐 (p.unit !~ '^[a-zA-Z]+$')。
--      ⚠️ 这条是本迁移最关键的护栏: prod 实测 F006 有 7 个 product_types 的 unit
--      仍然是英文 box (V20261029_32 跑完之后新建的 SKU), 它们对应的 6 条 BOM
--      目前 box = box 本来就是一致的。若少了这条护栏、按「名录通名」一律中文化,
--      这 6 行会被改成「盒」而 SKU 侧还是 box —— **修好 10 行的同时新造 6 行不一致**。
--   4. 量纲不同的不在此列(BOM 克 vs SKU 盒、BOM g vs SKU 盒 等): 别名表救不了,
--      需要人工核对业务含义, 本迁移不猜、不碰。
--
-- prod 实测影响面 (2026-08-01 全租户实测, 共 17 行):
--   F006        box→盒   10 行 (其中 is_current 4 行)
--   LIUSHANMEN  bag→袋    6 行 (其中 is_current 1 行)
--   LIUSHANMEN  box→盒    1 行 (is_current 0 行)
--   改完后仍不一致的 21 行全部是上面第 4 条那类量纲问题(g vs kg、克 vs 盒、份 vs 盒),
--   已单独记录待人工核对, 不由本迁移处理。
--
-- 幂等: 条件更新 + IS DISTINCT FROM, 重复执行不产生额外变化。
-- 回滚: 见同 PR 附带的 rollback SQL(按 id 还原 output_unit 原值; 迁移前已抓取 pre-image)。

UPDATE bom_recipes b
SET output_unit = p.unit
FROM product_types p, unit_of_measurements cur
WHERE b.product_type_id = p.id
  AND b.deleted_at IS NULL
  AND lower(b.output_unit) = lower(cur.unit_code)
  AND cur.unit_name IS NOT NULL AND cur.unit_name <> ''
  AND cur.category NOT IN ('WEIGHT', 'VOLUME')
  AND b.output_unit ~ '^[a-zA-Z]+$'
  AND p.unit !~ '^[a-zA-Z]+$'
  AND b.output_unit IS DISTINCT FROM p.unit;
