-- V20260929_01__revert_chart_insight_cross_factory.sql
-- Revert U1.8 (V20260928_01): the cross-tenant uk_ait_sig(signature_hash) was dead-on-arrival
-- because V20260927_01 keeps FORCE ROW LEVEL SECURITY with policy
--   tenant_isolation: factory_id = current_setting('app.factory_id', true)
-- so a template written by tenant A is RLS-invisible to tenant B's lookup, and B's
-- INSERT ... ON CONFLICT (signature_hash) collides with an RLS-invisible row.
-- Confirmed dead-on-arrival on prod (smartbi_prod_db).
--
-- v4 architecture moves all template work OFFLINE (M4, deferred); the online serve path
-- no longer reads or writes ai_insight_templates. Restore the factory-scoped UNIQUE so the
-- unique key is consistent with the RLS policy (and a future offline curator can decide
-- cross-tenant sharing deliberately, with the RLS policy fixed at that time).
--
-- Precondition: online writes to this table were removed in C1/C3, so the table is ~empty.
-- Step 1 dedups defensively (keep highest proposal_count per (signature_hash, factory_id))
-- in case any U1.8-era rows remain, so the compound UNIQUE can be re-created without collision.
--
-- Rollback:
--   ALTER TABLE ai_insight_templates DROP CONSTRAINT IF EXISTS uk_ait_sig_factory;
--   ALTER TABLE ai_insight_templates ADD CONSTRAINT uk_ait_sig UNIQUE (signature_hash);

BEGIN;

-- Step 1: defensive dedup (keep best row per (signature_hash, factory_id))
DELETE FROM ai_insight_templates a
USING (
    SELECT id FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY signature_hash, factory_id
                   ORDER BY proposal_count DESC, updated_at DESC, id
               ) AS rn
        FROM ai_insight_templates
    ) ranked
    WHERE rn > 1
) dups
WHERE a.id = dups.id;

-- Step 2: drop the U1.8 cross-tenant single-column UNIQUE (if present)
ALTER TABLE ai_insight_templates
    DROP CONSTRAINT IF EXISTS uk_ait_sig;

-- Step 3: restore the factory-scoped compound UNIQUE (idempotent)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'ai_insight_templates'::regclass
          AND conname = 'uk_ait_sig_factory'
    ) THEN
        ALTER TABLE ai_insight_templates
            ADD CONSTRAINT uk_ait_sig_factory UNIQUE (signature_hash, factory_id);
    END IF;
END;
$$;

COMMIT;
