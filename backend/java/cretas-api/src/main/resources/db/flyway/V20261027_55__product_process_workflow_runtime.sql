ALTER TABLE product_process_workflows
  ADD CONSTRAINT uk_ppw_id_factory_product
    UNIQUE (id, factory_id, product_type_id),
  ADD CONSTRAINT uk_ppw_id_factory_product_version
    UNIQUE (id, factory_id, product_type_id, definition_version);

ALTER TABLE production_batches
  ADD CONSTRAINT uk_production_batch_runtime_owner
    UNIQUE (id, factory_id, product_type_id);

CREATE TABLE product_process_workflow_activations (
  id BIGSERIAL PRIMARY KEY,
  factory_id VARCHAR(64) NOT NULL,
  product_type_id VARCHAR(64) NOT NULL,
  active_workflow_id BIGINT NOT NULL,
  active_definition_version INTEGER NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  activated_by BIGINT,
  activated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  lock_version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at TIMESTAMP,
  CONSTRAINT uk_ppwa_factory_product
    UNIQUE (factory_id, product_type_id),
  CONSTRAINT fk_ppwa_active_workflow_owner
    FOREIGN KEY (active_workflow_id, factory_id, product_type_id, active_definition_version)
    REFERENCES product_process_workflows(id, factory_id, product_type_id, definition_version)
);

CREATE TABLE production_workflow_instances (
  id BIGSERIAL PRIMARY KEY,
  factory_id VARCHAR(64) NOT NULL,
  production_batch_id BIGINT NOT NULL,
  product_type_id VARCHAR(64) NOT NULL,
  workflow_id BIGINT NOT NULL,
  definition_version INTEGER NOT NULL,
  nodes_json JSONB NOT NULL,
  edges_json JSONB NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
    CHECK (status IN ('ACTIVE','COMPLETED','CANCELLED')),
  compiled_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at TIMESTAMP,
  CONSTRAINT uk_pwi_factory_batch
    UNIQUE (factory_id, production_batch_id),
  CONSTRAINT uk_pwi_id_factory
    UNIQUE (id, factory_id),
  CONSTRAINT fk_pwi_batch_owner
    FOREIGN KEY (production_batch_id, factory_id, product_type_id)
    REFERENCES production_batches(id, factory_id, product_type_id),
  CONSTRAINT fk_pwi_workflow_owner
    FOREIGN KEY (workflow_id, factory_id, product_type_id)
    REFERENCES product_process_workflows(id, factory_id, product_type_id)
);

ALTER TABLE work_process_tasks
  ALTER COLUMN product_work_process_id DROP NOT NULL,
  ADD COLUMN workflow_instance_id BIGINT,
  ADD COLUMN workflow_node_id VARCHAR(128);

ALTER TABLE work_process_tasks
  ADD CONSTRAINT uk_wpt_id_factory_instance
    UNIQUE (id, factory_id, workflow_instance_id),
  ADD CONSTRAINT fk_wpt_workflow_instance_owner
    FOREIGN KEY (workflow_instance_id, factory_id)
    REFERENCES production_workflow_instances(id, factory_id);

CREATE UNIQUE INDEX uk_workflow_task_node
  ON work_process_tasks(workflow_instance_id, workflow_node_id)
  WHERE workflow_instance_id IS NOT NULL AND deleted_at IS NULL;

CREATE TABLE workflow_task_ports (
  id BIGSERIAL PRIMARY KEY,
  factory_id VARCHAR(64) NOT NULL,
  workflow_instance_id BIGINT NOT NULL,
  task_id BIGINT NOT NULL,
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
  CONSTRAINT uk_wtp_task_port
    UNIQUE (task_id, workflow_port_id),
  CONSTRAINT fk_wtp_workflow_instance_owner
    FOREIGN KEY (workflow_instance_id, factory_id)
    REFERENCES production_workflow_instances(id, factory_id),
  CONSTRAINT fk_wtp_task_owner
    FOREIGN KEY (task_id, factory_id, workflow_instance_id)
    REFERENCES work_process_tasks(id, factory_id, workflow_instance_id)
);
