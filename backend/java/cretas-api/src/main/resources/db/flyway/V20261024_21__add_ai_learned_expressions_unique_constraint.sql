-- V20261024_21__add_ai_learned_expressions_unique_constraint.sql
--
-- Fix Bug 5: Python expression_learner.py uses
--   ON CONFLICT (expression_hash, factory_id) DO NOTHING
-- but the table has no unique constraint on these columns, causing
--   InvalidColumnReferenceError (~120 errors/day, every 5 minutes).
--
-- Step 1: Remove duplicate (expression_hash, factory_id) rows.
--         Keeps the row with the earliest created_at (ctid as tiebreak).
--         Safe on PostgreSQL; no-op if no duplicates exist.
--
-- Step 2: Create unique index on (expression_hash, factory_id).
--         IF NOT EXISTS makes the whole migration idempotent.
--
-- NULL factory_id note: standard unique indexes treat NULL as distinct
-- (NULL <> NULL), so two rows with the same expression_hash but
-- factory_id IS NULL are NOT considered duplicates by the index. This is
-- fine — the ON CONFLICT DO NOTHING only fires when both columns match
-- exactly, and a null factory_id row is a "global" expression.

-- -------------------------------------------------------------------------
-- Step 1: Dedup — delete all but the earliest row per (hash, factory_id)
-- -------------------------------------------------------------------------
DELETE FROM ai_learned_expressions
WHERE id IN (
    SELECT id
    FROM (
        SELECT
            id,
            ROW_NUMBER() OVER (
                PARTITION BY expression_hash, factory_id
                ORDER BY created_at ASC NULLS LAST, ctid ASC
            ) AS rn
        FROM ai_learned_expressions
    ) ranked
    WHERE rn > 1
);

-- -------------------------------------------------------------------------
-- Step 2: Create unique index
-- -------------------------------------------------------------------------
CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_learned_expressions_hash_factory
    ON ai_learned_expressions (expression_hash, factory_id);
