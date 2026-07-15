-- Unified SKU / Workflow / production-reporting contract.

ALTER TABLE workflow_task_ports
  ADD COLUMN net_weight_grams_snapshot NUMERIC(20,6);

ALTER TABLE process_sheet_rows
  ADD COLUMN submission_status VARCHAR(16) NOT NULL DEFAULT 'LEGACY',
  ADD CONSTRAINT ck_process_sheet_rows_submission_status
    CHECK (submission_status IN ('LEGACY', 'DRAFT', 'SUBMITTED'));

ALTER TABLE production_plans
  ADD COLUMN selected_bom_recipe_id VARCHAR(191),
  ADD COLUMN selected_bom_version INTEGER,
  ADD COLUMN planned_net_weight_grams NUMERIC(20,6);

-- Backfill only unambiguous product-owned snapshots.  Existing active Workflow
-- instances keep the SKU value that was authoritative at migration time;
-- completed/cancelled history is deliberately not rewritten.
UPDATE production_plans pp
SET planned_net_weight_grams = pt.grams_per_unit
FROM product_types pt
WHERE pp.factory_id = pt.factory_id
  AND pp.product_type_id = pt.id
  AND pp.planned_net_weight_grams IS NULL
  AND pt.grams_per_unit IS NOT NULL
  AND pt.grams_per_unit > 0;

UPDATE workflow_task_ports wtp
SET net_weight_grams_snapshot = pt.grams_per_unit
FROM product_types pt, production_workflow_instances pwi
WHERE wtp.workflow_instance_id = pwi.id
  AND wtp.factory_id = pwi.factory_id
  AND pwi.status = 'ACTIVE'
  AND wtp.direction = 'OUTPUT'
  AND wtp.material_kind = 'FINISHED_GOOD'
  AND wtp.factory_id = pt.factory_id
  AND wtp.sku_id = pt.id
  AND wtp.net_weight_grams_snapshot IS NULL
  AND pt.grams_per_unit IS NOT NULL
  AND pt.grams_per_unit > 0;

CREATE TABLE sku_unit_migration_issues (
  id BIGSERIAL PRIMARY KEY,
  factory_id VARCHAR(64) NOT NULL,
  entity_type VARCHAR(32) NOT NULL,
  entity_id VARCHAR(191) NOT NULL,
  sku_id VARCHAR(191) NOT NULL,
  issue_code VARCHAR(64) NOT NULL,
  detail VARCHAR(500) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_sku_unit_migration_issue
    UNIQUE (factory_id, entity_type, entity_id, issue_code)
);

INSERT INTO sku_unit_migration_issues
  (factory_id, entity_type, entity_id, sku_id, issue_code, detail)
SELECT wtp.factory_id, 'WORKFLOW_TASK_PORT', wtp.id::text, wtp.sku_id,
       'NET_WEIGHT_SNAPSHOT_MISSING',
       'Active finished-goods count port has no unambiguous SKU net weight; formal reporting remains fail-closed.'
FROM workflow_task_ports wtp
JOIN production_workflow_instances pwi
  ON pwi.id = wtp.workflow_instance_id AND pwi.factory_id = wtp.factory_id
WHERE pwi.status = 'ACTIVE'
  AND wtp.direction = 'OUTPUT'
  AND wtp.material_kind = 'FINISHED_GOOD'
  AND lower(wtp.unit) NOT IN ('kg', 'g')
  AND wtp.net_weight_grams_snapshot IS NULL
ON CONFLICT DO NOTHING;

INSERT INTO sku_unit_migration_issues
  (factory_id, entity_type, entity_id, sku_id, issue_code, detail)
SELECT pp.factory_id, 'PRODUCTION_PLAN', pp.id, pp.product_type_id,
       'NET_WEIGHT_SNAPSHOT_MISSING',
       'Finished-goods count plan has no unambiguous SKU net weight; formal reporting remains fail-closed.'
FROM production_plans pp
WHERE lower(pp.planned_unit) NOT IN ('kg', 'g')
  AND pp.planned_net_weight_grams IS NULL
ON CONFLICT DO NOTHING;

CREATE TABLE production_input_allocations (
  id BIGSERIAL PRIMARY KEY,
  factory_id VARCHAR(50) NOT NULL,
  production_plan_id VARCHAR(191) NOT NULL,
  process_sheet_row_id BIGINT NOT NULL,
  material_type_id VARCHAR(191) NOT NULL,
  material_batch_id VARCHAR(191) NOT NULL,
  warehouse_id VARCHAR(64) NOT NULL,
  quantity NUMERIC(18,6) NOT NULL CHECK (quantity > 0),
  unit VARCHAR(16) NOT NULL,
  allocation_order INTEGER NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ALLOCATED'
    CHECK (status IN ('ALLOCATED')),
  created_by BIGINT NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at TIMESTAMP,
  CONSTRAINT fk_production_input_allocation_row
    FOREIGN KEY (process_sheet_row_id) REFERENCES process_sheet_rows(id) ON DELETE RESTRICT,
  CONSTRAINT fk_production_input_allocation_batch
    FOREIGN KEY (material_batch_id) REFERENCES material_batches(id) ON DELETE RESTRICT
);

CREATE INDEX idx_pia_row
  ON production_input_allocations(factory_id, process_sheet_row_id);

CREATE INDEX idx_pia_batch
  ON production_input_allocations(factory_id, material_batch_id);

CREATE UNIQUE INDEX uk_production_input_allocation_row_batch
  ON production_input_allocations(process_sheet_row_id, material_batch_id)
  WHERE deleted_at IS NULL;

-- Legacy rows may contain fractional factors.  Enforce the new integer rule for
-- all new/updated rows immediately, then validate historical rows after the
-- explicit migration preview has been reviewed.
ALTER TABLE product_packaging_specs
  ADD CONSTRAINT ck_product_packaging_factor_integer
    CHECK (conversion_factor = trunc(conversion_factor)) NOT VALID;
