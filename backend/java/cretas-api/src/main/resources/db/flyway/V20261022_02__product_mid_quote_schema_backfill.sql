-- V20261022_02: product_mid_quotes schema backfill
--
-- Background: prod cretas_prod_db was hand-ALTER'd to fix entity drift (no Flyway record).
-- This migration makes those changes idempotent so test env (cretas_db) catches up,
-- and future deploys do not diverge or fail validate.
--
-- Five changes (all idempotent):
--   1. ADD COLUMN notes TEXT
--   2. ADD COLUMN variance_threshold_pct NUMERIC(8,4)
--   3. trial_batch_id: VARCHAR(191) NOT NULL -> BIGINT nullable
--   4. sample_id + trial_quantity_kg: DROP NOT NULL
--   5. cost_variance_pct: NUMERIC(6,2) -> NUMERIC(8,4)
--
-- Prod state  : all five already applied  -> each DO $$ block is a no-op.
-- Test state  : none applied              -> all five execute.

-- 1. ADD COLUMN notes (idempotent via IF NOT EXISTS)
ALTER TABLE product_mid_quotes
    ADD COLUMN IF NOT EXISTS notes TEXT;

-- 2. ADD COLUMN variance_threshold_pct (idempotent via IF NOT EXISTS)
ALTER TABLE product_mid_quotes
    ADD COLUMN IF NOT EXISTS variance_threshold_pct NUMERIC(8, 4);

-- 3. trial_batch_id: VARCHAR -> BIGINT nullable
--    Prod: already BIGINT nullable      -> data_type check skips ALTER
--    Test: VARCHAR(191) NOT NULL, 0 rows -> USING cast is safe
DO
$$
    DECLARE
        col_type TEXT;
    BEGIN
        SELECT data_type
        INTO col_type
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'product_mid_quotes'
          AND column_name = 'trial_batch_id';

        -- Only alter if still character varying (test env)
        IF col_type = 'character varying' THEN
            -- Drop NOT NULL first (separate step avoids locking conflict)
            ALTER TABLE product_mid_quotes
                ALTER COLUMN trial_batch_id DROP NOT NULL;
            -- Cast VARCHAR -> BIGINT (safe: test table has 0 rows)
            ALTER TABLE product_mid_quotes
                ALTER COLUMN trial_batch_id TYPE BIGINT
                    USING trial_batch_id::BIGINT;
        END IF;
        -- If already bigint (prod): no-op
    END
$$;

-- 4a. sample_id: DROP NOT NULL (idempotent)
--     Variable named v_nullable (not is_nullable) to avoid PL/pgSQL ambiguity
--     with information_schema.columns column of same name.
DO
$$
    DECLARE
        v_nullable TEXT;
    BEGIN
        SELECT is_nullable
        INTO v_nullable
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'product_mid_quotes'
          AND column_name = 'sample_id';

        IF v_nullable = 'NO' THEN
            ALTER TABLE product_mid_quotes
                ALTER COLUMN sample_id DROP NOT NULL;
        END IF;
    END
$$;

-- 4b. trial_quantity_kg: DROP NOT NULL (idempotent)
DO
$$
    DECLARE
        v_nullable TEXT;
    BEGIN
        SELECT is_nullable
        INTO v_nullable
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'product_mid_quotes'
          AND column_name = 'trial_quantity_kg';

        IF v_nullable = 'NO' THEN
            ALTER TABLE product_mid_quotes
                ALTER COLUMN trial_quantity_kg DROP NOT NULL;
        END IF;
    END
$$;

-- 5. cost_variance_pct: precision NUMERIC(6,2) -> NUMERIC(8,4)
--    Prod: already NUMERIC(8,4)   -> numeric_precision=8 check skips ALTER
--    Test: NUMERIC(6,2)           -> ALTER executes
DO
$$
    DECLARE
        col_prec INTEGER;
    BEGIN
        SELECT numeric_precision
        INTO col_prec
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'product_mid_quotes'
          AND column_name = 'cost_variance_pct';

        IF col_prec IS DISTINCT FROM 8 THEN
            ALTER TABLE product_mid_quotes
                ALTER COLUMN cost_variance_pct TYPE NUMERIC(8, 4);
        END IF;
    END
$$;
