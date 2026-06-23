-- SP-D Fix 4: Recipe ACTIVE uniqueness index
-- Ensures only one ACTIVE recipe exists per (factory_id, product_type_id) combination.
-- Partial index applies only to non-deleted ACTIVE recipes, so DRAFT/ARCHIVED records
-- are unaffected and historical records can coexist.

CREATE UNIQUE INDEX IF NOT EXISTS uq_recipe_active
    ON product_recipes(factory_id, product_type_id)
    WHERE status = 'ACTIVE' AND deleted_at IS NULL;
