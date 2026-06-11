-- V20261021_01__grant_price_history_seq_to_app_user.sql
--
-- 修复: 原料调价静默失败 (六扇门 F006, BOM+单价审计 2026-06-11)。
--
-- ============================================================================
-- 背景
-- ============================================================================
-- 原料调价触发器 trg_raw_material_price_snapshot 在每次 raw_material_types 单价
-- 变更时向 raw_material_price_history 写一条价格快照 (审计/三价对比用)。该表 id 列
-- 是 serial/IDENTITY, 背后序列 raw_material_price_history_id_seq。
--
-- 应用 DB 用户 cretas_user 缺该序列的 USAGE 权限 → nextval() 抛
-- "permission denied for sequence raw_material_price_history_id_seq" → 触发器
-- abort → **任何原料调价整个事务回滚** (调价静默失败)。
--
-- prod 已在 2026-06-11 运行时手工 GRANT 恢复, 此迁移把修复落地, 防 DB 重建 /
-- test 环境复发 (per .claude/rules/server-operations.md 禁手工 DDL 不落迁移)。
--
-- ============================================================================
-- 幂等 + 防御
-- ============================================================================
-- - 序列不存在 (某环境表尚未建) → 跳过, 不报错。
-- - GRANT 本身幂等 (重复跑 = no-op)。
-- - 迁移运行用户若无 grant 权限 (insufficient_privilege) → WARNING 不中断
--   (prod 已手工修复, 此迁移仅补登记; 真正需要时迁移用户通常是 owner/superuser)。
-- ============================================================================

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_class
        WHERE relkind = 'S'
          AND relname = 'raw_material_price_history_id_seq'
    ) THEN
        BEGIN
            EXECUTE 'GRANT USAGE, SELECT ON SEQUENCE raw_material_price_history_id_seq TO cretas_user';
            RAISE NOTICE 'GRANTed USAGE,SELECT on raw_material_price_history_id_seq to cretas_user';
        EXCEPTION
            WHEN insufficient_privilege THEN
                RAISE WARNING 'Skipped GRANT on raw_material_price_history_id_seq: migration user lacks grant privilege (likely already granted out-of-band)';
            WHEN undefined_object THEN
                RAISE NOTICE 'Sequence raw_material_price_history_id_seq not found at GRANT time, skipping';
        END;
    ELSE
        RAISE NOTICE 'Sequence raw_material_price_history_id_seq does not exist in this DB, skipping GRANT (price-history trigger not provisioned here)';
    END IF;
END $$;
