-- Task 2: production_interim_settlement table + marker column on material_consumptions
-- Idempotency foundation for BY_STOCK 小结 (interim-settle) flow.

CREATE TABLE IF NOT EXISTS production_interim_settlement (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    factory_id VARCHAR(50) NOT NULL,
    production_plan_id VARCHAR(50) NOT NULL,
    session_seq INT NOT NULL,
    posted_at TIMESTAMP NOT NULL DEFAULT NOW(),
    posted_by BIGINT,
    summary JSONB,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    deleted_at TIMESTAMP NULL,
    CONSTRAINT uk_interim_plan_seq UNIQUE (factory_id, production_plan_id, session_seq)
);

CREATE INDEX IF NOT EXISTS idx_interim_plan
    ON production_interim_settlement(factory_id, production_plan_id)
    WHERE deleted_at IS NULL;

DO $$
BEGIN
    IF to_regclass('public.material_consumptions') IS NULL THEN
        RAISE NOTICE 'V20261027_20 skipped: material_consumptions not present before Hibernate DDL';
        RETURN;
    END IF;

    ALTER TABLE material_consumptions
        ADD COLUMN IF NOT EXISTS interim_settled_at TIMESTAMP NULL;
END $$;
