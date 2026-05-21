-- V20260510_02__normalize_bom_unit.sql
--
-- A4 (PR #309) Steve-approved long-term decision: B = eager batch migration.
-- Normalize bom_items.unit to D3 standard values (g / kg / mL / L / pcs) so
-- ProductionWorkflowOrchestrator.buildTransferRequest()'s unit conversion
-- (BomItem.unit='g' + RawMaterialType.unit='kg' → quantity ÷ 1000) fires
-- predictably instead of falling back to 1:1 legacy path.
--
-- Per A4 decision PR #309 §A4 + spec docs/superpowers/specs/2026-05-10-customer-meeting-design-decisions-impl-plan.md §4.8.
-- Audit doc: docs/qa-audits/2026-05-10-a4-bom-unit-pre-migration-audit.md.
--
-- Pre-migration distribution (audited 2026-05-10 via ssh root@47.100.235.168
-- → sudo -u postgres psql):
--
--   cretas_prod_db (smartbi_prod_db irrelevant — bom_items lives in main DB):
--     <kg>     len=2  count=19   → keep 'kg'
--     <个>     len=1  count=3    → map 'pcs'
--     <套>     len=1  count=3    → map 'pcs'  (AMBIGUOUS: 套=set 可能是包装单位)
--     <g>      len=1  count=1    → keep 'g'
--     <g   >   len=4  count=1    → trim → 'g'
--   Total prod: 27 rows (26 active + 1 soft-deleted)
--
--   cretas_db (test):
--     <kg>     len=2  count=10   → keep 'kg'
--     <套>     len=1  count=3    → map 'pcs'  (AMBIGUOUS)
--   Total test: 13 rows
--
-- Ambiguous rows: 6 / 40 (15%) — '套' (set) flagged but mapped to 'pcs' per
-- D3 standard. Steve sign-off: see audit doc §3. If '套' actually means
-- 包装单位 (batch packaging) the rollback path (unit_pre_migration backup
-- column) makes per-factory re-correction trivial.
--
-- ROLLBACK PROCEDURE (do NOT run in normal flow):
--   BEGIN;
--   UPDATE bom_items SET unit = unit_pre_migration WHERE unit_pre_migration IS NOT NULL;
--   ALTER TABLE bom_items DROP COLUMN unit_pre_migration;
--   COMMIT;

BEGIN;

-- ---------------------------------------------------------------------------
-- Step 0: Pre-flight idempotency guard.
-- ---------------------------------------------------------------------------
-- If unit_pre_migration column already exists, the migration has already run
-- (Flyway should prevent re-application, but Flyway out-of-order or manual
-- replay would silently double-apply otherwise). Abort with explicit error.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_name = 'bom_items' AND column_name = 'unit_pre_migration'
  ) THEN
    RAISE EXCEPTION 'V20260510_02 already applied: column unit_pre_migration exists. '
                    'If you intend to re-run, restore unit then drop the backup column first.';
  END IF;
END $$;

-- ---------------------------------------------------------------------------
-- Step 1: Add backup column.
-- ---------------------------------------------------------------------------
-- Permanent rollback safety net per A4 marching order. The column is retained
-- post-migration (NOT dropped) so per-row reverts stay possible if customer
-- triage flags '套' or other values as miscategorized.
ALTER TABLE bom_items ADD COLUMN IF NOT EXISTS unit_pre_migration VARCHAR(50);

UPDATE bom_items SET unit_pre_migration = unit;

-- ---------------------------------------------------------------------------
-- Step 2: Trim leading/trailing whitespace.
-- ---------------------------------------------------------------------------
-- Audit caught '<g   >' (4-char with 3 trailing spaces) in prod which would
-- bypass equality match in BomServiceImpl/orchestrator code. Apply first so
-- subsequent CASE mappings see canonical text.
UPDATE bom_items
SET unit = BTRIM(unit)
WHERE unit IS NOT NULL AND unit <> BTRIM(unit);

-- ---------------------------------------------------------------------------
-- Step 3: Apply unit standardization mappings.
-- ---------------------------------------------------------------------------
-- Mapping rules (D3 standard: g / kg / mL / L / pcs):
--   '克' / '克(g)' / '克(G)' / 'grams' / 'gram'            → 'g'
--   '千克' / '公斤' / 'kilograms' / 'kilogram' / 'KG' / 'Kg' → 'kg'
--   '毫升' / 'ml' / 'ML' / 'milliliters' / 'milliliter'    → 'mL'
--   '升' / 'liters' / 'liter' / 'l' / 'L'                  → 'L'
--   '件' / '个' / '只' / '套' / 'pieces' / 'piece'         → 'pcs'
--
-- '套' (set) is technically ambiguous (could be 包装单位 = batch packaging)
-- but Steve sign-off PR #309 A4=B accepted default mapping → 'pcs'.
-- Backup column preserves original for per-row rollback if customer triages.
UPDATE bom_items
SET unit = CASE
    WHEN unit IN ('克', '克(g)', '克(G)', 'grams', 'gram', 'G')                   THEN 'g'
    WHEN unit IN ('千克', '公斤', 'kilograms', 'kilogram', 'KG', 'Kg')             THEN 'kg'
    WHEN unit IN ('毫升', 'ml', 'ML', 'milliliters', 'milliliter')                 THEN 'mL'
    WHEN unit IN ('升', 'liters', 'liter', 'l')                                    THEN 'L'
    WHEN unit IN ('件', '个', '只', '套', 'pieces', 'piece')                       THEN 'pcs'
    ELSE unit
  END
WHERE unit IS NOT NULL
  AND unit NOT IN ('g', 'kg', 'mL', 'L', 'pcs');  -- skip already-canonical rows

-- ---------------------------------------------------------------------------
-- Step 4: Post-migration audit log.
-- ---------------------------------------------------------------------------
-- Emit final distribution + ambiguous row count via RAISE NOTICE so deploy
-- logs (apply-flyway / journalctl cretas-backend) capture the outcome.
DO $$
DECLARE
  v_total              INT;
  v_active             INT;
  v_canonical          INT;
  v_non_canonical_rows TEXT;
  v_backup_rows        INT;
BEGIN
  SELECT COUNT(*), COUNT(*) FILTER (WHERE deleted_at IS NULL)
    INTO v_total, v_active
    FROM bom_items;

  SELECT COUNT(*)
    INTO v_canonical
    FROM bom_items
    WHERE unit IN ('g', 'kg', 'mL', 'L', 'pcs');

  SELECT string_agg(format('%s=%s', unit, c), ', ' ORDER BY unit)
    INTO v_non_canonical_rows
    FROM (
      SELECT unit, COUNT(*) AS c
        FROM bom_items
        WHERE unit IS NOT NULL
          AND unit NOT IN ('g', 'kg', 'mL', 'L', 'pcs')
        GROUP BY unit
    ) q;

  SELECT COUNT(*) INTO v_backup_rows
    FROM bom_items WHERE unit_pre_migration IS NOT NULL;

  RAISE NOTICE 'V20260510_02 post-migration: total=%, active=%, canonical=%, backup_rows=%',
    v_total, v_active, v_canonical, v_backup_rows;

  IF v_non_canonical_rows IS NOT NULL THEN
    RAISE NOTICE 'V20260510_02 RESIDUAL non-canonical units (need triage): %', v_non_canonical_rows;
  ELSE
    RAISE NOTICE 'V20260510_02: 100%% rows canonicalized (g/kg/mL/L/pcs)';
  END IF;

  -- Sanity: backup column must hold every active row (idempotency anchor).
  IF v_backup_rows < v_active THEN
    RAISE EXCEPTION 'V20260510_02 sanity failure: backup column has % rows, expected ≥ % active rows',
      v_backup_rows, v_active;
  END IF;
END $$;

COMMIT;
