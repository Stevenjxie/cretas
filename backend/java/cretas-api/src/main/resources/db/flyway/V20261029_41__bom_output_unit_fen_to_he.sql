-- 把 BOM 产出单位「份」对齐成产出 SKU 的「盒」。
--
-- ## 为什么要人工定, 前一条迁移碰不了
-- `V20261029_39__bom_output_unit_align_to_sku.sql` 只处理**当前值是纯 ASCII 码**的行
-- (bag→袋、box→盒 这类「同一个单位两种写法」, 权威别名表能证明它们等价)。
--
-- 「份」和「盒」在权威别名表里是**两个不同的 COUNT 单位**(`portion` vs `box`),
-- 别名表证明不了谁等于谁 —— 到底按份卖还是按盒卖是**业务事实**, 不是技术判断。
-- 所以 V20261029_39 刻意跳过, 留给人工。
--
-- ## 口径 (Steve 2026-08-01 拍板)
-- 「SKU 那个按照盒吧, 反正统一就行」 —— 以 **SKU 侧为准**, BOM 跟随 SKU。
--
-- ## 影响面 (prod 实测)
-- LIUSHANMEN 叮咚好食光系列 5 条生效 BOM: 份 → 盒
--   BOM-20260616-001 纸片牛腱肉 80g / BOM-20260625-001 轻卤门腔(猪舌)120g
--   BOM-20260702-001 泰式酸辣猪蹄 225g / BOM-20260702-002 红烧猪蹄 250g
--   BOM-20260702-003 卤猪蹄(去大骨)200g
--
-- ## ⚠️ 第 6 条用「份」的 BOM 刻意不动
-- `BOM-20260708-001 干式熟成鸡（半只）` 的 `product_type_id`
-- (b4f16b12-003d-4b64-a464-d94d69e17292) 在 product_types 里**根本不存在**
-- (不是软删, 是没有这行) —— 悬空引用, **没有 SKU 可对齐**。
-- 下面的 JOIN 天然把它排除; 它是独立的数据完整性问题, 已单独记录待处理。
--
-- ## 护栏
-- 只改 `份 → 盒` 这一对, 且要求产出 SKU 的单位**确实是「盒」**。
-- 其余中文↔中文的不一致(例如 `克 vs 盒`)仍然需要人工逐个定口径, 本迁移不猜、不碰。
--
-- ## 幂等
-- 条件更新 + `IS DISTINCT FROM`, 重复执行不产生额外变化。
-- 回滚见同目录 manual-rollback/。

UPDATE bom_recipes b
SET output_unit = p.unit
FROM product_types p
WHERE b.product_type_id = p.id
  AND b.deleted_at IS NULL
  AND b.output_unit = '份'
  AND p.unit = '盒'
  AND b.output_unit IS DISTINCT FROM p.unit;
