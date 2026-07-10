-- Make the unit of ProductionPlan.planned_quantity explicit.
-- Existing plans historically used kg as the planning/input unit.
ALTER TABLE production_plans
    ADD COLUMN IF NOT EXISTS planned_unit VARCHAR(32);

UPDATE production_plans
SET planned_unit = 'kg'
WHERE planned_unit IS NULL OR btrim(planned_unit) = '';

ALTER TABLE production_plans
    ALTER COLUMN planned_unit SET DEFAULT 'kg',
    ALTER COLUMN planned_unit SET NOT NULL;
