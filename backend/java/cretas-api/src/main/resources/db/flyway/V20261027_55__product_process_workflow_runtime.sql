CREATE TABLE product_process_workflow_activations (
  id BIGSERIAL PRIMARY KEY,
  factory_id VARCHAR(64) NOT NULL,
  product_type_id VARCHAR(64) NOT NULL,
  active_workflow_id BIGINT NOT NULL REFERENCES product_process_workflows(id),
  active_definition_version INTEGER NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  activated_by BIGINT,
  activated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  lock_version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at TIMESTAMP,
  UNIQUE (factory_id, product_type_id)
);

CREATE TABLE production_workflow_instances (
  id BIGSERIAL PRIMARY KEY,
  factory_id VARCHAR(64) NOT NULL,
  production_batch_id BIGINT NOT NULL REFERENCES production_batches(id),
  product_type_id VARCHAR(64) NOT NULL,
  workflow_id BIGINT NOT NULL REFERENCES product_process_workflows(id),
  definition_version INTEGER NOT NULL,
  nodes_json JSONB NOT NULL,
  edges_json JSONB NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
    CHECK (status IN ('ACTIVE','COMPLETED','CANCELLED')),
  compiled_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at TIMESTAMP,
  UNIQUE (factory_id, production_batch_id)
);

ALTER TABLE work_process_tasks
  ALTER COLUMN product_work_process_id DROP NOT NULL,
  ADD COLUMN workflow_instance_id BIGINT REFERENCES production_workflow_instances(id),
  ADD COLUMN workflow_node_id VARCHAR(128);

CREATE UNIQUE INDEX uk_workflow_task_node
  ON work_process_tasks(workflow_instance_id, workflow_node_id)
  WHERE workflow_instance_id IS NOT NULL AND deleted_at IS NULL;

CREATE TABLE workflow_task_ports (
  id BIGSERIAL PRIMARY KEY,
  factory_id VARCHAR(64) NOT NULL,
  workflow_instance_id BIGINT NOT NULL REFERENCES production_workflow_instances(id),
  task_id BIGINT NOT NULL REFERENCES work_process_tasks(id),
  workflow_port_id VARCHAR(128) NOT NULL,
  direction VARCHAR(8) NOT NULL CHECK (direction IN ('INPUT','OUTPUT')),
  ordinal INTEGER NOT NULL,
  material_node_id VARCHAR(128) NOT NULL,
  material_kind VARCHAR(32) NOT NULL,
  sku_id VARCHAR(128) NOT NULL,
  unit VARCHAR(32) NOT NULL,
  required BOOLEAN NOT NULL DEFAULT TRUE,
  conversion_mode VARCHAR(32),
  conversion_expression VARCHAR(500),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at TIMESTAMP,
  UNIQUE (task_id, workflow_port_id)
);
