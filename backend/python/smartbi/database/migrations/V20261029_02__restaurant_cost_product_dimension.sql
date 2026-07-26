-- SmartBI-owned recipe cost product-name read model.
--
-- Cost facts are keyed by the operational product_types.id. Historical Gold
-- rows (especially the public demo seed) can legitimately outlive the
-- operational seed rows that created them. Keeping the tenant-scoped name/key
-- snapshot beside Gold lets margin, target-margin and finance ETL resolve those
-- rows without writing demo products back into the ERP database.

CREATE TABLE IF NOT EXISTS dim_restaurant_cost_product (
    factory_id VARCHAR(100) NOT NULL,
    product_source_pk VARCHAR(191) NOT NULL,
    product_name TEXT NOT NULL,
    normalized_name TEXT NOT NULL,
    source VARCHAR(40) NOT NULL DEFAULT 'recipe_etl',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (factory_id, product_source_pk)
);

CREATE INDEX IF NOT EXISTS idx_restaurant_cost_product_name
    ON dim_restaurant_cost_product (factory_id, normalized_name)
    WHERE is_active = TRUE;

ALTER TABLE dim_restaurant_cost_product ENABLE ROW LEVEL SECURITY;
ALTER TABLE dim_restaurant_cost_product FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON dim_restaurant_cost_product;
CREATE POLICY tenant_isolation ON dim_restaurant_cost_product
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));

GRANT SELECT, INSERT, UPDATE, DELETE ON dim_restaurant_cost_product TO smartbi_user;

-- Backfill the existing RES_3101_009 Gold cost keys from the repository's
-- historical QHJ demo product seeds. This mutates SmartBI metadata only; it
-- does not recreate product_types/recipes in the operational Cretas database.
SELECT set_config('app.factory_id', 'RES_3101_009', false);
WITH seed(product_source_pk, product_name) AS (
    VALUES
        ('pt_qhj_001', '招牌青花椒味(单人份)'),
        ('pt_qhj_002', '招牌青花椒鱼可乐单人套餐'),
        ('pt_qhj_003', '米饭'),
        ('pt_qhj_004', '招牌青花椒味(2-3人份)'),
        ('pt_qhj_005', '招牌青花椒味(小份)'),
        ('pt_qhj_006', '小炒现切吊龙'),
        ('pt_qhj_007', '经典红糖冰粉'),
        ('pt_qhj_008', '成都冒烤鸭(单人份)'),
        ('pt_qhj_009', '古法盐焗鸡'),
        ('pt_qhj_010', '牛骨髓麻婆豆腐'),
        ('pt_qhj_011', '招牌青花椒鱼(微麻微辣)[小份]'),
        ('pt_qhj_012', '营养多C番茄味(单人份)'),
        ('pt_qhj_013', '招牌青花椒鱼(微麻微辣)[大份]'),
        ('pt_qhj_014', '招牌青花椒鱼(微麻微辣)[小份活鱼现做]'),
        ('pt_qhj_015', '特色青花椒鱼[活鱼现做]'),
        ('pt_qhj_016', '招牌青花椒鱼(微麻微辣)[大份活鱼现做]'),
        ('pt_qhj_017', '铜锅焖牛肋条'),
        ('pt_qhj_018', '招牌青花椒鱼(微麻微辣)[小份小心鱼刺]'),
        ('pt_qhj_019', '油爆罗氏虾'),
        ('pt_qhj_020', '凤梨排骨'),
        ('pt_qhj_021', '鲜腐竹杭白菜'),
        ('pt_qhj_022', '古法秘制酸菜味(单人份)'),
        ('pt_qhj_023', '招牌青花椒鱼(微麻微辣)[小份手工去刺]'),
        ('pt_qhj_024', '金牌毛血旺'),
        ('pt_qhj_025', '乌蒙山干锅牛肉'),
        ('pt_qhj_026', '五彩小炒'),
        ('pt_qhj_027', '招牌秘制青花椒味(2-3人)'),
        ('pt_qhj_028', '米饭(单人份)'),
        ('pt_qhj_029', '酸菜吊龙炒饭'),
        ('pt_qhj_030', '美鱼美蛙(2-3人份)'),
        ('pt_qhj_031', '摇滚小酥肉'),
        ('pt_qhj_032', '特色青花椒鱼[活鱼手工去刺]'),
        ('pt_qhj_033', '青小米椒花甲'),
        ('pt_qhj_034', '招牌青花椒鱼(微麻微辣)[大份手工去刺]'),
        ('pt_qhj_035', '招牌青花椒鱼(2-3人份)'),
        ('pt_qhj_036', '剁椒跳跳蛙'),
        ('pt_qhj_037', '爆香麻辣水煮鱼(单人份)'),
        ('pt_qhj_038', '鸡汁纸片笋'),
        ('pt_qhj_039', '白灼生菜'),
        ('pt_qhj_040', '肉沫包浆豆腐煲'),
        ('pt_qhj_041', '宫爆超大虾球'),
        ('pt_qhj_042', '招牌秘制青花椒味(1-2人)'),
        ('pt_qhj_043', '美鱼美蛙'),
        ('pt_qhj_044', '娃娃菜'),
        ('pt_qhj_045', '峨边脆笋'),
        ('pt_qhj_046', '山城小酥肉'),
        ('pt_qhj_047', '麻辣干锅鱼片[黑鱼]'),
        ('pt_qhj_048', '营养多C番茄味(2-3人份)'),
        ('pt_qhj_049', '柠檬手舂无骨鸡爪'),
        ('pt_qhj_050', '金汤肥牛酸菜鱼(单人份)'),
        ('pt_qhj_051', '招牌青花椒鱼(微麻微辣)[大份小心鱼刺]'),
        ('pt_qhj_052', '营养多C番茄味(小份)'),
        ('pt_qhj_053', '红糖夹心糍粑'),
        ('pt_qhj_054', '酸辣蕨根粉'),
        ('pt_qhj_055', '金牌蒜蓉粉丝虾仁'),
        ('pt_qhj_056', '铜锅霸道牛蛙虾'),
        ('pt_qhj_057', '咸蛋黄鸡翅'),
        ('pt_qhj_058', '金汤肥牛酸菜鱼'),
        ('pt_qhj_059', '鱼羊鲜'),
        ('pt_qhj_060', '手钓东山小管'),
        ('pt_qhj_061', '莴笋'),
        ('pt_qhj_062', '金针菇'),
        ('pt_qhj_063', '川式小炒黑猪肉'),
        ('pt_qhj_064', '沸腾麻辣鱼[活鱼现做]'),
        ('pt_qhj_065', '营养多C番茄鱼[小份]'),
        ('pt_qhj_066', '特色青花椒鱼-手工去刺[小份]'),
        ('pt_qhj_067', '成都冒烤鸭(大份)'),
        ('pt_qhj_068', '暖冬鱼羊鲜(单人份)'),
        ('pt_qhj_069', '乐山把把串'),
        ('pt_qhj_070', '手作冰豆花'),
        ('pt_qhj_071', '脆哨茶油蒸蛋'),
        ('pt_qhj_072', '糯米樟茶鸭'),
        ('pt_qhj_073', '口水鸡'),
        ('pt_qhj_074', '豆腐皮'),
        ('pt_qhj_075', '江油鸭血肥肠'),
        ('pt_qhj_076', '响口三脆'),
        ('pt_qhj_077', '双人餐'),
        ('pt_qhj_078', '川式鲍鱼小炒肉'),
        ('pt_qhj_079', '咸蛋黄鸡翅[4个]'),
        ('pt_qhj_080', '特色青花椒鱼-手工去刺[大份]'),
        ('pt_qhj_081', '放心吃鱼品质双人套餐'),
        ('pt_qhj_082', '南乳蹄花鸡爪煲'),
        ('pt_qhj_083', '脆肠爆腰花'),
        ('pt_qhj_084', '牛腩牛筋煲'),
        ('pt_qhj_085', '家烧豆面'),
        ('pt_qhj_086', '咸蛋黄牛蛙'),
        ('pt_qhj_087', '美鱼美蛙[活鱼现做]'),
        ('pt_qhj_088', '清蒸宁德大黄鱼'),
        ('pt_qhj_089', '千页豆腐'),
        ('pt_qhj_090', '古法秘制酸菜鱼(小份)'),
        ('pt_qhj_091', '手工糍粑'),
        ('pt_qhj_092', '川式小炒肉'),
        ('pt_qhj_093', '成都冒烤鸭(小份)'),
        ('pt_qhj_094', '古法秘制酸菜味(2-3人份)'),
        ('pt_qhj_095', '杂菌煲'),
        ('pt_qhj_096', '凉拌鲜豆苗'),
        ('pt_qhj_097', '【无刺】招牌青花椒鱼(单人份)'),
        ('pt_qhj_098', '来吃鱼鸭双人套餐'),
        ('pt_qhj_099', '招牌秘制青花椒味(单人份)'),
        ('pt_qhj_100', '怪好吃多味(2-3人)'),
        ('pt_qhj_101', '来吃鱼鸭约惠双人套餐'),
        ('pt_qhj_102', '古法秘制酸菜鱼[小份]'),
        ('pt_qhj_103', '年糕'),
        ('pt_qhj_104', '营养多C番茄鱼[小份手工去刺]'),
        ('pt_qhj_105', '营养多C番茄鱼[小份活鱼现做]'),
        ('pt_qhj_106', '大碗冰粉'),
        ('pt_qhj_107', '【无刺】招牌青花椒味(小份)'),
        ('pt_qhj_108', '鲜浓酱香味(2-3人)'),
        ('pt_qhj_109', '大家都这样组合配菜'),
        ('pt_qhj_110', '招牌青花椒味多人专享套餐'),
        ('pt_qhj_111', '【无刺】招牌青花椒味(2-3人份)'),
        ('pt_qhj_112', '烧椒皮蛋'),
        ('pt_qhj_113', '双味包浆豆腐'),
        ('pt_qhj_114', '青笋片'),
        ('pt_qhj_115', '营养多C番茄鱼[大份]'),
        ('pt_qhj_116', '卤炸牛肉串'),
        ('pt_qhj_117', '泡椒跳跳蛙'),
        ('pt_qhj_118', '招牌特色青花椒鱼(2-3人份)'),
        ('pt_qhj_119', '香煎鲜虾饼'),
        ('pt_qhj_120', '陈皮山楂饮'),
        ('pt_qhj_121', '爆香冒烤鱼(单人份)'),
        ('pt_qhj_122', '香辣牛蛙'),
        ('pt_qhj_123', '豆汤菌菇煲'),
        ('pt_qhj_124', '小份手工冰粉'),
        ('pt_qhj_125', '龙眼牛乳冰'),
        ('pt_qhj_126', '土豆粉'),
        ('pt_qhj_127', '鲜浓酱香味(1-2人)'),
        ('pt_qhj_128', '甄选优惠家庭套餐'),
        ('pt_qhj_129', '招牌特色青花椒鱼(小份)'),
        ('pt_qhj_130', '成都冒烤鸭可乐单人套餐'),
        ('pt_qhj_131', '古法秘制酸菜鱼[大份]'),
        ('pt_qhj_132', '江油肥肠'),
        ('pt_qhj_133', '营养多C番茄鱼[小份小心鱼刺]'),
        ('pt_qhj_134', '干锅无刺黑鱼配时蔬套餐'),
        ('pt_qhj_135', '青提牛乳冰'),
        ('pt_qhj_136', '营养多C番茄鱼[大份手工去刺]')
)
INSERT INTO dim_restaurant_cost_product (
    factory_id, product_source_pk, product_name, normalized_name,
    source, is_active
)
SELECT
    'RES_3101_009',
    product_source_pk,
    product_name,
    LOWER(REGEXP_REPLACE(BTRIM(product_name), '\s+', ' ', 'g')),
    'legacy_qhj_demo_seed',
    TRUE
FROM seed
ON CONFLICT (factory_id, product_source_pk) DO UPDATE SET
    product_name = EXCLUDED.product_name,
    normalized_name = EXCLUDED.normalized_name,
    source = EXCLUDED.source,
    is_active = TRUE,
    updated_at = NOW();
