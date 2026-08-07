-- =============================================================================
-- V20261029_69: 六膳门停用物料分段字典 —— 16 位分类码彻底下架的最后一步
--
-- Steve 拍板 2026-08-07:「我要彻底下架用业务代码代替」。
--
-- ## 为什么必须做这一步(不做等于没下架完)
--   V20261029_67 只把**存量** 14 个 16 位码换成了客户自己的料号(YL052–YL065)。
--   字典还在, 于是 `taxonomyMode` 仍为 true —— **新建的物料照样会生成 16 位码**,
--   下个月又会长出一批。真正的开关是这张字典。
--
-- ## 为什么是软删而不是物理删
--   `material_business_code_prefixes` 有 4 行(六膳门)通过**多列外键**指向
--   `material_code_segments`, 物理删会撞外键。
--   而软删已经足够: `MaterialCodeSegment` 带 @Where(deleted_at IS NULL), 后端判据
--   `countByFactoryIdAndLevel(factoryId, 1)` 与前端 segmentTree 都看不见软删行 →
--   `taxonomyMode` / `hasSegmentDictionary` 当场变 false, 建档路径切到「用户自填料号」。
--   软删也是这套字典本来就在用的停用方式(六膳门此前已软删 15 个 L2 + 226 个 L3),
--   且回滚只是把 deleted_at 置回 NULL。
--
-- ## 现状(2026-08-07 prod 实测)
--   L1 活 3 条(001 原料 / 002 包材 / 003 辅料), 软删 0
--   L2 活 2 条(001008 进口牛肉 / 001009 猪肉), 软删 15
--   L3 活 5 条, 软删 226
--   ⚠️ 链本来就是断的: L3 `0010070001`(猪肝) 的 parent_code `001007` 已被软删。
--   本迁移只动这 10 条还活着的。
--
-- ## 不动什么
--   - 4 个已发出的 business_code(M5YZ7M000001 等)与 material_business_code_prefixes
--     的 4 行前缀 —— @Column(updatable=false), 且 getDisplayCode() 优先它, 清掉反而丢历史
--   - 已经换成 YL0xx 的那 14 个物料的 code
--   - 其它工厂(F006 的字典原样保留, 它仍走分类码体系)
--
-- ## ⛔ 部署前置(不满足就别上)
--   PR「无分段字典的工厂改走用户自填料号 + 平台类别枚举」必须**已部署且真机验过一次
--   保存并回查落库**。否则这一刀下去六膳门当场建不了物料 ——
--   建档路径的入口在前端, 只有真按过保存才算验过(弹窗能开、格子能渲染都不算)。
--
-- ## 回滚
--   db/manual-rollback/V20261029_69__liushanmen_retire_segment_dictionary_rollback.sql
-- =============================================================================

-- ⚠️ segment_id 必须是 bigint —— `material_code_segments.id` 是 bigint 不是 varchar。
-- 写成 varchar 时**迁移照样跑通**(INSERT 会隐式转), 只有回滚脚本里的
-- `s.id = l.segment_id` 才会炸 `operator does not exist: bigint = character varying`。
-- 干跑必须连回滚一起跑往返, 否则这类错要等到真出事需要回滚那天才发现。
CREATE TABLE IF NOT EXISTS migration_liushanmen_segment_retire_20261029_69 (
    segment_id   bigint      PRIMARY KEY,
    level        smallint    NOT NULL,
    segment_code varchar(64) NOT NULL,
    migrated_at  timestamp   NOT NULL DEFAULT now()
);

DO $$
DECLARE
    v_before integer;
    v_rows   integer;
    v_after  integer;
BEGIN
    SELECT count(*) INTO v_before
      FROM material_code_segments
     WHERE factory_id = 'LIUSHANMEN' AND deleted_at IS NULL;

    IF v_before = 0 THEN
        RAISE NOTICE 'V20261029_69: 六膳门分段字典已为空, 无需处理';
        RETURN;
    END IF;

    INSERT INTO migration_liushanmen_segment_retire_20261029_69 (segment_id, level, segment_code)
    SELECT id, level, segment_code
      FROM material_code_segments
     WHERE factory_id = 'LIUSHANMEN' AND deleted_at IS NULL
    ON CONFLICT (segment_id) DO NOTHING;

    UPDATE material_code_segments
       SET deleted_at = now(), updated_at = now()
     WHERE factory_id = 'LIUSHANMEN' AND deleted_at IS NULL;
    GET DIAGNOSTICS v_rows = ROW_COUNT;

    -- 防呆①: 停用条数必须等于停用前的活跃条数
    IF v_rows <> v_before THEN
        RAISE EXCEPTION 'V20261029_69 中止: 预期停用 % 条, 实际 % 条', v_before, v_rows;
    END IF;

    -- 防呆②: 停完必须真的一条 L1 都不剩 —— 后端 taxonomyMode 就是看这个数
    SELECT count(*) INTO v_after
      FROM material_code_segments
     WHERE factory_id = 'LIUSHANMEN' AND level = 1 AND deleted_at IS NULL;
    IF v_after <> 0 THEN
        RAISE EXCEPTION 'V20261029_69 中止: 停用后仍有 % 条活跃 L1, taxonomyMode 不会关', v_after;
    END IF;

    -- 防呆③: 别的工厂一条都不许被碰(F006 仍走分类码体系)
    SELECT count(*) INTO v_after
      FROM material_code_segments
     WHERE factory_id = 'F006' AND level = 1 AND deleted_at IS NULL;
    IF v_after = 0 THEN
        RAISE EXCEPTION 'V20261029_69 中止: F006 的 L1 被误伤';
    END IF;

    RAISE NOTICE 'V20261029_69: 六膳门停用分段字典 % 条, 建档路径切到用户自填料号', v_rows;
END $$;
