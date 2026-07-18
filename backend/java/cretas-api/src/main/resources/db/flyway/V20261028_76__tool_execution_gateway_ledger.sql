-- Persistent idempotency and payload-free audit ledger for the governed ToolExecutionGateway.
-- Raw parameters, business responses, downstream messages, confirmation bearer tokens, and raw
-- idempotency keys are intentionally absent from both tables.

CREATE TABLE IF NOT EXISTS tool_execution_audit_events (
    id VARCHAR(36) PRIMARY KEY,
    request_fingerprint VARCHAR(64) NOT NULL,
    correlation_fingerprint VARCHAR(64) NOT NULL,
    trace_fingerprint VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(50) NOT NULL,
    business_type VARCHAR(32) NOT NULL,
    principal_type VARCHAR(16) NOT NULL,
    principal_id VARCHAR(100) NOT NULL,
    tool_name VARCHAR(150) NOT NULL,
    descriptor_version VARCHAR(64) NOT NULL,
    source VARCHAR(32) NOT NULL,
    execution_mode VARCHAR(16) NOT NULL,
    command_digest VARCHAR(64),
    confirmation_fingerprint VARCHAR(64),
    idempotency_key_hash VARCHAR(64),
    state VARCHAR(16) NOT NULL,
    outcome_status VARCHAR(32),
    result_code VARCHAR(64),
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    CONSTRAINT ck_tea_state CHECK (state IN ('STARTED', 'COMPLETED')),
    CONSTRAINT ck_tea_hash_lengths CHECK (
        length(request_fingerprint) = 64
        AND length(correlation_fingerprint) = 64
        AND length(trace_fingerprint) = 64
        AND (command_digest IS NULL OR length(command_digest) = 64)
        AND (confirmation_fingerprint IS NULL OR length(confirmation_fingerprint) = 64)
        AND (idempotency_key_hash IS NULL OR length(idempotency_key_hash) = 64)
    )
);

CREATE TABLE IF NOT EXISTS tool_execution_idempotency (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(50) NOT NULL,
    business_type VARCHAR(32) NOT NULL,
    principal_type VARCHAR(16) NOT NULL,
    principal_id VARCHAR(100) NOT NULL,
    tool_name VARCHAR(150) NOT NULL,
    descriptor_version VARCHAR(64) NOT NULL,
    idempotency_key_hash VARCHAR(64) NOT NULL,
    command_digest VARCHAR(64) NOT NULL,
    confirmation_fingerprint VARCHAR(64) NOT NULL,
    state VARCHAR(20) NOT NULL,
    outcome_status VARCHAR(32),
    original_audit_event_id VARCHAR(36) NOT NULL,
    result_code VARCHAR(64),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    CONSTRAINT uk_tei_replay_locator UNIQUE (
        tenant_id, principal_type, principal_id, tool_name,
        descriptor_version, idempotency_key_hash
    ),
    CONSTRAINT ck_tei_state CHECK (state IN ('IN_PROGRESS', 'SUCCEEDED', 'FAILED', 'IN_DOUBT')),
    CONSTRAINT ck_tei_hash_lengths CHECK (
        length(idempotency_key_hash) = 64
        AND length(command_digest) = 64
        AND length(confirmation_fingerprint) = 64
    ),
    CONSTRAINT fk_tei_original_audit FOREIGN KEY (original_audit_event_id)
        REFERENCES tool_execution_audit_events(id)
);

CREATE INDEX IF NOT EXISTS idx_tea_tenant_started
    ON tool_execution_audit_events (tenant_id, started_at);
CREATE INDEX IF NOT EXISTS idx_tea_trace_fingerprint
    ON tool_execution_audit_events (trace_fingerprint);
CREATE INDEX IF NOT EXISTS idx_tea_tool_status
    ON tool_execution_audit_events (tool_name, outcome_status);
CREATE INDEX IF NOT EXISTS idx_tei_state_updated
    ON tool_execution_idempotency (state, updated_at);
CREATE INDEX IF NOT EXISTS idx_tei_command_digest
    ON tool_execution_idempotency (command_digest);
