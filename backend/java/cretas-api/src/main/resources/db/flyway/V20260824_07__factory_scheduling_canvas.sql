-- Canvas P3 batch 2: factory_scheduling_config @Version + soft-delete partial-unique migration.
--
-- 背景: P3 半-Canvas-ed UI wrap of FactorySchedulingConfig entity. AUD-4 P1 optimistic-lock
-- requires `version` column (mirror FactoryThreshold / IndicatorDefinition pattern).
--
-- Changes:
-- 1. ADD COLUMN version BIGINT NOT NULL DEFAULT 0 (JPA @Version backing column)
-- 2. ADD COLUMN deleted_at TIMESTAMP NULL (soft-delete capability, currently no @Where but
--    Canvas controller's delete endpoint sets it; future BaseEntity migration ready)
-- 3. Convert UNIQUE(factory_id) → partial-unique on (factory_id) WHERE deleted_at IS NULL
--    so soft-deleted rows don't block re-creation
--
-- Idempotent: IF NOT EXISTS / IF EXISTS guards.

-- ⚠️ 2026-06-01 修 e2e-pr-gate 全新 CI DB: factory_scheduling_config 是 Hibernate JPA entity 表
--   (无 Flyway CREATE)。全新 DB 上 Flyway 先于 ddl-auto 跑 → 表不存在 → 裸 ALTER + ::regclass
--   cast (line below) 报 relation does not exist 阻断启动。表相关操作整块包 to_regclass 守卫:
--   表存在才跑; 不存在跳过 (Hibernate 随后建表+列, version/deleted_at 由 entity 声明)。
--   prod 表早已存在 → 行为不变。(FUNCTION 定义与表无关, 留在守卫外。)

-- Trigger function (table-independent — 可在表不存在时也定义)
CREATE OR REPLACE FUNCTION update_factory_scheduling_config_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DO $$
DECLARE
    cons_name TEXT;
BEGIN
    IF to_regclass('public.factory_scheduling_config') IS NULL THEN
        RAISE NOTICE 'V20260824_07 skipped: factory_scheduling_config table absent (fresh DB pre-ddl-auto), non-fatal';
        RETURN;
    END IF;

    -- 1. version (JPA @Version backing) + 2. deleted_at (soft-delete)
    ALTER TABLE factory_scheduling_config ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
    ALTER TABLE factory_scheduling_config ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP NULL;

    -- 3. Convert UNIQUE(factory_id) → partial-unique (factory_id WHERE deleted_at IS NULL)
    SELECT conname INTO cons_name
    FROM pg_constraint
    WHERE conrelid = 'factory_scheduling_config'::regclass
      AND contype = 'u'
      AND pg_get_constraintdef(oid) LIKE '%(factory_id)%';
    IF cons_name IS NOT NULL THEN
        EXECUTE 'ALTER TABLE factory_scheduling_config DROP CONSTRAINT ' || quote_ident(cons_name);
    END IF;

    CREATE UNIQUE INDEX IF NOT EXISTS uk_fsc_factory_active
        ON factory_scheduling_config(factory_id)
        WHERE deleted_at IS NULL;

    DROP TRIGGER IF EXISTS trigger_fsc_updated_at ON factory_scheduling_config;
    CREATE TRIGGER trigger_fsc_updated_at
    BEFORE UPDATE ON factory_scheduling_config
    FOR EACH ROW EXECUTE FUNCTION update_factory_scheduling_config_updated_at();
END $$;
