-- Adaptive restaurant evidence loop contracts (expand phase).
-- Release order is mandatory:
--   route gate OFF -> V03 -> new Java -> new Python -> RN -> health verification
--   -> V05 owner contract -> route gate ON.
-- Keep the route gate OFF for the whole mixed-version window. Old code can maintain
-- legacy NULL-owner rows, but V03 rejects creation of any new NULL-owner run.
-- No ERP write capability is introduced; ActionProposal remains structured read-only JSON.

SET LOCAL lock_timeout = '5s';

-- Existing rows intentionally remain NULL: the previous schema did not retain enough
-- truth to infer an owner safely. During expand only, old code without app.user_id can
-- maintain those legacy rows; new code can only access rows matching its trusted owner.
ALTER TABLE smart_bi_agent_run
    ADD COLUMN IF NOT EXISTS owner_user_id VARCHAR(128);
ALTER TABLE smart_bi_agent_run
    DROP CONSTRAINT IF EXISTS smart_bi_agent_run_owner_user_id_check;
ALTER TABLE smart_bi_agent_run
    ADD CONSTRAINT smart_bi_agent_run_owner_user_id_check CHECK (
        owner_user_id IS NULL
        OR owner_user_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
    );

CREATE OR REPLACE FUNCTION smart_bi_agent_run_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.run_id <> OLD.run_id
       OR NEW.factory_id <> OLD.factory_id
       OR NEW.owner_user_id IS DISTINCT FROM OLD.owner_user_id
       OR NEW.business_type <> OLD.business_type
       OR NEW.correlation_id <> OLD.correlation_id
       OR NEW.route_code <> OLD.route_code
       OR NEW.sanitized_request <> OLD.sanitized_request
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'agent run identity/request fields are immutable';
    END IF;
    IF OLD.state <> 'RUNNING' THEN RAISE EXCEPTION 'terminal agent run is immutable'; END IF;
    IF NEW.state = 'RUNNING' AND (NEW.completed_at IS NOT NULL OR NEW.outcome_summary IS NOT NULL) THEN
        RAISE EXCEPTION 'running agent run cannot contain terminal outcome';
    END IF;
    IF NEW.state <> 'RUNNING' AND (NEW.completed_at IS NULL OR NEW.outcome_summary IS NULL) THEN
        RAISE EXCEPTION 'terminal agent run requires outcome and completion time';
    END IF;
    IF NEW.failure_code IS NOT NULL AND NEW.failure_code !~ '^[A-Z][A-Z0-9_]{0,95}$' THEN
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
    IF NEW.rounds_used < OLD.rounds_used OR NEW.tool_calls_used < OLD.tool_calls_used
       OR NEW.facts_used < OLD.facts_used OR NEW.evidence_bytes_used < OLD.evidence_bytes_used
       OR NEW.next_event_sequence < OLD.next_event_sequence THEN
        RAISE EXCEPTION 'agent run counters are monotonic';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'agent run version must advance exactly once';
    END IF;
    RETURN NEW;
END;
$$;

DROP POLICY IF EXISTS smart_bi_agent_run_tenant_select ON smart_bi_agent_run;
CREATE POLICY smart_bi_agent_run_tenant_select ON smart_bi_agent_run FOR SELECT USING (
    factory_id = current_setting('app.factory_id', true)
    AND (
        owner_user_id = NULLIF(current_setting('app.user_id', true), '')
        OR (
            owner_user_id IS NULL
            AND NULLIF(current_setting('app.user_id', true), '') IS NULL
        )
    )
);
DROP POLICY IF EXISTS smart_bi_agent_run_tenant_insert ON smart_bi_agent_run;
CREATE POLICY smart_bi_agent_run_tenant_insert ON smart_bi_agent_run FOR INSERT WITH CHECK (
    factory_id = current_setting('app.factory_id', true)
    AND NULLIF(current_setting('app.user_id', true), '') IS NOT NULL
    AND owner_user_id = NULLIF(current_setting('app.user_id', true), '')
);
DROP POLICY IF EXISTS smart_bi_agent_run_tenant_update ON smart_bi_agent_run;
CREATE POLICY smart_bi_agent_run_tenant_update ON smart_bi_agent_run FOR UPDATE USING (
    factory_id = current_setting('app.factory_id', true)
    AND (
        owner_user_id = NULLIF(current_setting('app.user_id', true), '')
        OR (
            owner_user_id IS NULL
            AND NULLIF(current_setting('app.user_id', true), '') IS NULL
        )
    )
) WITH CHECK (
    factory_id = current_setting('app.factory_id', true)
    AND (
        owner_user_id = NULLIF(current_setting('app.user_id', true), '')
        OR (
            owner_user_id IS NULL
            AND NULLIF(current_setting('app.user_id', true), '') IS NULL
        )
    )
);
DROP POLICY IF EXISTS smart_bi_agent_event_tenant_select ON smart_bi_agent_event;
CREATE POLICY smart_bi_agent_event_tenant_select ON smart_bi_agent_event FOR SELECT USING (
    factory_id = current_setting('app.factory_id', true)
    AND EXISTS (
        SELECT 1 FROM smart_bi_agent_run owned
        WHERE owned.run_id = smart_bi_agent_event.run_id
          AND owned.factory_id = smart_bi_agent_event.factory_id
          AND (
              owned.owner_user_id = NULLIF(current_setting('app.user_id', true), '')
              OR (
                  owned.owner_user_id IS NULL
                  AND NULLIF(current_setting('app.user_id', true), '') IS NULL
              )
          )
    )
);
DROP POLICY IF EXISTS smart_bi_agent_event_tenant_insert ON smart_bi_agent_event;
CREATE POLICY smart_bi_agent_event_tenant_insert ON smart_bi_agent_event FOR INSERT WITH CHECK (
    factory_id = current_setting('app.factory_id', true)
    AND EXISTS (
        SELECT 1 FROM smart_bi_agent_run owned
        WHERE owned.run_id = smart_bi_agent_event.run_id
          AND owned.factory_id = smart_bi_agent_event.factory_id
          AND (
              owned.owner_user_id = NULLIF(current_setting('app.user_id', true), '')
              OR (
                  owned.owner_user_id IS NULL
                  AND NULLIF(current_setting('app.user_id', true), '') IS NULL
              )
          )
    )
);

ALTER TABLE smart_bi_agent_event
    DROP CONSTRAINT IF EXISTS smart_bi_agent_event_event_type_check;
ALTER TABLE smart_bi_agent_event
    ADD CONSTRAINT smart_bi_agent_event_event_type_check CHECK (event_type IN (
        'RUN_STARTED', 'ROUTE_SELECTED', 'PLAN_CREATED', 'STEP_STARTED',
        'STEP_COMPLETED', 'STEP_FAILED', 'EVIDENCE_RECORDED', 'EVIDENCE_GAP',
        'REPLAN', 'CLARIFICATION', 'CANCEL_REQUESTED', 'BUDGET_EXCEEDED',
        'RUN_CANCELLED', 'RUN_COMPLETED', 'RUN_FAILED'
    ));

CREATE OR REPLACE FUNCTION smart_bi_agent_outcome_payload_is_safe(value JSONB)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    item JSONB;
    nested JSONB;
    code TEXT;
    expected_keys TEXT[] := ARRAY[
        'status','routeCode','claims','blockers','observations','attributionSupported'
    ];
BEGIN
    IF value ? 'actionProposals' THEN
        expected_keys := array_append(expected_keys, 'actionProposals');
    END IF;
    IF NOT smart_bi_agent_exact_json_keys(value, expected_keys) THEN RETURN FALSE; END IF;
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
    FOR item IN SELECT element FROM jsonb_array_elements(value->'claims') rows(element) LOOP
        IF NOT smart_bi_agent_exact_json_keys(
            item, ARRAY['statementCode','metric','value','unit','evidenceId','factId']
        ) OR item->>'statementCode' !~ '^[A-Z][A-Z0-9_]{0,95}$'
          OR item->>'metric' !~ '^[A-Za-z][A-Za-z0-9_]{0,95}$'
          OR item->>'value' !~ '^-?(0|[1-9]\d*)(\.\d+)?$'
          OR item->>'evidenceId' !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
          OR item->>'factId' !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$' THEN
            RETURN FALSE;
        END IF;
        IF item->'unit' <> 'null'::jsonb AND item->>'unit' !~ '^[A-Z][A-Z0-9_]{0,95}$' THEN
            RETURN FALSE;
        END IF;
    END LOOP;
    IF value ? 'actionProposals' THEN
        IF jsonb_typeof(value->'actionProposals') <> 'array'
           OR jsonb_array_length(value->'actionProposals') > 20 THEN RETURN FALSE; END IF;
        FOR item IN SELECT element FROM jsonb_array_elements(value->'actionProposals') rows(element) LOOP
            IF NOT smart_bi_agent_exact_json_keys(
                item, ARRAY['proposalCode','actionCode','rationaleCodes','evidenceReferences','executionMode']
            ) OR item->>'proposalCode' !~ '^[A-Z][A-Z0-9_]{0,95}$'
              OR item->>'actionCode' !~ '^[A-Z][A-Z0-9_]{0,95}$'
              OR item->>'executionMode' <> 'READ_ONLY_PROPOSAL'
              OR jsonb_typeof(item->'rationaleCodes') <> 'array'
              OR jsonb_array_length(item->'rationaleCodes') NOT BETWEEN 1 AND 20
              OR jsonb_typeof(item->'evidenceReferences') <> 'array'
              OR jsonb_array_length(item->'evidenceReferences') > 20 THEN RETURN FALSE; END IF;
            FOR code IN SELECT jsonb_array_elements_text(item->'rationaleCodes') LOOP
                IF code !~ '^[A-Z][A-Z0-9_]{0,95}$' THEN RETURN FALSE; END IF;
            END LOOP;
            FOR nested IN SELECT element FROM jsonb_array_elements(item->'evidenceReferences') rows(element) LOOP
                IF NOT smart_bi_agent_exact_json_keys(nested, ARRAY['evidenceId','factId'])
                   OR nested->>'evidenceId' !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
                   OR nested->>'factId' !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$' THEN
                    RETURN FALSE;
                END IF;
            END LOOP;
        END LOOP;
    END IF;
    RETURN TRUE;
EXCEPTION WHEN OTHERS THEN
    RETURN FALSE;
END;
$$;

CREATE OR REPLACE FUNCTION smart_bi_agent_event_payload_is_safe(kind TEXT, value JSONB)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    item JSONB;
    nested JSONB;
    code TEXT;
    dimension_key TEXT;
    dimension_value JSONB;
BEGIN
    IF kind IN ('RUN_STARTED','ROUTE_SELECTED') THEN
        RETURN smart_bi_agent_exact_json_keys(value, ARRAY['routeCode'])
           AND value->>'routeCode' = 'GROSS_MARGIN_DECLINE_ATTRIBUTION';
    ELSIF kind = 'PLAN_CREATED' THEN
        RETURN smart_bi_agent_exact_json_keys(value, ARRAY['routeCode','stepCount','maxRounds','maxToolCalls'])
           AND value->>'routeCode' = 'GROSS_MARGIN_DECLINE_ATTRIBUTION'
           AND value->>'stepCount' ~ '^\d+$' AND (value->>'stepCount')::INTEGER BETWEEN 1 AND 10
           AND value->>'maxRounds' ~ '^\d+$' AND (value->>'maxRounds')::INTEGER BETWEEN 1 AND 2
           AND value->>'maxToolCalls' ~ '^\d+$' AND (value->>'maxToolCalls')::INTEGER BETWEEN 1 AND 10;
    ELSIF kind = 'STEP_STARTED' THEN
        RETURN smart_bi_agent_exact_json_keys(value, ARRAY['round','purposeCode'])
           AND value->>'round' ~ '^[12]$'
           AND value->>'purposeCode' ~ '^[A-Z][A-Z0-9_]{0,95}$';
    ELSIF kind = 'STEP_COMPLETED' THEN
        IF NOT smart_bi_agent_exact_json_keys(
            value, ARRAY['round','evidenceId','evidenceStatus','factCount','evidenceBytes','warningCodes']
        ) OR value->>'round' !~ '^[12]$'
          OR value->>'evidenceId' !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
          OR value->>'evidenceStatus' NOT IN ('OK','EMPTY','PARTIAL','NOT_COMPUTABLE','CONFLICT','DENIED','ERROR')
          OR value->>'factCount' !~ '^\d+$' OR value->>'evidenceBytes' !~ '^\d+$'
          OR jsonb_typeof(value->'warningCodes') <> 'array'
          OR jsonb_array_length(value->'warningCodes') > 100 THEN RETURN FALSE; END IF;
        FOR code IN SELECT jsonb_array_elements_text(value->'warningCodes') LOOP
            IF code !~ '^[A-Z][A-Z0-9_]{0,95}$' THEN RETURN FALSE; END IF;
        END LOOP;
        RETURN TRUE;
    ELSIF kind = 'EVIDENCE_RECORDED' THEN
        IF NOT smart_bi_agent_exact_json_keys(
            value, ARRAY['evidenceId','evidenceStatus','factReferences','provenance','warningCodes','drilldownTruncated']
        ) OR value->>'evidenceId' !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
          OR value->>'evidenceStatus' NOT IN ('OK','EMPTY','PARTIAL','NOT_COMPUTABLE','CONFLICT','DENIED','ERROR')
          OR jsonb_typeof(value->'factReferences') <> 'array'
          OR jsonb_array_length(value->'factReferences') > 100
          OR jsonb_typeof(value->'provenance') <> 'array'
          OR jsonb_array_length(value->'provenance') > 100
          OR jsonb_typeof(value->'warningCodes') <> 'array'
          OR jsonb_array_length(value->'warningCodes') > 100
          OR jsonb_typeof(value->'drilldownTruncated') <> 'boolean' THEN RETURN FALSE; END IF;
        FOR code IN SELECT jsonb_array_elements_text(value->'warningCodes') LOOP
            IF code !~ '^[A-Z][A-Z0-9_]{0,95}$' THEN RETURN FALSE; END IF;
        END LOOP;
        FOR item IN SELECT element FROM jsonb_array_elements(value->'factReferences') rows(element) LOOP
            IF NOT smart_bi_agent_exact_json_keys(
                item, ARRAY['factId','metric','value','unit','dimensions','provenanceRefs']
            ) OR item->>'factId' !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
              OR item->>'metric' !~ '^[A-Za-z][A-Za-z0-9_]{0,95}$'
              OR item->>'value' !~ '^-?(0|[1-9]\d*)(\.\d+)?$'
              OR jsonb_typeof(item->'dimensions') <> 'object'
              OR (SELECT COUNT(*) FROM jsonb_object_keys(item->'dimensions')) > 20
              OR jsonb_typeof(item->'provenanceRefs') <> 'array'
              OR jsonb_array_length(item->'provenanceRefs') NOT BETWEEN 1 AND 20 THEN RETURN FALSE; END IF;
            IF item->'unit' <> 'null'::jsonb AND item->>'unit' !~ '^[A-Z][A-Z0-9_]{0,95}$' THEN RETURN FALSE; END IF;
            FOR dimension_key, dimension_value IN
                SELECT key, value FROM jsonb_each(item->'dimensions')
            LOOP
                IF dimension_key !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
                   OR jsonb_typeof(dimension_value) <> 'string'
                   OR length(dimension_value #>> '{}') NOT BETWEEN 1 AND 256
                   OR (dimension_value #>> '{}') ~ '[[:cntrl:]]' THEN RETURN FALSE; END IF;
            END LOOP;
            FOR code IN SELECT jsonb_array_elements_text(item->'provenanceRefs') LOOP
                IF code !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$' THEN RETURN FALSE; END IF;
            END LOOP;
        END LOOP;
        FOR item IN SELECT element FROM jsonb_array_elements(value->'provenance') rows(element) LOOP
            IF NOT smart_bi_agent_exact_json_keys(item, ARRAY['refId','sourceType','asset','queryId','sourceVersion'])
               OR item->>'refId' !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
               OR item->>'sourceType' !~ '^[A-Z][A-Z0-9_]{0,95}$'
               OR length(item->>'asset') NOT BETWEEN 1 AND 256
               OR item->>'asset' ~ '[[:cntrl:]]'
               OR item->>'queryId' !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
               OR item->>'sourceVersion' !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$' THEN RETURN FALSE; END IF;
        END LOOP;
        RETURN TRUE;
    ELSIF kind = 'EVIDENCE_GAP' THEN
        IF NOT smart_bi_agent_exact_json_keys(value, ARRAY['round','gapCodes','resolvable'])
           OR value->>'round' !~ '^[12]$' OR jsonb_typeof(value->'gapCodes') <> 'array'
           OR jsonb_array_length(value->'gapCodes') > 100
           OR jsonb_typeof(value->'resolvable') <> 'boolean' THEN RETURN FALSE; END IF;
        FOR code IN SELECT jsonb_array_elements_text(value->'gapCodes') LOOP
            IF code !~ '^[A-Z][A-Z0-9_]{0,95}$' THEN RETURN FALSE; END IF;
        END LOOP;
        RETURN TRUE;
    ELSIF kind = 'REPLAN' THEN
        IF NOT smart_bi_agent_exact_json_keys(value, ARRAY['fromRound','toRound','gapCodes','stepIds'])
           OR value->>'fromRound' <> '1' OR value->>'toRound' <> '2'
           OR jsonb_typeof(value->'gapCodes') <> 'array' OR jsonb_array_length(value->'gapCodes') > 100
           OR jsonb_typeof(value->'stepIds') <> 'array' OR jsonb_array_length(value->'stepIds') > 10 THEN RETURN FALSE; END IF;
        FOR code IN SELECT jsonb_array_elements_text(value->'gapCodes') LOOP
            IF code !~ '^[A-Z][A-Z0-9_]{0,95}$' THEN RETURN FALSE; END IF;
        END LOOP;
        FOR code IN SELECT jsonb_array_elements_text(value->'stepIds') LOOP
            IF code !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$' THEN RETURN FALSE; END IF;
        END LOOP;
        RETURN TRUE;
    ELSIF kind = 'CLARIFICATION' THEN
        IF NOT smart_bi_agent_exact_json_keys(value, ARRAY['clarificationCode','gapCodes'])
           OR value->>'clarificationCode' !~ '^[A-Z][A-Z0-9_]{0,95}$'
           OR jsonb_typeof(value->'gapCodes') <> 'array'
           OR jsonb_array_length(value->'gapCodes') > 100 THEN RETURN FALSE; END IF;
        FOR code IN SELECT jsonb_array_elements_text(value->'gapCodes') LOOP
            IF code !~ '^[A-Z][A-Z0-9_]{0,95}$' THEN RETURN FALSE; END IF;
        END LOOP;
        RETURN TRUE;
    ELSIF kind = 'CANCEL_REQUESTED' THEN
        RETURN smart_bi_agent_exact_json_keys(value, ARRAY['requestCode'])
           AND value->>'requestCode' = 'EXPLICIT_SERVER_CANCEL';
    ELSIF kind IN ('STEP_FAILED','RUN_FAILED','RUN_CANCELLED') THEN
        RETURN smart_bi_agent_exact_json_keys(value, ARRAY['failureCode'])
           AND value->>'failureCode' ~ '^[A-Z][A-Z0-9_]{0,95}$';
    ELSIF kind = 'BUDGET_EXCEEDED' THEN
        RETURN smart_bi_agent_exact_json_keys(
            value, ARRAY['failureCode','roundsUsed','toolCallsUsed','factsUsed','evidenceBytesUsed']
        ) AND value->>'failureCode' ~ '^[A-Z][A-Z0-9_]{0,95}$'
          AND value->>'roundsUsed' ~ '^\d+$' AND (value->>'roundsUsed')::INTEGER BETWEEN 0 AND 2
          AND value->>'toolCallsUsed' ~ '^\d+$' AND (value->>'toolCallsUsed')::INTEGER BETWEEN 0 AND 10
          AND value->>'factsUsed' ~ '^\d+$' AND value->>'evidenceBytesUsed' ~ '^\d+$';
    ELSIF kind = 'RUN_COMPLETED' THEN
        RETURN smart_bi_agent_outcome_payload_is_safe(value);
    END IF;
    RETURN FALSE;
EXCEPTION WHEN OTHERS THEN
    RETURN FALSE;
END;
$$;

ALTER TABLE smart_bi_agent_run
    DROP CONSTRAINT IF EXISTS smart_bi_agent_run_outcome_schema;
ALTER TABLE smart_bi_agent_run
    ADD CONSTRAINT smart_bi_agent_run_outcome_schema CHECK (
        outcome_summary IS NULL OR smart_bi_agent_outcome_payload_is_safe(outcome_summary)
    );
ALTER TABLE smart_bi_agent_event
    DROP CONSTRAINT IF EXISTS smart_bi_agent_event_payload_schema;
ALTER TABLE smart_bi_agent_event
    ADD CONSTRAINT smart_bi_agent_event_payload_schema
    CHECK (smart_bi_agent_event_payload_is_safe(event_type, payload));

COMMENT ON FUNCTION smart_bi_agent_event_payload_is_safe(TEXT, JSONB) IS
    'Exact safe Event v1 schemas, including bounded evidence references and durable cancellation.';
