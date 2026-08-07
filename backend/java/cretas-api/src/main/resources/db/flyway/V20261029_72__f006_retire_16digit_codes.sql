-- =============================================================================
-- V20261029_72: F006 下架 16 位分类码 —— 新编一套料号 + 停用分段字典
--
-- Steve 拍板 2026-08-07:「16 位编码我要求全部下架全平台」「新编一套吧，直接换码」。
-- LIUSHANMEN 已由 V20261029_67(换码) + V20261029_69(停字典) 做完; 这是最后一个租户。
--
-- ⛔ 只动 F006。
--
-- ## 三件事, 必须原子完成(半套比不做更糟)
--   ① 305 个物料的 code: 16 位分类码 → 新料号
--   ② business_code 清空(61 条)
--   ③ 分段字典停用(259 条活跃分段)
--
-- ### 为什么 ② 也要清
--   `getDisplayCode()` **优先返回 business_code**, 16 位码只是 fallback。
--   而 business_code 的前缀就是 L3 的 base36 压缩(deriveStablePrefix) —— 它是**同一套
--   分类体系的短表示**, 不是另一套编码。留着的话那 61 个物料在界面上依然显示
--   `M601S2000001` 而不是新料号, 「下架」只完成了一半。
--   (LIUSHANMEN 那次只有 4 条, 当时按「不丢历史」保留了; 这里 61 条占五分之一, 保留会让
--    客户看到两套码并存, 所以口径改成清空。旧值全部记在台账里, 回滚可还原。)
--
-- ### 为什么 ③ 也要做
--   字典还在 → `taxonomyMode` 仍为 true → **新建的物料照样生成 16 位码**, 下个月又长一批。
--   真正的开关是这张字典。软删而非物删: `material_business_code_prefixes` 有多列外键指向
--   `material_code_segments`, 物删会撞; 而实体带 @Where(deleted_at IS NULL),
--   软删后 `countByFactoryIdAndLevel` 与前端 segmentTree 都看不见 → 开关当场关闭。
--
-- ## 新料号规则
--   按**类别**分前缀, 按 `created_at, code` 稳定排序编号, 三位数字:
--     原料 → YL001..YL113   (113 个)
--     辅料 → FL001..FL133   (133 个)
--     包材 → BC001..BC059   ( 59 个)
--   沿用六膳门(LIUSHANMEN)客户自己的习惯(YL/BC), 辅料补一个 FL。
--   F006 现有 305 个码**全部**是 16 位, 不存在与既有料号撞号的可能(仍加防呆闸兜底)。
--
-- ## 前置(已满足)
--   无分段字典的建档路径必须先上线, 否则清完字典 F006 就建不了物料:
--     PR#2365 无字典 → 用户自填料号 + 平台类别枚举   ✅ 已部署
--     PR#2385 修「用户填的料号被 delete 掉」          ✅ 已部署并真机验证
--     PR#2379 类别下拉并上**存量在用**取值            ✅ 已部署
--   ⚠️ 最后一条对 F006 是硬前置: 它的类别是 原料/辅料/包材, 而平台 MATERIAL_CATEGORY 枚举
--      是 主材/辅材/调味料/包材/添加剂 —— `原料` 和 `辅料` 都不在枚举里, 全靠那个并集。
--
-- ## 建议顺序
--   先跑 V20261029_71(清空 F006 流水)再跑本迁移, 引用面最小。
--   但换码本身不依赖它: **全部 7 条外键都指向 raw_material_types(id)**, 没有一条指向 code。
--
-- ## 回滚
--   db/manual-rollback/V20261029_72__f006_retire_16digit_codes_rollback.sql
-- =============================================================================

CREATE TABLE IF NOT EXISTS migration_f006_recode_20261029_72 (
    material_type_id  varchar(191) PRIMARY KEY,
    old_code          varchar(64) NOT NULL,
    new_code          varchar(64) NOT NULL,
    old_business_code varchar(64),
    category          varchar(64),
    material_name     varchar(255),
    migrated_at       timestamp NOT NULL DEFAULT now()
);

-- segment_id 必须是 bigint —— material_code_segments.id 是 bigint 不是 varchar。
-- 写成 varchar 时迁移照样跑通(INSERT 隐式转), 只有回滚脚本的 `s.id = l.segment_id`
-- 才会炸 `operator does not exist: bigint = character varying`(2026-08-07 干跑实证)。
CREATE TABLE IF NOT EXISTS migration_f006_segment_retire_20261029_72 (
    segment_id   bigint      PRIMARY KEY,
    level        smallint    NOT NULL,
    segment_code varchar(64) NOT NULL,
    migrated_at  timestamp   NOT NULL DEFAULT now()
);

DO $$
DECLARE
    v_total    integer;
    v_rows     integer;
    v_before   integer;
    v_dup      text;
    v_lsm_before integer;
BEGIN
    -- 🔴 环境守卫: 一次性租户操作, 只对装着那批 prod 数据的库有意义。
    -- 新建的 dev/test 库里 F006 没有 16 位码, 必须**干净跳过**而不是 abort ——
    -- 否则任何新环境跑 Flyway 都会中止, 应用起不来。
    SELECT count(*) INTO v_total
      FROM raw_material_types WHERE factory_id = 'F006' AND code ~ '^[0-9]{16}$';
    IF v_total = 0 THEN
        RAISE NOTICE 'V20261029_72: F006 没有 16 位码, 跳过(非 prod 环境的正常情况)';
        RETURN;
    END IF;

    SELECT count(*) INTO v_lsm_before FROM raw_material_types WHERE factory_id = 'LIUSHANMEN';

    ---------------------------------------------------------------------------
    -- ① 算新码并入台账
    ---------------------------------------------------------------------------
    INSERT INTO migration_f006_recode_20261029_72
                (material_type_id, old_code, new_code, old_business_code, category, material_name)
    SELECT m.id,
           m.code,
           CASE m.category
               WHEN '原料' THEN 'YL'
               WHEN '辅料' THEN 'FL'
               WHEN '包材' THEN 'BC'
               ELSE 'WL'          -- 兜底: 出现预期外的类别也给一个前缀, 不静默跳过
           END
           || lpad(row_number() OVER (
                  PARTITION BY m.category ORDER BY m.created_at, m.code)::text, 3, '0'),
           m.business_code,
           m.category,
           m.name
      FROM raw_material_types m
     WHERE m.factory_id = 'F006'
       AND m.code ~ '^[0-9]{16}$'
    ON CONFLICT (material_type_id) DO NOTHING;

    -- 防呆①: 新码自身不许重复
    SELECT string_agg(new_code, ', ') INTO v_dup
      FROM (SELECT new_code FROM migration_f006_recode_20261029_72
             GROUP BY new_code HAVING count(*) > 1) x;
    IF v_dup IS NOT NULL THEN
        RAISE EXCEPTION 'V20261029_72 中止: 新码内部重复 —— %', v_dup;
    END IF;

    -- 防呆②: 新码不许与 F006 已有的任何码撞
    IF EXISTS (
        SELECT 1 FROM migration_f006_recode_20261029_72 l
          JOIN raw_material_types m ON m.factory_id = 'F006' AND m.code = l.new_code
         WHERE m.id <> l.material_type_id
    ) THEN
        RAISE EXCEPTION 'V20261029_72 中止: 新码与既有料号冲突, 请人工确认编号规则';
    END IF;

    ---------------------------------------------------------------------------
    -- ② 换码 + 清 business_code
    ---------------------------------------------------------------------------
    UPDATE raw_material_types m
       SET code = l.new_code,
           business_code = NULL,
           updated_at = now()
      FROM migration_f006_recode_20261029_72 l
     WHERE m.id = l.material_type_id
       AND m.code = l.old_code;
    GET DIAGNOSTICS v_rows = ROW_COUNT;

    -- 防呆③: 换码条数必须等于台账条数
    IF v_rows <> (SELECT count(*) FROM migration_f006_recode_20261029_72) THEN
        RAISE EXCEPTION 'V20261029_72 中止: 实换 % 条, 台账 % 条',
            v_rows, (SELECT count(*) FROM migration_f006_recode_20261029_72);
    END IF;

    -- 防呆④: 换完 F006 不许再有 16 位码
    SELECT count(*) INTO v_rows
      FROM raw_material_types WHERE factory_id = 'F006' AND code ~ '^[0-9]{16}$';
    IF v_rows <> 0 THEN
        RAISE EXCEPTION 'V20261029_72 中止: 换码后 F006 仍有 % 个 16 位码', v_rows;
    END IF;

    ---------------------------------------------------------------------------
    -- ③ 停用分段字典 —— 关掉「新建还会生成 16 位码」这个开关
    ---------------------------------------------------------------------------
    SELECT count(*) INTO v_before
      FROM material_code_segments WHERE factory_id = 'F006' AND deleted_at IS NULL;

    IF v_before > 0 THEN
        INSERT INTO migration_f006_segment_retire_20261029_72 (segment_id, level, segment_code)
        SELECT id, level, segment_code
          FROM material_code_segments WHERE factory_id = 'F006' AND deleted_at IS NULL
        ON CONFLICT (segment_id) DO NOTHING;

        UPDATE material_code_segments
           SET deleted_at = now(), updated_at = now()
         WHERE factory_id = 'F006' AND deleted_at IS NULL;
        GET DIAGNOSTICS v_rows = ROW_COUNT;

        IF v_rows <> v_before THEN
            RAISE EXCEPTION 'V20261029_72 中止: 预期停用 % 条分段, 实际 %', v_before, v_rows;
        END IF;
    END IF;

    -- 防呆⑤: 停完必须一条活跃 L1 都不剩 —— taxonomyMode 就是看这个数
    SELECT count(*) INTO v_rows
      FROM material_code_segments WHERE factory_id = 'F006' AND level = 1 AND deleted_at IS NULL;
    IF v_rows <> 0 THEN
        RAISE EXCEPTION 'V20261029_72 中止: 停用后仍有 % 条活跃 L1', v_rows;
    END IF;

    -- 防呆⑥: 全平台 16 位码归零(LIUSHANMEN 已在 _67 处理, 其它工厂本来就没有)
    SELECT count(*) INTO v_rows FROM raw_material_types WHERE code ~ '^[0-9]{16}$';
    IF v_rows <> 0 THEN
        RAISE EXCEPTION 'V20261029_72 中止: 全平台仍有 % 个 16 位码, 未彻底下架', v_rows;
    END IF;

    -- 防呆⑦: LIUSHANMEN 一条都不许被碰
    -- ⚠️ 判据用「与迁移开始时相同」而不是写死 129 —— 写死会让这条迁移只能在 prod 跑
    SELECT count(*) INTO v_rows FROM raw_material_types WHERE factory_id = 'LIUSHANMEN';
    IF v_rows <> v_lsm_before THEN
        RAISE EXCEPTION 'V20261029_72 中止: LIUSHANMEN 物料数从 % 变成 %, 误伤真客户',
            v_lsm_before, v_rows;
    END IF;

    RAISE NOTICE 'V20261029_72 完成: F006 换码 % 条 / 停用分段字典 % 条; 全平台 16 位码归零',
        (SELECT count(*) FROM migration_f006_recode_20261029_72), v_before;
END $$;
