-- Make the unit of ProductionPlan.planned_quantity explicit.
-- planned_quantity is the planned finished-product output, not raw-material input.
ALTER TABLE production_plans
    ADD COLUMN IF NOT EXISTS planned_unit VARCHAR(32);

UPDATE production_plans p
SET planned_unit = COALESCE(NULLIF(btrim(pt.unit), ''), 'kg')
FROM product_types pt
WHERE p.product_type_id = pt.id
  AND (p.planned_unit IS NULL OR btrim(p.planned_unit) = '');

UPDATE production_plans
SET planned_unit = 'kg'
WHERE planned_unit IS NULL OR btrim(planned_unit) = '';

ALTER TABLE production_plans
    ALTER COLUMN planned_unit SET DEFAULT 'kg',
    ALTER COLUMN planned_unit SET NOT NULL;
