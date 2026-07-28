-- Immutable ProductionPlan authority and server-derived multi-output settlement.
-- Existing plans without a selection snapshot are explicitly legacy. Existing
-- WORKFLOW plans are not guessed/backfilled from today's ACTIVE BOM: incomplete
-- historical authority remains visible and runtime fails closed.

ALTER TABLE production_plans
    ADD COLUMN IF NOT EXISTS selected_workflow_revision_id BIGINT,
    ADD COLUMN IF NOT EXISTS selected_workflow_revision_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS workflow_output_units_by_product JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS selected_bom_family_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS selected_bom_recipe_ids_by_product JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS selected_bom_versions_by_product JSONB NOT NULL DEFAULT '{}'::jsonb;

UPDATE production_plans
   SET workflow_selection_mode = 'LEGACY',
       selected_workflow_id = NULL,
       selected_workflow_version = NULL
 WHERE workflow_selection_mode IS NULL;

-- Pre-V30 WORKFLOW rows do not contain enough evidence to identify an exact
-- historical revision/BOM family. Preserve their mode and old selection
-- instead of guessing or silently downgrading; guarded runtime paths fail
-- closed and require the operator to recreate an affected unfinished plan.

ALTER TABLE production_batches
    ADD COLUMN IF NOT EXISTS selected_workflow_revision_id BIGINT,
    ADD COLUMN IF NOT EXISTS selected_workflow_revision_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS selected_bom_family_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS selected_bom_recipe_ids_by_product JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS selected_bom_versions_by_product JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS workflow_output_units_by_product JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS target_finished_good_ids JSONB NOT NULL DEFAULT '[]'::jsonb;

-- A scalar output is meaningful only when all terminal lines use one canonical
-- unit. Mixed-unit Workflow settlements use the output-line ledger exclusively.
ALTER TABLE production_settlements
    ALTER COLUMN actual_finished_quantity DROP NOT NULL;

-- Audit the exact pinned Workflow input identity. Shared/fan-out raw material
-- remains BOM-family-owned; it is not forced onto an arbitrary terminal SKU.
ALTER TABLE production_settlement_consumptions
    ADD COLUMN IF NOT EXISTS product_type_id VARCHAR(191),
    ADD COLUMN IF NOT EXISTS workflow_material_node_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS workflow_input_port_id VARCHAR(128);

CREATE TABLE IF NOT EXISTS production_settlement_output_lines (
    id VARCHAR(191) PRIMARY KEY,
    factory_id VARCHAR(50) NOT NULL,
    settlement_id VARCHAR(191) NOT NULL,
    production_plan_id VARCHAR(191) NOT NULL,
    product_type_id VARCHAR(191) NOT NULL,
    reported_batch_number VARCHAR(64) NOT NULL,
    reported_quantity NUMERIC(18,4) NOT NULL,
    quantity_unit VARCHAR(20) NOT NULL,
    bom_family_id VARCHAR(64) NOT NULL,
    bom_recipe_id VARCHAR(191) NOT NULL,
    bom_recipe_version INTEGER NOT NULL,
    target_terminal_node_id VARCHAR(128) NOT NULL,
    output_role VARCHAR(24) NOT NULL,
    cost_allocation_ratio NUMERIC(7,4),
    byproduct_nrv_unit_price NUMERIC(15,4),
    allocated_cost NUMERIC(18,6),
    unit_cost NUMERIC(18,6),
    received_quantity NUMERIC(18,4),
    finished_goods_batch_id VARCHAR(191),
    receipt_idempotency_key VARCHAR(128),
    received_by BIGINT,
    received_at TIMESTAMP,
    status VARCHAR(24) NOT NULL DEFAULT 'REPORTED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    CONSTRAINT uq_pso_reported_output
        UNIQUE (settlement_id, product_type_id, reported_batch_number, quantity_unit),
    CONSTRAINT ck_pso_reported_quantity CHECK (reported_quantity > 0),
    CONSTRAINT ck_pso_received_quantity
        CHECK (received_quantity IS NULL OR (received_quantity > 0 AND received_quantity <= reported_quantity)),
    CONSTRAINT ck_pso_output_role CHECK (output_role IN ('MAIN', 'CO_PRODUCT', 'BY_PRODUCT')),
    CONSTRAINT ck_pso_status CHECK (status IN ('REPORTED', 'RECEIVED')),
    CONSTRAINT fk_pso_settlement FOREIGN KEY (settlement_id)
        REFERENCES production_settlements(id),
    CONSTRAINT fk_pso_plan FOREIGN KEY (production_plan_id)
        REFERENCES production_plans(id),
    CONSTRAINT fk_pso_recipe FOREIGN KEY (bom_recipe_id)
        REFERENCES bom_recipes(id)
);

CREATE INDEX IF NOT EXISTS idx_pso_factory_settlement
    ON production_settlement_output_lines(factory_id, settlement_id);
CREATE INDEX IF NOT EXISTS idx_pso_factory_plan
    ON production_settlement_output_lines(factory_id, production_plan_id);

CREATE OR REPLACE FUNCTION pin_production_batch_workflow_selection()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
  plan_mode VARCHAR(16);
  plan_workflow_id BIGINT;
  plan_workflow_version INTEGER;
  plan_workflow_revision_id BIGINT;
  plan_workflow_revision_hash VARCHAR(64);
  plan_bom_family_id VARCHAR(64);
  plan_bom_recipe_ids JSONB;
  plan_bom_versions JSONB;
  plan_output_units JSONB;
  plan_target_finished_good_ids JSONB;
  plan_found BOOLEAN := FALSE;
  governed_workflow_exists BOOLEAN := FALSE;
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended(
    NEW.factory_id || E'\\x1f' || NEW.product_type_id,
    0
  ));

  IF NEW.production_plan_id IS NOT NULL THEN
    SELECT workflow_selection_mode,
           selected_workflow_id,
           selected_workflow_version,
           selected_workflow_revision_id,
           selected_workflow_revision_hash,
           selected_bom_family_id,
           selected_bom_recipe_ids_by_product,
           selected_bom_versions_by_product,
           workflow_output_units_by_product,
           target_finished_good_ids
      INTO plan_mode,
           plan_workflow_id,
           plan_workflow_version,
           plan_workflow_revision_id,
           plan_workflow_revision_hash,
           plan_bom_family_id,
           plan_bom_recipe_ids,
           plan_bom_versions,
           plan_output_units,
           plan_target_finished_good_ids
      FROM production_plans
     WHERE id = NEW.production_plan_id
       AND factory_id = NEW.factory_id;
    plan_found := FOUND;
    IF NOT plan_found THEN
      RAISE EXCEPTION
        'production batch plan % does not exist in factory %',
        NEW.production_plan_id, NEW.factory_id
        USING ERRCODE = '23503';
    END IF;
  END IF;

  IF plan_mode = 'WORKFLOW' THEN
    -- Expand-phase compatibility: while the old blue/green slot can still
    -- write during migration startup, copy its legacy Workflow selection
    -- without rejecting the batch. The new service always writes the complete
    -- authority below and fails incomplete plans closed at runtime. A later
    -- contract migration may enforce NOT NULL only after every writer has
    -- crossed this deployment boundary.
    NEW.workflow_selection_mode := 'WORKFLOW';
    NEW.selected_workflow_id := plan_workflow_id;
    NEW.selected_workflow_version := plan_workflow_version;
    NEW.selected_workflow_revision_id := plan_workflow_revision_id;
    NEW.selected_workflow_revision_hash := plan_workflow_revision_hash;
    NEW.selected_bom_family_id := plan_bom_family_id;
    NEW.selected_bom_recipe_ids_by_product := COALESCE(plan_bom_recipe_ids, '{}'::jsonb);
    NEW.selected_bom_versions_by_product := COALESCE(plan_bom_versions, '{}'::jsonb);
    NEW.workflow_output_units_by_product := COALESCE(plan_output_units, '{}'::jsonb);
    NEW.target_finished_good_ids := COALESCE(plan_target_finished_good_ids, '[]'::jsonb);
  ELSIF plan_mode = 'LEGACY' THEN
    NEW.workflow_selection_mode := 'LEGACY';
    NEW.selected_workflow_id := NULL;
    NEW.selected_workflow_version := NULL;
    NEW.selected_workflow_revision_id := NULL;
    NEW.selected_workflow_revision_hash := NULL;
    NEW.selected_bom_family_id := NULL;
    NEW.selected_bom_recipe_ids_by_product := '{}'::jsonb;
    NEW.selected_bom_versions_by_product := '{}'::jsonb;
    NEW.workflow_output_units_by_product := '{}'::jsonb;
    NEW.target_finished_good_ids := '[]'::jsonb;
  ELSIF NEW.production_plan_id IS NOT NULL THEN
    RAISE EXCEPTION
      'production plan % lacks an explicit workflow selection snapshot',
      NEW.production_plan_id
      USING ERRCODE = '23514';
  ELSE
    SELECT EXISTS (
      SELECT 1
        FROM product_process_workflow_activations activation
       WHERE activation.factory_id = NEW.factory_id
         AND activation.product_type_id = NEW.product_type_id
         AND activation.enabled = TRUE
         AND activation.deleted_at IS NULL
    )
      INTO governed_workflow_exists;
    IF governed_workflow_exists THEN
      RAISE EXCEPTION
        'product % in factory % is Workflow-governed; create the batch through a production plan',
        NEW.product_type_id, NEW.factory_id
        USING ERRCODE = '23514';
    END IF;
    NEW.workflow_selection_mode := 'LEGACY';
    NEW.selected_workflow_id := NULL;
    NEW.selected_workflow_version := NULL;
    NEW.selected_workflow_revision_id := NULL;
    NEW.selected_workflow_revision_hash := NULL;
    NEW.selected_bom_family_id := NULL;
    NEW.selected_bom_recipe_ids_by_product := '{}'::jsonb;
    NEW.selected_bom_versions_by_product := '{}'::jsonb;
    NEW.workflow_output_units_by_product := '{}'::jsonb;
    NEW.target_finished_good_ids := '[]'::jsonb;
  END IF;
  RETURN NEW;
END;
$$;

COMMENT ON COLUMN production_plans.selected_bom_recipe_ids_by_product IS
    'Immutable terminal SKU to BOM Output Recipe id map pinned when the plan is created';
COMMENT ON TABLE production_settlement_output_lines IS
    'Server-derived terminal outputs and BOM-owned cost/receipt allocation facts';
