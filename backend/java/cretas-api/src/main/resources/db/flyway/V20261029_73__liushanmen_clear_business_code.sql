-- =============================================================================
-- V20261029_73: 清空六膳门残留的 4 个 business_code —— 收掉我自己造出来的口径不一致
--
-- ## 这是在补一个我自己留下的坑
--   V20261029_67 换码时, 六膳门那 4 个 business_code 按「不丢历史」保留了(当时只有 4 条);
--   V20261029_72 处理 F006 时口径改成了清空(61 条占五分之一, 留着就是两套码并存)。
--
--   结果两个租户走了**两套口径**, 而且方向正好反了:
--     F006(测试租户)     business_code 已清空 → 界面显示新料号 BC/FL/YL   ✅ 干净
--     LIUSHANMEN(真客户)  还留着 4 个 M6…      → 界面显示 M601S2000001    ❌ 反而更脏
--
--   `getDisplayCode()` **优先返回 business_code**, 16 位码只是 fallback。而 business_code
--   的前缀就是 L3 的 base36 压缩(deriveStablePrefix) —— 它是**同一套分类体系的短表示**,
--   不是另一套编码。「16 位码彻底下架」这个目标下, 这 4 条也该走。
--
-- ## 改什么
--   六膳门 4 个物料的 business_code → NULL。
--   清掉之后 getDisplayCode() 回落到 code, 也就是它们已经换好的 YL0xx 料号。
--
-- ## 不动什么
--   - code(YL052..YL065 等)一个字不改
--   - material_business_code_prefixes / counters 的分配器状态保留(不影响展示, 且已停用字典)
--   - F006 已在 _72 处理过, 这里不重复
--
-- ## 环境守卫
--   一次性租户操作, 新建的 dev/test 库里六膳门没有 business_code → 干净跳过而不是 abort。
--
-- ## 回滚
--   db/manual-rollback/V20261029_73__liushanmen_clear_business_code_rollback.sql
-- =============================================================================

CREATE TABLE IF NOT EXISTS migration_lsm_bizcode_clear_20261029_73 (
    material_type_id  varchar(191) PRIMARY KEY,
    code              varchar(64),
    old_business_code varchar(64) NOT NULL,
    material_name     varchar(255),
    migrated_at       timestamp NOT NULL DEFAULT now()
);

DO $$
DECLARE
    v_total integer;
    v_rows  integer;
    v_cleared integer;
BEGIN
    SELECT count(*) INTO v_total
      FROM raw_material_types
     WHERE factory_id = 'LIUSHANMEN' AND business_code IS NOT NULL;

    IF v_total = 0 THEN
        RAISE NOTICE 'V20261029_73: 六膳门已无 business_code, 跳过(非 prod 环境的正常情况)';
        RETURN;
    END IF;

    INSERT INTO migration_lsm_bizcode_clear_20261029_73
                (material_type_id, code, old_business_code, material_name)
    SELECT id, code, business_code, name
      FROM raw_material_types
     WHERE factory_id = 'LIUSHANMEN' AND business_code IS NOT NULL
    ON CONFLICT (material_type_id) DO NOTHING;

    -- 防呆①: 清之前每一条都必须已经有可回落的 code, 否则清完 getDisplayCode() 没东西可显示
    IF EXISTS (
        SELECT 1 FROM raw_material_types
         WHERE factory_id = 'LIUSHANMEN' AND business_code IS NOT NULL
           AND (code IS NULL OR btrim(code) = '')
    ) THEN
        RAISE EXCEPTION 'V20261029_73 中止: 有物料 business_code 非空但 code 为空, 清了就没有可显示的编码';
    END IF;

    UPDATE raw_material_types
       SET business_code = NULL, updated_at = now()
     WHERE factory_id = 'LIUSHANMEN' AND business_code IS NOT NULL;
    GET DIAGNOSTICS v_cleared = ROW_COUNT;

    IF v_cleared <> v_total THEN
        RAISE EXCEPTION 'V20261029_73 中止: 预期清 % 条, 实际 %', v_total, v_cleared;
    END IF;

    -- 防呆②: 全平台不许再有 business_code(两个租户都处理完了)
    SELECT count(*) INTO v_rows FROM raw_material_types WHERE business_code IS NOT NULL;
    IF v_rows <> 0 THEN
        RAISE EXCEPTION 'V20261029_73 中止: 全平台仍有 % 条 business_code', v_rows;
    END IF;

    -- 防呆③: 六膳门物料数不许变
    SELECT count(*) INTO v_rows FROM raw_material_types WHERE factory_id = 'LIUSHANMEN';
    IF v_rows <> 129 THEN
        RAISE NOTICE 'V20261029_73 提示: 六膳门物料数为 %(评审时 129), 仅提示不中止', v_rows;
    END IF;

    RAISE NOTICE 'V20261029_73: 六膳门清空 business_code % 条, 展示回落到各自料号', v_cleared;
END $$;
