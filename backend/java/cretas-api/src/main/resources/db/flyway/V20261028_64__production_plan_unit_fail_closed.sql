-- New production plans must carry the unit resolved from product authority.
-- Historical rows remain untouched; only the silent database-side kg fallback is removed.
ALTER TABLE production_plans
    ALTER COLUMN planned_unit DROP DEFAULT;
