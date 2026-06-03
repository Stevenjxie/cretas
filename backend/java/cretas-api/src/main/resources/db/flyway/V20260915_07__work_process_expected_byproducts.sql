-- V20260915_07__work_process_expected_byproducts.sql
-- 副产物防呆预填: work_processes 加 expected_byproducts jsonb 列 + seed 猪舌两道
-- 幂等: ADD COLUMN IF NOT EXISTS + UPDATE WHERE id= (幂等)
-- Guard: work_processes 是 entity-only 表 (Hibernate 建, 非 Flyway 建).
--   fresh-DB Flyway 先于 Hibernate DDL 跑时表不存在 → SKIP 不报错.

DO $$
BEGIN
    -- Guard: entity-only table must exist before we can ALTER it.
    IF to_regclass('public.work_processes') IS NULL THEN
        RAISE NOTICE 'V20260915_07 skipped: work_processes not yet created (fresh-DB Flyway-before-Hibernate)';
        RETURN;
    END IF;

    -- Step 1: ADD COLUMN IF NOT EXISTS (idempotent)
    ALTER TABLE work_processes
        ADD COLUMN IF NOT EXISTS expected_byproducts jsonb;

    -- Step 2: Seed 猪舌 2 道副产物 (UPDATE 幂等: 重跑只是覆盖同值)
    --   WP-F006-ZS-01 修油 → 肥油
    --   WP-F006-ZS-04 去舌苔 → 舌苔碎肉
    UPDATE work_processes
    SET expected_byproducts = '[{"name":"肥油","unit":"kg","defaultEnabled":true}]'::jsonb,
        updated_at = NOW()
    WHERE id = 'WP-F006-ZS-01';

    UPDATE work_processes
    SET expected_byproducts = '[{"name":"舌苔碎肉","unit":"kg","defaultEnabled":true}]'::jsonb,
        updated_at = NOW()
    WHERE id = 'WP-F006-ZS-04';

END $$;
