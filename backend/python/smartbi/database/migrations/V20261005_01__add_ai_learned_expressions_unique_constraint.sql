-- Migration: add UNIQUE constraint on (expression_hash, factory_id) for ai_learned_expressions
--
-- Bug 5: ExpressionLearner INSERT...ON CONFLICT (expression_hash, factory_id) DO NOTHING
-- fails with asyncpg.exceptions.InvalidColumnReferenceError because no UNIQUE constraint
-- exists on those columns. The ON CONFLICT clause requires a UNIQUE index or constraint.
--
-- Steps:
--   1. Delete duplicate rows (keep the oldest row per (expression_hash, factory_id) pair)
--      to avoid constraint violation when adding the UNIQUE index.
--   2. Add the UNIQUE constraint (implemented as a UNIQUE INDEX for efficiency).
--
-- After this migration, ExpressionLearner inserts will resolve correctly via
-- ON CONFLICT (expression_hash, factory_id) DO NOTHING.

BEGIN;

-- Step 1: Remove duplicates, keeping earliest created_at per (expression_hash, factory_id).
DELETE FROM ai_learned_expressions
WHERE id NOT IN (
    SELECT DISTINCT ON (expression_hash, factory_id) id
    FROM ai_learned_expressions
    ORDER BY expression_hash, factory_id, created_at ASC
);

-- Step 2: Create the UNIQUE index that ON CONFLICT requires.
CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_learned_expressions_hash_factory
    ON ai_learned_expressions (expression_hash, factory_id);

COMMIT;
