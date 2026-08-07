-- =============================================================================
-- V20261029_67: 六膳门下架 16 位分类码 —— 换回客户自己的料号 (YL 续号)
--
-- 背景 (Steve 拍板 2026-08-07)
--   16 位分类码在代码里的正式名字就是 `LegacyClassificationCode`
--   (RawMaterialType#getLegacyClassificationCode), `getDisplayCode()` 优先返回
--   business_code, 16 位只是 fallback —— 它是过渡期产物。
--
--   六膳门实际在用的是自己的料号(客户 Excel 台账里的「料号」列):
--     WL 101 个 (最大 WL2009) / YL 10 个 (最大 YL051) / BC 4 个 (最大 BC004)
--   而 14 个 16 位码**全是 2026-07-17 之后建的** —— 那天前端上线了一条硬性强制
--   (list.vue: 新建物料必须选 L1/L2/L3), 把客户从自己的编码习惯上推走了。
--   7-07 之前建的 115 个全是自由料号, 7-17 之后建的全是 16 位码, 分水岭一清二楚。
--
-- 改什么
--   那 14 个原料的 code: 16 位分类码 → YL052 .. YL065 (按 created_at, code 稳定排序)
--
-- ⛔ 为什么换码而不是删物料
--   这 14 个里有正在用的 —— `牛外脊西冷MB2+谷饲100天` 就是刚收货 2006kg 的那个,
--   还有 `瑞瀍-菲力/牛柳` / `双汇冻猪肝`。删物料会把批次/采购单/BOM 一起打断。
--   换码是安全的: **全部 7 条外键都指向 raw_material_types(id)** (RMT_<时间戳>),
--   **没有一条指向 code**。
--
-- 换码前已核: 三处可能冗余存码的快照列, 对这 14 个码**引用数全为 0**
--     inventory_ledger_snapshots.material_code            0
--     bom_item_substitutes.substitute_material_code_snapshot 0
--     supplier_materials.supplier_material_code           0
--
-- ⛔ 不动 business_code
--   那 4 个已有的 business_code (M5YZ7M000001 等) 保持原样 —— 它是 @Column(updatable=false),
--   且 business_code 的前缀就是 L3 的 base36 压缩(deriveStablePrefix), 属于同一套体系的
--   短表示。留着不影响展示(getDisplayCode 优先它), 清掉反而丢历史。
--
-- 回滚
--   db/manual-rollback/V20261029_67__liushanmen_retire_16digit_codes_rollback.sql
-- =============================================================================

CREATE TABLE IF NOT EXISTS migration_liushanmen_code_retire_20261029_67 (
    material_type_id varchar(191) PRIMARY KEY,
    old_code         varchar(64) NOT NULL,
    new_code         varchar(64) NOT NULL,
    material_name    varchar(255),
    migrated_at      timestamp   NOT NULL DEFAULT now()
);

DO $$
DECLARE
    v_start   integer;
    v_changed integer := 0;
BEGIN
    -- 续号起点 = 现有 YL 最大序号 + 1。只认 `YL` + 纯数字, 免得被别的写法带偏。
    SELECT COALESCE(MAX((regexp_replace(code, '^YL', ''))::integer), 0)
      INTO v_start
      FROM raw_material_types
     WHERE factory_id = 'LIUSHANMEN'
       AND code ~ '^YL[0-9]+$';

    INSERT INTO migration_liushanmen_code_retire_20261029_67
                (material_type_id, old_code, new_code, material_name)
    SELECT m.id,
           m.code,
           'YL' || lpad((v_start + row_number() OVER (ORDER BY m.created_at, m.code))::text, 3, '0'),
           m.name
      FROM raw_material_types m
     WHERE m.factory_id = 'LIUSHANMEN'
       AND m.is_active
       AND m.code ~ '^[0-9]{16}$'
    ON CONFLICT (material_type_id) DO NOTHING;

    -- 防呆: 新码若与任何既有码撞了就整体中止, 不允许出现半套
    IF EXISTS (
        SELECT 1
          FROM migration_liushanmen_code_retire_20261029_67 l
          JOIN raw_material_types m
            ON m.factory_id = 'LIUSHANMEN' AND m.code = l.new_code
         WHERE m.id <> l.material_type_id
    ) THEN
        RAISE EXCEPTION 'V20261029_67 中止: 续号与既有料号冲突, 请人工确认起点';
    END IF;

    UPDATE raw_material_types m
       SET code = l.new_code, updated_at = now()
      FROM migration_liushanmen_code_retire_20261029_67 l
     WHERE m.id = l.material_type_id
       AND m.code = l.old_code;
    GET DIAGNOSTICS v_changed = ROW_COUNT;

    RAISE NOTICE 'V20261029_67: 六膳门 16 位分类码换成客户料号 % 条 (YL% 起)', v_changed, v_start + 1;
END $$;

-- ⛔ 刻意**不清**六膳门的分段字典 —— 清了会把客户卡死, 建不了物料:
--   1. RawMaterialTypeServiceImpl#createMaterialType 第一行就是
--      requireValidSegmentChain(factoryId, dto.getSegmentCode()) —— **fail-closed**,
--      没有 L3 直接抛错
--   2. 前端「类别」下拉 materialFamilyOptions **由分段字典 L1 派生**, 字典空 → 一个选项都没有
--   3. 新建表单**没有任何输入框能填料号**, code 全靠 generateSP8Code 生成
--   4. 后端 `dto.setCode(generated)` 是**无条件覆盖**, 前端传 code 也会被盖掉
--
-- 也就是说「彻底下架」需要先给**无分段字典的租户**新建一条建档路径
-- (用户填料号 + 类别改走 MATERIAL_CATEGORY 枚举 + 后端分支不再 fail-closed)。
-- 那是新功能, 单独一步做; 做完再清字典。
--
-- 本迁移只做「换码」这一半: 客户视野里 16 位码当场消失, 而建档路径原样可用。
