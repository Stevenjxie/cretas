-- 半成品注入工序 (张权 R4): 可配置的「从中段起步选库里已有半成品/成品直接投料」开关。
-- 加在 product_work_processes 上, 逐道录入 SFI/FG picker 的 config-driven 显示条件。
-- product_work_processes is entity-only in fresh CI DBs: Flyway runs before Hibernate DDL.
--
-- 新增 BOOLEAN 列, 加性 (additive) 迁移, 无 CHECK/枚举约束需同步放宽 (非枚举列)。
-- DEFAULT FALSE: 现有工序默认普通工序; 逐道录入 picker 由 ProcessDataTable.vue 现有 archetype
-- 兜底 (熟制/气调/焯水/滚揉/去舌苔) 保证零回归, 本 flag 只是额外的显式 config-driven 注入点。
DO $$
BEGIN
    IF to_regclass('public.product_work_processes') IS NULL THEN
        RAISE NOTICE 'V20261027_41 skipped: product_work_processes not present before Hibernate DDL';
        RETURN;
    END IF;

    ALTER TABLE product_work_processes
        ADD COLUMN IF NOT EXISTS allow_semi_finished_injection BOOLEAN NOT NULL DEFAULT FALSE;

    COMMENT ON COLUMN product_work_processes.allow_semi_finished_injection IS
        'Whether this product process is a semi-finished/finished-goods injection step (张权 R4): '
        'when true, the per-process entry screen offers the SFI/FG inventory picker so production can '
        'start from mid-chain using existing stock, skipping earlier processes.';
END $$;
