CREATE TABLE IF NOT EXISTS oa_action_idempotency_ledger (
    id                  VARCHAR(36) PRIMARY KEY,
    factory_id          VARCHAR(50) NOT NULL,
    instance_id         VARCHAR(36) NOT NULL,
    idempotency_key     VARCHAR(128) NOT NULL,
    expected_node_id    VARCHAR(50) NOT NULL,
    action              VARCHAR(32) NOT NULL,
    operator_id         BIGINT NOT NULL,
    operator_role       VARCHAR(50),
    request_fingerprint VARCHAR(64) NOT NULL,
    completion_state    VARCHAR(20) NOT NULL,
    result_json         JSONB,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at          TIMESTAMP,
    CONSTRAINT uk_oa_action_idempotency_scope
        UNIQUE (factory_id, instance_id, idempotency_key),
    CONSTRAINT ck_oa_action_idempotency_state
        CHECK (completion_state IN ('IN_PROGRESS', 'COMPLETED'))
);

CREATE INDEX IF NOT EXISTS idx_oa_action_idempotency_instance
    ON oa_action_idempotency_ledger (factory_id, instance_id, created_at DESC)
    WHERE deleted_at IS NULL;
