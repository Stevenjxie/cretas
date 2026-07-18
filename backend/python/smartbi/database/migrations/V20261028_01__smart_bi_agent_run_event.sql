-- Bounded restaurant evidence runtime: tenant-isolated run ledger + append-only events.
--
-- Security invariants:
--   * app.factory_id is transaction-local and is the only RLS tenant key.
--   * there is exactly one tenant predicate per operation; no cleanup/prune bypass.
--   * prompt/review/member/secret-shaped JSON keys are rejected recursively.
--   * events are immutable and receive an atomic per-run sequence from the run row.

CREATE TABLE IF NOT EXISTS smart_bi_agent_run (
    run_id UUID PRIMARY KEY,
    factory_id VARCHAR(64) NOT NULL,
    business_type VARCHAR(32) NOT NULL
        CHECK (business_type = 'RESTAURANT'),
    correlation_id VARCHAR(128) NOT NULL,
    route_code VARCHAR(96) NOT NULL
        CHECK (route_code = 'GROSS_MARGIN_DECLINE_ATTRIBUTION'),
    state VARCHAR(32) NOT NULL DEFAULT 'RUNNING'
        CHECK (state IN (
            'RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED',
            'BUDGET_EXCEEDED'
        )),
    sanitized_request JSONB NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(sanitized_request) = 'object')
        CHECK (octet_length(sanitized_request::text) <= 32768)
        CHECK (sanitized_request::text !~* '"(prompt|raw[_-]?prompt|raw[_-]?question|review([_-]?text)?|member([_-]?id)?|secret|token|password|authorization|cookie|api[_-]?key|credential)"[[:space:]]*:'),
    outcome_summary JSONB
        CHECK (outcome_summary IS NULL OR jsonb_typeof(outcome_summary) = 'object')
        CHECK (outcome_summary IS NULL OR octet_length(outcome_summary::text) <= 32768)
        CHECK (outcome_summary IS NULL OR outcome_summary::text !~* '"(prompt|raw[_-]?prompt|raw[_-]?question|review([_-]?text)?|member([_-]?id)?|secret|token|password|authorization|cookie|api[_-]?key|credential)"[[:space:]]*:'),
    failure_code VARCHAR(96),
    rounds_used INTEGER NOT NULL DEFAULT 0 CHECK (rounds_used BETWEEN 0 AND 2),
    tool_calls_used INTEGER NOT NULL DEFAULT 0 CHECK (tool_calls_used BETWEEN 0 AND 10),
    facts_used INTEGER NOT NULL DEFAULT 0 CHECK (facts_used >= 0),
    evidence_bytes_used BIGINT NOT NULL DEFAULT 0 CHECK (evidence_bytes_used >= 0),
    next_event_sequence BIGINT NOT NULL DEFAULT 0 CHECK (next_event_sequence >= 0),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    UNIQUE (run_id, factory_id),
    CHECK (
        (state = 'RUNNING' AND completed_at IS NULL)
        OR (state <> 'RUNNING' AND completed_at IS NOT NULL)
    )
);

CREATE TABLE IF NOT EXISTS smart_bi_agent_event (
    run_id UUID NOT NULL,
    factory_id VARCHAR(64) NOT NULL,
    event_sequence BIGINT NOT NULL CHECK (event_sequence > 0),
    event_type VARCHAR(48) NOT NULL CHECK (event_type IN (
        'RUN_STARTED', 'ROUTE_SELECTED', 'PLAN_CREATED', 'STEP_STARTED',
        'STEP_COMPLETED', 'STEP_FAILED', 'BUDGET_EXCEEDED', 'RUN_CANCELLED',
        'RUN_COMPLETED', 'RUN_FAILED'
    )),
    step_id VARCHAR(96),
    tool_name VARCHAR(128),
    payload JSONB NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(payload) = 'object')
        CHECK (octet_length(payload::text) <= 32768)
        CHECK (payload::text !~* '"(prompt|raw[_-]?prompt|raw[_-]?question|review([_-]?text)?|member([_-]?id)?|secret|token|password|authorization|cookie|api[_-]?key|credential)"[[:space:]]*:'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (run_id, event_sequence),
    CONSTRAINT smart_bi_agent_event_run_tenant_fk
        FOREIGN KEY (run_id, factory_id)
        REFERENCES smart_bi_agent_run (run_id, factory_id)
);

-- Exact JSON schemas close the generic-key loophole (for example, hiding a
-- raw prompt under "message"). The application allowlist is mirrored here so
-- direct SQL through the app role cannot weaken the ledger contract.
CREATE OR REPLACE FUNCTION smart_bi_agent_exact_json_keys(value JSONB, keys TEXT[])
RETURNS BOOLEAN
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT jsonb_typeof(value) = 'object'
       AND value ?& keys
       AND (SELECT COUNT(*) FROM jsonb_object_keys(value)) = cardinality(keys)
$$;

CREATE OR REPLACE FUNCTION smart_bi_agent_request_payload_is_safe(value JSONB)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
AS $$
BEGIN
    IF NOT smart_bi_agent_exact_json_keys(
        value, ARRAY['routeCode','startDate','endDate','storeTopN','dishTopN']
    ) THEN RETURN FALSE; END IF;
    IF value->>'routeCode' <> 'GROSS_MARGIN_DECLINE_ATTRIBUTION'
       OR jsonb_typeof(value->'startDate') <> 'string'
       OR jsonb_typeof(value->'endDate') <> 'string'
       OR value->>'startDate' !~ '^\d{4}-\d{2}-\d{2}$'
       OR value->>'endDate' !~ '^\d{4}-\d{2}-\d{2}$'
       OR jsonb_typeof(value->'storeTopN') <> 'number'
       OR jsonb_typeof(value->'dishTopN') <> 'number'
       OR value->>'storeTopN' !~ '^\d+$'
       OR value->>'dishTopN' !~ '^\d+$' THEN RETURN FALSE; END IF;
    RETURN (value->>'storeTopN')::INTEGER BETWEEN 1 AND 50
       AND (value->>'dishTopN')::INTEGER BETWEEN 1 AND 20;
END;
$$;

CREATE OR REPLACE FUNCTION smart_bi_agent_outcome_payload_is_safe(value JSONB)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    item JSONB;
    code TEXT;
BEGIN
    IF NOT smart_bi_agent_exact_json_keys(
        value, ARRAY['status','routeCode','claims','blockers','observations','attributionSupported']
    ) THEN RETURN FALSE; END IF;
    IF value->>'status' NOT IN (
        'COMPLETE','PARTIAL','NOT_COMPUTABLE','FAILED','CANCELLED','BUDGET_EXCEEDED'
    ) OR value->>'routeCode' <> 'GROSS_MARGIN_DECLINE_ATTRIBUTION'
       OR jsonb_typeof(value->'claims') <> 'array'
       OR jsonb_typeof(value->'blockers') <> 'array'
       OR jsonb_typeof(value->'observations') <> 'array'
       OR jsonb_typeof(value->'attributionSupported') <> 'boolean'
       OR jsonb_array_length(value->'claims') > 100
       OR jsonb_array_length(value->'blockers') > 100
       OR jsonb_array_length(value->'observations') > 100 THEN RETURN FALSE; END IF;
    FOR code IN SELECT jsonb_array_elements_text(value->'blockers') LOOP
        IF code !~ '^[A-Z][A-Z0-9_]{0,95}$' THEN RETURN FALSE; END IF;
    END LOOP;
    FOR code IN SELECT jsonb_array_elements_text(value->'observations') LOOP
        IF code !~ '^[A-Z][A-Z0-9_]{0,95}$' THEN RETURN FALSE; END IF;
    END LOOP;
    FOR item IN
        SELECT element
        FROM jsonb_array_elements(value->'claims') AS claim_rows(element)
    LOOP
        IF NOT smart_bi_agent_exact_json_keys(
            item, ARRAY['statementCode','metric','value','unit','evidenceId','factId']
        ) THEN RETURN FALSE; END IF;
        IF jsonb_typeof(item->'statementCode') <> 'string'
           OR item->>'statementCode' !~ '^[A-Z][A-Z0-9_]{0,95}$'
           OR jsonb_typeof(item->'metric') <> 'string'
           OR item->>'metric' !~ '^[A-Za-z][A-Za-z0-9_]{0,95}$'
           OR jsonb_typeof(item->'value') <> 'string'
           OR item->>'value' !~ '^-?(0|[1-9]\d*)(\.\d+)?$'
           OR jsonb_typeof(item->'evidenceId') <> 'string'
           OR item->>'evidenceId' !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
           OR jsonb_typeof(item->'factId') <> 'string'
           OR item->>'factId' !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$' THEN
            RETURN FALSE;
        END IF;
        IF item->'unit' <> 'null'::jsonb AND (
            jsonb_typeof(item->'unit') <> 'string'
            OR item->>'unit' !~ '^[A-Z][A-Z0-9_]{0,95}$'
        ) THEN RETURN FALSE; END IF;
    END LOOP;
    RETURN TRUE;
EXCEPTION WHEN OTHERS THEN
    RETURN FALSE;
END;
$$;

CREATE OR REPLACE FUNCTION smart_bi_agent_event_payload_is_safe(
    kind TEXT, value JSONB
)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    code TEXT;
BEGIN
    IF kind IN ('RUN_STARTED','ROUTE_SELECTED') THEN
        RETURN smart_bi_agent_exact_json_keys(value, ARRAY['routeCode'])
           AND value->>'routeCode' = 'GROSS_MARGIN_DECLINE_ATTRIBUTION';
    ELSIF kind = 'PLAN_CREATED' THEN
        IF NOT smart_bi_agent_exact_json_keys(
            value, ARRAY['routeCode','stepCount','maxRounds','maxToolCalls']
        ) OR value->>'routeCode' <> 'GROSS_MARGIN_DECLINE_ATTRIBUTION'
          OR jsonb_typeof(value->'stepCount') <> 'number'
          OR jsonb_typeof(value->'maxRounds') <> 'number'
          OR jsonb_typeof(value->'maxToolCalls') <> 'number' THEN RETURN FALSE; END IF;
        FOR code IN SELECT value->>key FROM unnest(ARRAY['stepCount','maxRounds','maxToolCalls']) key LOOP
            IF code !~ '^\d+$' THEN RETURN FALSE; END IF;
        END LOOP;
        RETURN (value->>'stepCount')::INTEGER BETWEEN 1 AND 10
           AND (value->>'maxRounds')::INTEGER BETWEEN 1 AND 2
           AND (value->>'maxToolCalls')::INTEGER BETWEEN 1 AND 10;
    ELSIF kind = 'STEP_STARTED' THEN
        RETURN smart_bi_agent_exact_json_keys(value, ARRAY['round','purposeCode'])
           AND jsonb_typeof(value->'round') = 'number'
           AND jsonb_typeof(value->'purposeCode') = 'string'
           AND value->>'round' ~ '^[12]$'
           AND value->>'purposeCode' ~ '^[A-Z][A-Z0-9_]{0,95}$';
    ELSIF kind = 'STEP_COMPLETED' THEN
        IF NOT smart_bi_agent_exact_json_keys(
            value, ARRAY['round','evidenceId','evidenceStatus','factCount','evidenceBytes','warningCodes']
        ) OR jsonb_typeof(value->'round') <> 'number'
          OR jsonb_typeof(value->'evidenceId') <> 'string'
          OR jsonb_typeof(value->'evidenceStatus') <> 'string'
          OR jsonb_typeof(value->'factCount') <> 'number'
          OR jsonb_typeof(value->'evidenceBytes') <> 'number'
          OR value->>'round' !~ '^[12]$'
          OR value->>'evidenceId' !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
          OR value->>'evidenceStatus' NOT IN ('OK','EMPTY','PARTIAL','NOT_COMPUTABLE','CONFLICT','DENIED','ERROR')
          OR value->>'factCount' !~ '^\d+$'
          OR value->>'evidenceBytes' !~ '^\d+$'
          OR jsonb_typeof(value->'warningCodes') <> 'array'
          OR jsonb_array_length(value->'warningCodes') > 100 THEN RETURN FALSE; END IF;
        FOR code IN SELECT jsonb_array_elements_text(value->'warningCodes') LOOP
            IF code !~ '^[A-Z][A-Z0-9_]{0,95}$' THEN RETURN FALSE; END IF;
        END LOOP;
        RETURN TRUE;
    ELSIF kind IN ('STEP_FAILED','RUN_FAILED','RUN_CANCELLED') THEN
        RETURN smart_bi_agent_exact_json_keys(value, ARRAY['failureCode'])
           AND value->>'failureCode' ~ '^[A-Z][A-Z0-9_]{0,95}$';
    ELSIF kind = 'BUDGET_EXCEEDED' THEN
        IF NOT smart_bi_agent_exact_json_keys(
            value, ARRAY['failureCode','roundsUsed','toolCallsUsed','factsUsed','evidenceBytesUsed']
        ) OR jsonb_typeof(value->'failureCode') <> 'string'
          OR jsonb_typeof(value->'roundsUsed') <> 'number'
          OR jsonb_typeof(value->'toolCallsUsed') <> 'number'
          OR jsonb_typeof(value->'factsUsed') <> 'number'
          OR jsonb_typeof(value->'evidenceBytesUsed') <> 'number'
          OR value->>'failureCode' !~ '^[A-Z][A-Z0-9_]{0,95}$' THEN RETURN FALSE; END IF;
        FOR code IN SELECT value->>key FROM unnest(
            ARRAY['roundsUsed','toolCallsUsed','factsUsed','evidenceBytesUsed']
        ) key LOOP
            IF code !~ '^\d+$' THEN RETURN FALSE; END IF;
        END LOOP;
        RETURN (value->>'roundsUsed')::INTEGER BETWEEN 0 AND 2
           AND (value->>'toolCallsUsed')::INTEGER BETWEEN 0 AND 10;
    ELSIF kind = 'RUN_COMPLETED' THEN
        RETURN smart_bi_agent_outcome_payload_is_safe(value);
    END IF;
    RETURN FALSE;
EXCEPTION WHEN OTHERS THEN
    RETURN FALSE;
END;
$$;

ALTER TABLE smart_bi_agent_run
    DROP CONSTRAINT IF EXISTS smart_bi_agent_run_request_schema;
ALTER TABLE smart_bi_agent_run
    ADD CONSTRAINT smart_bi_agent_run_request_schema
    CHECK (smart_bi_agent_request_payload_is_safe(sanitized_request));
ALTER TABLE smart_bi_agent_run
    DROP CONSTRAINT IF EXISTS smart_bi_agent_run_outcome_schema;
ALTER TABLE smart_bi_agent_run
    ADD CONSTRAINT smart_bi_agent_run_outcome_schema
    CHECK (
        outcome_summary IS NULL
        OR smart_bi_agent_outcome_payload_is_safe(outcome_summary)
    );
ALTER TABLE smart_bi_agent_event
    DROP CONSTRAINT IF EXISTS smart_bi_agent_event_payload_schema;
ALTER TABLE smart_bi_agent_event
    ADD CONSTRAINT smart_bi_agent_event_payload_schema
    CHECK (smart_bi_agent_event_payload_is_safe(event_type, payload));

CREATE INDEX IF NOT EXISTS idx_smart_bi_agent_run_tenant_created
    ON smart_bi_agent_run (factory_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_smart_bi_agent_run_tenant_state
    ON smart_bi_agent_run (factory_id, state, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_smart_bi_agent_event_tenant_created
    ON smart_bi_agent_event (factory_id, created_at DESC);

CREATE OR REPLACE FUNCTION smart_bi_agent_run_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.run_id <> OLD.run_id
       OR NEW.factory_id <> OLD.factory_id
       OR NEW.business_type <> OLD.business_type
       OR NEW.correlation_id <> OLD.correlation_id
       OR NEW.route_code <> OLD.route_code
       OR NEW.sanitized_request <> OLD.sanitized_request
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'agent run identity/request fields are immutable';
    END IF;
    IF OLD.state <> 'RUNNING' THEN
        RAISE EXCEPTION 'terminal agent run is immutable';
    END IF;
    IF NEW.state = 'RUNNING' AND (NEW.completed_at IS NOT NULL OR NEW.outcome_summary IS NOT NULL) THEN
        RAISE EXCEPTION 'running agent run cannot contain terminal outcome';
    END IF;
    IF NEW.state <> 'RUNNING' AND (NEW.completed_at IS NULL OR NEW.outcome_summary IS NULL) THEN
        RAISE EXCEPTION 'terminal agent run requires outcome and completion time';
    END IF;
    IF NEW.failure_code IS NOT NULL
       AND NEW.failure_code !~ '^[A-Z][A-Z0-9_]{0,95}$' THEN
        RAISE EXCEPTION 'agent run failure code must be controlled';
    END IF;
    IF (NEW.state IN ('COMPLETED','PARTIAL') AND NEW.failure_code IS NOT NULL)
       OR (NEW.state IN ('FAILED','CANCELLED','BUDGET_EXCEEDED') AND NEW.failure_code IS NULL) THEN
        RAISE EXCEPTION 'agent run terminal failure-code contract mismatch';
    END IF;
    IF (NEW.state = 'COMPLETED' AND NEW.outcome_summary->>'status' <> 'COMPLETE')
       OR (NEW.state = 'PARTIAL' AND NEW.outcome_summary->>'status' NOT IN ('PARTIAL','NOT_COMPUTABLE'))
       OR (NEW.state = 'FAILED' AND NEW.outcome_summary->>'status' <> 'FAILED')
       OR (NEW.state = 'CANCELLED' AND NEW.outcome_summary->>'status' <> 'CANCELLED')
       OR (NEW.state = 'BUDGET_EXCEEDED' AND NEW.outcome_summary->>'status' <> 'BUDGET_EXCEEDED') THEN
        RAISE EXCEPTION 'agent run state and outcome status mismatch';
    END IF;
    IF NEW.rounds_used < OLD.rounds_used
       OR NEW.tool_calls_used < OLD.tool_calls_used
       OR NEW.facts_used < OLD.facts_used
       OR NEW.evidence_bytes_used < OLD.evidence_bytes_used
       OR NEW.next_event_sequence < OLD.next_event_sequence THEN
        RAISE EXCEPTION 'agent run counters are monotonic';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'agent run version must advance exactly once';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_smart_bi_agent_run_guard ON smart_bi_agent_run;
CREATE TRIGGER trg_smart_bi_agent_run_guard
BEFORE UPDATE ON smart_bi_agent_run
FOR EACH ROW EXECUTE FUNCTION smart_bi_agent_run_guard();

CREATE OR REPLACE FUNCTION smart_bi_agent_event_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM smart_bi_agent_run run
        WHERE run.run_id = NEW.run_id
          AND run.factory_id = NEW.factory_id
          AND run.next_event_sequence = NEW.event_sequence
          AND (
              (
                  run.state = 'RUNNING'
                  AND NEW.event_type NOT IN (
                      'RUN_COMPLETED','RUN_FAILED','RUN_CANCELLED','BUDGET_EXCEEDED'
                  )
              )
              OR (NEW.event_type = 'RUN_COMPLETED' AND run.state IN ('COMPLETED', 'PARTIAL'))
              OR (NEW.event_type = 'RUN_FAILED' AND run.state = 'FAILED')
              OR (NEW.event_type = 'RUN_CANCELLED' AND run.state = 'CANCELLED')
              OR (NEW.event_type = 'BUDGET_EXCEEDED' AND run.state = 'BUDGET_EXCEEDED')
          )
    ) THEN
        RAISE EXCEPTION 'event sequence is not the current tenant-bound run sequence';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION smart_bi_agent_event_append_only()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'smart_bi_agent_event is append-only';
END;
$$;

DROP TRIGGER IF EXISTS trg_smart_bi_agent_event_insert_guard ON smart_bi_agent_event;
CREATE TRIGGER trg_smart_bi_agent_event_insert_guard
BEFORE INSERT ON smart_bi_agent_event
FOR EACH ROW EXECUTE FUNCTION smart_bi_agent_event_insert_guard();

DROP TRIGGER IF EXISTS trg_smart_bi_agent_event_append_only ON smart_bi_agent_event;
CREATE TRIGGER trg_smart_bi_agent_event_append_only
BEFORE UPDATE OR DELETE ON smart_bi_agent_event
FOR EACH ROW EXECUTE FUNCTION smart_bi_agent_event_append_only();

ALTER TABLE smart_bi_agent_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE smart_bi_agent_run FORCE ROW LEVEL SECURITY;
ALTER TABLE smart_bi_agent_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE smart_bi_agent_event FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS smart_bi_agent_run_tenant_select ON smart_bi_agent_run;
CREATE POLICY smart_bi_agent_run_tenant_select ON smart_bi_agent_run
    FOR SELECT
    USING (factory_id = current_setting('app.factory_id', true));

DROP POLICY IF EXISTS smart_bi_agent_run_tenant_insert ON smart_bi_agent_run;
CREATE POLICY smart_bi_agent_run_tenant_insert ON smart_bi_agent_run
    FOR INSERT
    WITH CHECK (factory_id = current_setting('app.factory_id', true));

DROP POLICY IF EXISTS smart_bi_agent_run_tenant_update ON smart_bi_agent_run;
CREATE POLICY smart_bi_agent_run_tenant_update ON smart_bi_agent_run
    FOR UPDATE
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));

DROP POLICY IF EXISTS smart_bi_agent_event_tenant_select ON smart_bi_agent_event;
CREATE POLICY smart_bi_agent_event_tenant_select ON smart_bi_agent_event
    FOR SELECT
    USING (factory_id = current_setting('app.factory_id', true));

DROP POLICY IF EXISTS smart_bi_agent_event_tenant_insert ON smart_bi_agent_event;
CREATE POLICY smart_bi_agent_event_tenant_insert ON smart_bi_agent_event
    FOR INSERT
    WITH CHECK (factory_id = current_setting('app.factory_id', true));

REVOKE ALL ON smart_bi_agent_run FROM PUBLIC;
REVOKE ALL ON smart_bi_agent_event FROM PUBLIC;
REVOKE ALL ON smart_bi_agent_run FROM smartbi_user;
REVOKE ALL ON smart_bi_agent_event FROM smartbi_user;
GRANT SELECT, INSERT ON smart_bi_agent_run TO smartbi_user;
GRANT UPDATE (
    state, outcome_summary, failure_code, rounds_used, tool_calls_used,
    facts_used, evidence_bytes_used, next_event_sequence, version,
    updated_at, completed_at
) ON smart_bi_agent_run TO smartbi_user;
GRANT SELECT, INSERT ON smart_bi_agent_event TO smartbi_user;

COMMENT ON TABLE smart_bi_agent_run IS
    'Tenant-isolated bounded evidence runs; no raw prompts, reviews, members or secrets.';
COMMENT ON TABLE smart_bi_agent_event IS
    'Append-only compact trajectory events with atomic per-run sequence.';
