CREATE TABLE IF NOT EXISTS product_process_workflows (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(64) NOT NULL,
    product_type_id VARCHAR(64) NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(16) NOT NULL,
    definition_version INTEGER NOT NULL,
    nodes_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    edges_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    viewport_json JSONB NOT NULL DEFAULT '{"x":0,"y":0,"zoom":1}'::jsonb,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,
    CONSTRAINT ck_product_process_workflow_status
        CHECK (status IN ('DRAFT', 'PUBLISHED')),
    CONSTRAINT uk_product_process_workflow_version
        UNIQUE (factory_id, product_type_id, status, definition_version)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_product_process_workflow_active_draft
    ON product_process_workflows(factory_id, product_type_id)
    WHERE status = 'DRAFT' AND deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_product_process_workflow_lookup
    ON product_process_workflows(factory_id, product_type_id, status, definition_version DESC)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE product_process_workflows IS
    '产品工序 Workflow 图定义；阶段一与 product_work_processes 运行时链隔离';
