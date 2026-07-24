-- Keep cancellation audit separate from free-form notes so list/detail screens
-- can show the exact reason, operator and time without parsing display text.
ALTER TABLE production_plans
    ADD COLUMN IF NOT EXISTS cancel_reason TEXT,
    ADD COLUMN IF NOT EXISTS cancelled_by BIGINT,
    ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMP;
