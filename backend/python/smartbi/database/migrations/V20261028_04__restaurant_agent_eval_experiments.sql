-- Tenant-isolated immutable Eval Sets and reproducible offline experiments.
-- Run traces continue to read smart_bi_agent_run/event as the single truth.

CREATE OR REPLACE FUNCTION smart_bi_agentops_config_is_safe(value JSONB)
RETURNS BOOLEAN LANGUAGE sql IMMUTABLE AS $$
    SELECT jsonb_typeof(value) = 'object'
       AND (SELECT COUNT(*) FROM jsonb_object_keys(value)) = 3
       AND value ?& ARRAY[
           'promptSnapshotDigest','modelSnapshotDigest','toolSnapshotDigest'
       ]
       AND value->>'promptSnapshotDigest' ~ '^[0-9a-f]{64}$'
       AND value->>'modelSnapshotDigest' ~ '^[0-9a-f]{64}$'
       AND value->>'toolSnapshotDigest' ~ '^[0-9a-f]{64}$'
$$;

CREATE OR REPLACE FUNCTION smart_bi_agentops_bounds_are_safe(value JSONB)
RETURNS BOOLEAN LANGUAGE sql IMMUTABLE AS $$
    SELECT jsonb_typeof(value) = 'object'
       AND (SELECT COUNT(*) FROM jsonb_object_keys(value)) = 3
       AND value ?& ARRAY['maxCases','maxConcurrency','perCaseTimeoutMs']
       AND value->>'maxCases' ~ '^\d+$'
       AND value->>'maxConcurrency' ~ '^\d+$'
       AND value->>'perCaseTimeoutMs' ~ '^\d+$'
       AND (value->>'maxCases')::INTEGER BETWEEN 1 AND 100
       AND (value->>'maxConcurrency')::INTEGER BETWEEN 1 AND 4
       AND (value->>'perCaseTimeoutMs')::INTEGER BETWEEN 50 AND 5000
$$;

CREATE OR REPLACE FUNCTION smart_bi_agentops_actuals_are_bounded(value JSONB)
RETURNS BOOLEAN LANGUAGE sql IMMUTABLE AS $$
    SELECT jsonb_typeof(value) = 'object'
       AND (SELECT COUNT(*) FROM jsonb_object_keys(value)) BETWEEN 1 AND 100
$$;

CREATE TABLE IF NOT EXISTS smart_bi_agent_eval_set (
    eval_set_id UUID NOT NULL,
    factory_id VARCHAR(64) NOT NULL,
    name VARCHAR(96) NOT NULL,
    version INTEGER NOT NULL CHECK (version BETWEEN 1 AND 1000000),
    description VARCHAR(500) NOT NULL DEFAULT '',
    cases JSONB NOT NULL CHECK (jsonb_typeof(cases) = 'array'),
    content_digest CHAR(64) NOT NULL CHECK (content_digest ~ '^[0-9a-f]{64}$'),
    request_id UUID NOT NULL,
    request_digest CHAR(64) NOT NULL CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (eval_set_id),
    UNIQUE (eval_set_id, factory_id),
    UNIQUE (eval_set_id, factory_id, name, version),
    UNIQUE (eval_set_id, factory_id, name, version, content_digest),
    UNIQUE (factory_id, created_by, request_id),
    CHECK (jsonb_array_length(cases) BETWEEN 1 AND 100),
    CHECK (octet_length(cases::text) <= 3276800),
    CHECK (cases::text !~* '"(prompt|raw[_-]?prompt|secret|token|password|authorization|cookie|api[_-]?key|credential)"[[:space:]]*:')
);

CREATE TABLE IF NOT EXISTS smart_bi_agent_experiment (
    experiment_id UUID NOT NULL,
    factory_id VARCHAR(64) NOT NULL,
    eval_set_id UUID NOT NULL,
    eval_set_name VARCHAR(96) NOT NULL,
    eval_set_version INTEGER NOT NULL,
    eval_set_digest CHAR(64) NOT NULL CHECK (eval_set_digest ~ '^[0-9a-f]{64}$'),
    evaluator_version VARCHAR(64) NOT NULL,
    evaluator_build VARCHAR(64) NOT NULL CHECK (evaluator_build ~ '^[0-9a-f]{64}$'),
    snapshot_digest CHAR(64) NOT NULL CHECK (snapshot_digest ~ '^[0-9a-f]{64}$'),
    config_snapshot JSONB NOT NULL CHECK (smart_bi_agentops_config_is_safe(config_snapshot)),
    actual_snapshots JSONB NOT NULL CHECK (smart_bi_agentops_actuals_are_bounded(actual_snapshots)),
    runner_bounds JSONB NOT NULL CHECK (smart_bi_agentops_bounds_are_safe(runner_bounds)),
    aggregate JSONB NOT NULL CHECK (jsonb_typeof(aggregate) = 'object'),
    case_results JSONB NOT NULL CHECK (jsonb_typeof(case_results) = 'array'),
    request_id UUID NOT NULL,
    request_digest CHAR(64) NOT NULL CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    operation_kind VARCHAR(8) NOT NULL CHECK (operation_kind IN ('RUN', 'RERUN')),
    source_experiment_id UUID,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (experiment_id),
    UNIQUE (experiment_id, factory_id),
    UNIQUE (factory_id, created_by, request_id),
    CONSTRAINT smart_bi_agent_experiment_eval_fk
        FOREIGN KEY (
            eval_set_id, factory_id, eval_set_name, eval_set_version, eval_set_digest
        ) REFERENCES smart_bi_agent_eval_set (
            eval_set_id, factory_id, name, version, content_digest
        ),
    CONSTRAINT smart_bi_agent_experiment_source_fk
        FOREIGN KEY (source_experiment_id, factory_id)
        REFERENCES smart_bi_agent_experiment (experiment_id, factory_id),
    CHECK (jsonb_array_length(case_results) BETWEEN 1 AND 100),
    CHECK (
        (operation_kind = 'RUN' AND source_experiment_id IS NULL)
        OR (operation_kind = 'RERUN' AND source_experiment_id IS NOT NULL)
    ),
    CHECK (
        source_experiment_id IS NULL
        OR source_experiment_id <> experiment_id
    ),
    CHECK (octet_length(config_snapshot::text) <= 64000),
    CHECK (octet_length(actual_snapshots::text) <= 4194304),
    CHECK (octet_length(case_results::text) <= 3276800),
    CHECK (actual_snapshots::text !~* '"(prompt|raw[_-]?prompt|raw[_-]?question|raw[_-]?request|review([_-]?text)?|member([_-]?id)?|secret|token|password|authorization|cookie|api[_-]?key|credential)"[[:space:]]*:')
);

CREATE INDEX IF NOT EXISTS idx_agent_eval_set_tenant_created
    ON smart_bi_agent_eval_set (factory_id, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_eval_set_tenant_name_version
    ON smart_bi_agent_eval_set (factory_id, lower(name), version);
CREATE INDEX IF NOT EXISTS idx_agent_experiment_tenant_created
    ON smart_bi_agent_experiment (factory_id, created_at DESC);

CREATE OR REPLACE FUNCTION smart_bi_agentops_append_only()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'AgentOps version and experiment records are immutable';
END;
$$;

DROP TRIGGER IF EXISTS trg_agent_eval_set_immutable ON smart_bi_agent_eval_set;
CREATE TRIGGER trg_agent_eval_set_immutable
BEFORE UPDATE OR DELETE ON smart_bi_agent_eval_set
FOR EACH ROW EXECUTE FUNCTION smart_bi_agentops_append_only();

DROP TRIGGER IF EXISTS trg_agent_experiment_immutable ON smart_bi_agent_experiment;
CREATE TRIGGER trg_agent_experiment_immutable
BEFORE UPDATE OR DELETE ON smart_bi_agent_experiment
FOR EACH ROW EXECUTE FUNCTION smart_bi_agentops_append_only();

ALTER TABLE smart_bi_agent_eval_set ENABLE ROW LEVEL SECURITY;
ALTER TABLE smart_bi_agent_eval_set FORCE ROW LEVEL SECURITY;
ALTER TABLE smart_bi_agent_experiment ENABLE ROW LEVEL SECURITY;
ALTER TABLE smart_bi_agent_experiment FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS agent_eval_set_tenant_select ON smart_bi_agent_eval_set;
CREATE POLICY agent_eval_set_tenant_select ON smart_bi_agent_eval_set
FOR SELECT USING (factory_id = current_setting('app.factory_id', true));
DROP POLICY IF EXISTS agent_eval_set_tenant_insert ON smart_bi_agent_eval_set;
CREATE POLICY agent_eval_set_tenant_insert ON smart_bi_agent_eval_set
FOR INSERT WITH CHECK (factory_id = current_setting('app.factory_id', true));

DROP POLICY IF EXISTS agent_experiment_tenant_select ON smart_bi_agent_experiment;
CREATE POLICY agent_experiment_tenant_select ON smart_bi_agent_experiment
FOR SELECT USING (factory_id = current_setting('app.factory_id', true));
DROP POLICY IF EXISTS agent_experiment_tenant_insert ON smart_bi_agent_experiment;
CREATE POLICY agent_experiment_tenant_insert ON smart_bi_agent_experiment
FOR INSERT WITH CHECK (factory_id = current_setting('app.factory_id', true));

REVOKE ALL ON smart_bi_agent_eval_set FROM PUBLIC;
REVOKE ALL ON smart_bi_agent_experiment FROM PUBLIC;
REVOKE ALL ON smart_bi_agent_eval_set FROM smartbi_user;
REVOKE ALL ON smart_bi_agent_experiment FROM smartbi_user;
GRANT SELECT, INSERT ON smart_bi_agent_eval_set TO smartbi_user;
GRANT SELECT, INSERT ON smart_bi_agent_experiment TO smartbi_user;

COMMENT ON TABLE smart_bi_agent_eval_set IS
    'Immutable tenant-scoped versioned offline Agent Eval Sets.';
COMMENT ON TABLE smart_bi_agent_experiment IS
    'Immutable reproducible offline experiment results and config snapshots.';
