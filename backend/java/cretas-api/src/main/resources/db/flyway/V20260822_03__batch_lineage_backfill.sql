-- =====================================================================
-- Batch Lineage Backfill — Historical `batch_relations` → `batch_lineage_edges`
-- Phase 1 Sprint 1 Day 8
-- Spec: docs/superpowers/specs/2026-05-20-food-industry-indicator-center-design.md
-- Day 8 entry (line 387): "V20260601_01 backfill migration, run against test DB,
--                          verify batch_relations → lineage_edges"
--
-- NOTE on slot: spec referenced V20260601_01 stale. V20260821_04 already taken
--               by customer_quality_standards; V20260822_01/02 taken by
--               drop_tax_direct/indicator_computation_seed. Next free slot is
--               V20260822_03 (per memory rule [[flyway-collision-marathon-2026-05-20]]
--               — always grep existing slots before claiming a new V*).
--
-- =====================================================================
-- WHY THIS MIGRATION
-- ---------------------------------------------------------------------
-- `batch_relations` (entity BatchRelation, since 2026-01-02) was Cretas's
-- original 1:1 production↔material lineage. The new `batch_lineage_edges`
-- (Day 1, V20260821_02) is a multi-hop polymorphic graph with closure
-- support for full RAW → PRODUCTION → FINISHED → SHIPMENT chains.
--
-- Day 7 (LineageRecordingService) writes NEW lineage events going forward.
-- Day 8 backfills HISTORICAL `batch_relations` rows so:
--   • full-chain traceback queries see historical evidence
--   • closure trigger (fn_maintain_lineage_closure) populates batch_lineage_closure
--   • F006 audit reports can answer "what raw materials produced batch X?"
--     for batches created before Day 7 was deployed.
--
-- =====================================================================
-- COLUMN MAPPING (batch_relations → batch_lineage_edges)
-- ---------------------------------------------------------------------
--   batch_relations.factory_id            → batch_lineage_edges.factory_id
--   batch_relations.material_batch_id     → source_id      (VARCHAR UUID)
--   ('MATERIAL_BATCH' literal)            → source_type
--   batch_relations.production_batch_id   → target_id      (Long → VARCHAR cast)
--   ('PRODUCTION_BATCH' literal)          → target_type
--   batch_relations.relation_type:
--       'INPUT'  → edge_type='RAW_TO_PRODUCTION'  (default)
--       'OUTPUT' → edge_type='RAW_TO_PRODUCTION'  (semantic equivalent: still raw→prod)
--       'REWORK' → edge_type='REWORK'
--       'BLEND'  → edge_type='BLEND'
--   batch_relations.quantity_used         → quantity_used  (NUMERIC scale 3 → 4, exact)
--   batch_relations.unit                  → unit
--   batch_relations.used_at  (fallback created_at) → event_time
--   batch_relations.operator_id           → operator_id
--   batch_relations.remarks               → meta jsonb {"backfill_remarks":..., "source":"V20260822_03"}
--   ('V20260822_03' literal)              → meta.source (forensic provenance)
--
-- ID strategy: gen_random_uuid()::VARCHAR — new IDs for each backfill row.
-- (We never reuse batch_relations.id because batch_lineage_edges.id is
-- VARCHAR(191) UUID, batch_relations.id is VARCHAR(50) with prefix; collision
-- avoidance + provenance both win.)
--
-- =====================================================================
-- IDEMPOTENCY
-- ---------------------------------------------------------------------
-- Guard via NOT EXISTS subquery on (factory_id, source_id, source_type,
-- target_id, target_type) — these 5 cols uniquely identify an edge per
-- product/material pair. Re-running migration after Day 7 lineage hooks
-- have begun writing edges will NOT create duplicates.
--
-- The closure trigger (fn_maintain_lineage_closure) auto-populates closures.
--
-- =====================================================================
-- SOFT-DELETE
-- ---------------------------------------------------------------------
-- batch_relations is soft-deleted (@Where deleted_at IS NULL). We exclude
-- deleted rows from backfill — historical deletes mean "the relation was a
-- mistake", so we should NOT resurrect them in the forensic lineage graph.
--
-- =====================================================================

DO $$
DECLARE
    v_table_exists  BOOLEAN;
    v_before_edges  INT;
    v_inserted      INT;
    v_after_edges   INT;
    v_after_closure INT;
BEGIN
    -- ---------------------------------------------------------------------
    -- 0. Pre-flight: does batch_relations table exist?
    --    Defensive: in fresh test DBs (e.g. CI H2 with ddl-auto=create-drop
    --    and only JPA entities being scanned), batch_relations may not exist
    --    at migration time. In that case, no-op safely.
    -- ---------------------------------------------------------------------
    SELECT EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_name = 'batch_relations'
    ) INTO v_table_exists;

    IF NOT v_table_exists THEN
        RAISE NOTICE 'V20260822_03: batch_relations table not present — no-op (fresh DB or test env without legacy data)';
        RETURN;
    END IF;

    -- ---------------------------------------------------------------------
    -- 1. Snapshot before-count
    -- ---------------------------------------------------------------------
    SELECT COUNT(*) INTO v_before_edges FROM batch_lineage_edges;
    RAISE NOTICE 'V20260822_03: batch_lineage_edges row count BEFORE backfill = %', v_before_edges;

    -- ---------------------------------------------------------------------
    -- 2. Backfill INSERT (idempotent via NOT EXISTS)
    -- ---------------------------------------------------------------------
    INSERT INTO batch_lineage_edges (
        id, factory_id, edge_type, source_type, source_id,
        target_type, target_id, quantity_used, unit, event_time,
        operator_id, meta, created_at, updated_at
    )
    SELECT
        gen_random_uuid()::VARCHAR,
        br.factory_id,
        CASE br.relation_type
            WHEN 'REWORK' THEN 'REWORK'
            WHEN 'BLEND'  THEN 'BLEND'
            ELSE 'RAW_TO_PRODUCTION'  -- INPUT, OUTPUT, NULL → default raw→production
        END,
        'MATERIAL_BATCH',
        br.material_batch_id,
        'PRODUCTION_BATCH',
        br.production_batch_id::VARCHAR,                       -- Long → String (spec § Critical Type Resolution)
        br.quantity_used,
        br.unit,
        COALESCE(br.used_at, br.created_at, NOW()),            -- best-effort event_time
        br.operator_id,
        jsonb_build_object(
            'source', 'V20260822_03',
            'backfill_from_id', br.id,
            'original_relation_type', br.relation_type,
            'backfill_remarks', br.remarks,
            'stage', br.stage,
            'verified', br.verified
        ),
        COALESCE(br.created_at, NOW()),
        NOW()                                                   -- updated_at = migration time
    FROM batch_relations br
    WHERE br.deleted_at IS NULL                                 -- skip soft-deleted (rule § Soft-delete above)
      AND br.material_batch_id IS NOT NULL                      -- defensive
      AND br.production_batch_id IS NOT NULL                    -- defensive
      AND NOT EXISTS (
          SELECT 1 FROM batch_lineage_edges e
          WHERE e.factory_id   = br.factory_id
            AND e.source_id    = br.material_batch_id
            AND e.source_type  = 'MATERIAL_BATCH'
            AND e.target_id    = br.production_batch_id::VARCHAR
            AND e.target_type  = 'PRODUCTION_BATCH'
      );

    GET DIAGNOSTICS v_inserted = ROW_COUNT;

    -- ---------------------------------------------------------------------
    -- 3. Post-count + closure verification
    --    Closure trigger fired per-row during INSERT, so closure should grow
    --    by AT LEAST v_inserted (could be more if existing edges chain into
    --    backfilled ones via transitive closure).
    -- ---------------------------------------------------------------------
    SELECT COUNT(*) INTO v_after_edges  FROM batch_lineage_edges;
    SELECT COUNT(*) INTO v_after_closure FROM batch_lineage_closure;

    RAISE NOTICE 'V20260822_03: backfill complete. Inserted % new edges (% existing skipped via NOT EXISTS guard)',
        v_inserted, (v_after_edges - v_before_edges - v_inserted);
    RAISE NOTICE 'V20260822_03: batch_lineage_edges row count AFTER = %', v_after_edges;
    RAISE NOTICE 'V20260822_03: batch_lineage_closure row count AFTER = % (auto-populated by trigger)', v_after_closure;
END $$;
