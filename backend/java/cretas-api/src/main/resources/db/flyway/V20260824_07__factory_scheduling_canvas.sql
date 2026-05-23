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

-- 1. Add version column (JPA @Version backing)
ALTER TABLE factory_scheduling_config
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- 2. Add deleted_at column (soft-delete capability)
ALTER TABLE factory_scheduling_config
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP NULL;

-- 3. Convert UNIQUE(factory_id) → partial-unique (factory_id WHERE deleted_at IS NULL)
-- Drop the existing UNIQUE constraint first if present, then add partial-unique index.
DO $$
DECLARE
    cons_name TEXT;
BEGIN
    SELECT conname INTO cons_name
    FROM pg_constraint
    WHERE conrelid = 'factory_scheduling_config'::regclass
      AND contype = 'u'
      AND pg_get_constraintdef(oid) LIKE '%(factory_id)%';
    IF cons_name IS NOT NULL THEN
        EXECUTE 'ALTER TABLE factory_scheduling_config DROP CONSTRAINT ' || quote_ident(cons_name);
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_fsc_factory_active
    ON factory_scheduling_config(factory_id)
    WHERE deleted_at IS NULL;

-- Trigger to keep updated_at fresh (mirrors BaseEntity convention)
CREATE OR REPLACE FUNCTION update_factory_scheduling_config_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trigger_fsc_updated_at ON factory_scheduling_config;
CREATE TRIGGER trigger_fsc_updated_at
BEFORE UPDATE ON factory_scheduling_config
FOR EACH ROW EXECUTE FUNCTION update_factory_scheduling_config_updated_at();
