-- P4 kg↔盒单位标准化: 为 F006 产品补填 grams_per_unit (标准克重)
-- 数据来源: 产品名称中明确标注克重 (120g / 80g / 120g)
-- 只在 grams_per_unit IS NULL 时更新, 防止重复执行覆盖已有配置

-- 猪舌 120g/盒 (4e345886-52e4-494a-bcb3-3f0ee9e126b2 = 叮咚好食光轻卤门腔（猪舌）120g)
UPDATE product_types
   SET grams_per_unit = 120
 WHERE id = '4e345886-52e4-494a-bcb3-3f0ee9e126b2'
   AND grams_per_unit IS NULL;

-- 牛腱 80g/盒 (c2974690-4ac7-4c17-9ad4-5ee5b12bb26c = 叮咚好食光纸片牛腱肉 80g)
UPDATE product_types
   SET grams_per_unit = 80
 WHERE id = 'c2974690-4ac7-4c17-9ad4-5ee5b12bb26c'
   AND grams_per_unit IS NULL;

-- 掌中宝 120g/盒 (1d7fbd73-8797-4933-83f1-46413a45992d = 叮咚好食光椒麻掌中宝 120g)
UPDATE product_types
   SET grams_per_unit = 120
 WHERE id = '1d7fbd73-8797-4933-83f1-46413a45992d'
   AND grams_per_unit IS NULL;

-- 猪蹄 200g/盒 (577d8a7f-9185-407a-8675-524f6dd2d565 = 叮咚好食光卤猪蹄(去大骨) 200g)
-- 注: 猪蹄工序 P2 defer, 但 grams_per_unit 仍应配全, 否则将来猪蹄完工 kg 不换算与其余盒装产品不一致
UPDATE product_types
   SET grams_per_unit = 200
 WHERE id = '577d8a7f-9185-407a-8675-524f6dd2d565'
   AND grams_per_unit IS NULL;

-- Post-verify
DO $$
DECLARE
    seeded INT;
BEGIN
    SELECT COUNT(*) INTO seeded
      FROM product_types
     WHERE id IN (
         '4e345886-52e4-494a-bcb3-3f0ee9e126b2',
         'c2974690-4ac7-4c17-9ad4-5ee5b12bb26c',
         '1d7fbd73-8797-4933-83f1-46413a45992d',
         '577d8a7f-9185-407a-8675-524f6dd2d565'
     )
       AND grams_per_unit IS NOT NULL;
    RAISE NOTICE 'V20260915_03: % / 4 F006 products have grams_per_unit set', seeded;
END $$;
