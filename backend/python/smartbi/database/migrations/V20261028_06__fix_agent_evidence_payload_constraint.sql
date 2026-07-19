-- Fix the V03 EVIDENCE_RECORDED validator without weakening any payload rule.
--
-- V03 named the function argument `value` and also selected the `value` column
-- returned by jsonb_each().  PL/pgSQL treats that reference as ambiguous when
-- the dimensions loop is first executed.  The catch-all handler then returns
-- FALSE, so every otherwise-valid evidence drilldown is rejected by the table
-- CHECK constraint.  Preserve the public parameter name for CREATE OR REPLACE,
-- use a local positional alias, and qualify the jsonb_each columns.

CREATE OR REPLACE FUNCTION smart_bi_agent_event_payload_is_safe(
    kind TEXT,
    value JSONB
)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    payload_value ALIAS FOR $2;
    item JSONB;
    code TEXT;
    dimension_key TEXT;
    dimension_value JSONB;
BEGIN
    IF kind IN ('RUN_STARTED','ROUTE_SELECTED') THEN
        RETURN smart_bi_agent_exact_json_keys(payload_value, ARRAY['routeCode'])
           AND payload_value->>'routeCode' = 'GROSS_MARGIN_DECLINE_ATTRIBUTION';
    ELSIF kind = 'PLAN_CREATED' THEN
        RETURN smart_bi_agent_exact_json_keys(
            payload_value, ARRAY['routeCode','stepCount','maxRounds','maxToolCalls']
        )
           AND payload_value->>'routeCode' = 'GROSS_MARGIN_DECLINE_ATTRIBUTION'
           AND payload_value->>'stepCount' ~ '^\d+$'
           AND (payload_value->>'stepCount')::INTEGER BETWEEN 1 AND 10
           AND payload_value->>'maxRounds' ~ '^\d+$'
           AND (payload_value->>'maxRounds')::INTEGER BETWEEN 1 AND 2
           AND payload_value->>'maxToolCalls' ~ '^\d+$'
           AND (payload_value->>'maxToolCalls')::INTEGER BETWEEN 1 AND 10;
    ELSIF kind = 'STEP_STARTED' THEN
        RETURN smart_bi_agent_exact_json_keys(payload_value, ARRAY['round','purposeCode'])
           AND payload_value->>'round' ~ '^[12]$'
           AND payload_value->>'purposeCode' ~ '^[A-Z][A-Z0-9_]{0,95}$';
    ELSIF kind = 'STEP_COMPLETED' THEN
        IF NOT smart_bi_agent_exact_json_keys(
            payload_value,
            ARRAY['round','evidenceId','evidenceStatus','factCount','evidenceBytes','warningCodes']
        ) OR payload_value->>'round' !~ '^[12]$'
          OR payload_value->>'evidenceId' !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
          OR payload_value->>'evidenceStatus' NOT IN (
              'OK','EMPTY','PARTIAL','NOT_COMPUTABLE','CONFLICT','DENIED','ERROR'
          )
          OR payload_value->>'factCount' !~ '^\d+$'
          OR payload_value->>'evidenceBytes' !~ '^\d+$'
          OR jsonb_typeof(payload_value->'warningCodes') <> 'array'
          OR jsonb_array_length(payload_value->'warningCodes') > 100 THEN
            RETURN FALSE;
        END IF;
        FOR code IN SELECT jsonb_array_elements_text(payload_value->'warningCodes') LOOP
            IF code !~ '^[A-Z][A-Z0-9_]{0,95}$' THEN RETURN FALSE; END IF;
        END LOOP;
        RETURN TRUE;
    ELSIF kind = 'EVIDENCE_RECORDED' THEN
        IF NOT smart_bi_agent_exact_json_keys(
            payload_value,
            ARRAY[
                'evidenceId','evidenceStatus','factReferences','provenance',
                'warningCodes','drilldownTruncated'
            ]
        ) OR payload_value->>'evidenceId' !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
          OR payload_value->>'evidenceStatus' NOT IN (
              'OK','EMPTY','PARTIAL','NOT_COMPUTABLE','CONFLICT','DENIED','ERROR'
          )
          OR jsonb_typeof(payload_value->'factReferences') <> 'array'
          OR jsonb_array_length(payload_value->'factReferences') > 100
          OR jsonb_typeof(payload_value->'provenance') <> 'array'
          OR jsonb_array_length(payload_value->'provenance') > 100
          OR jsonb_typeof(payload_value->'warningCodes') <> 'array'
          OR jsonb_array_length(payload_value->'warningCodes') > 100
          OR jsonb_typeof(payload_value->'drilldownTruncated') <> 'boolean' THEN
            RETURN FALSE;
        END IF;
        FOR code IN SELECT jsonb_array_elements_text(payload_value->'warningCodes') LOOP
            IF code !~ '^[A-Z][A-Z0-9_]{0,95}$' THEN RETURN FALSE; END IF;
        END LOOP;
        FOR item IN
            SELECT fact_row.element
            FROM jsonb_array_elements(payload_value->'factReferences') AS fact_row(element)
        LOOP
            IF NOT smart_bi_agent_exact_json_keys(
                item, ARRAY['factId','metric','value','unit','dimensions','provenanceRefs']
            ) OR item->>'factId' !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
              OR item->>'metric' !~ '^[A-Za-z][A-Za-z0-9_]{0,95}$'
              OR item->>'value' !~ '^-?(0|[1-9]\d*)(\.\d+)?$'
              OR jsonb_typeof(item->'dimensions') <> 'object'
              OR (SELECT COUNT(*) FROM jsonb_object_keys(item->'dimensions')) > 20
              OR jsonb_typeof(item->'provenanceRefs') <> 'array'
              OR jsonb_array_length(item->'provenanceRefs') NOT BETWEEN 1 AND 20 THEN
                RETURN FALSE;
            END IF;
            IF item->'unit' <> 'null'::jsonb
               AND item->>'unit' !~ '^[A-Z][A-Z0-9_]{0,95}$' THEN
                RETURN FALSE;
            END IF;
            FOR dimension_key, dimension_value IN
                SELECT dimension_row.key, dimension_row.value
                FROM jsonb_each(item->'dimensions') AS dimension_row(key, value)
            LOOP
                IF dimension_key !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
                   OR jsonb_typeof(dimension_value) <> 'string'
                   OR length(dimension_value #>> '{}') NOT BETWEEN 1 AND 256
                   OR (dimension_value #>> '{}') ~ '[[:cntrl:]]' THEN
                    RETURN FALSE;
                END IF;
            END LOOP;
            FOR code IN SELECT jsonb_array_elements_text(item->'provenanceRefs') LOOP
                IF code !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$' THEN
                    RETURN FALSE;
                END IF;
            END LOOP;
        END LOOP;
        FOR item IN
            SELECT provenance_row.element
            FROM jsonb_array_elements(payload_value->'provenance') AS provenance_row(element)
        LOOP
            IF NOT smart_bi_agent_exact_json_keys(
                item, ARRAY['refId','sourceType','asset','queryId','sourceVersion']
            )
               OR item->>'refId' !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
               OR item->>'sourceType' !~ '^[A-Z][A-Z0-9_]{0,95}$'
               OR length(item->>'asset') NOT BETWEEN 1 AND 256
               OR item->>'asset' ~ '[[:cntrl:]]'
               OR item->>'queryId' !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
               OR item->>'sourceVersion' !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$' THEN
                RETURN FALSE;
            END IF;
        END LOOP;
        RETURN TRUE;
    ELSIF kind = 'EVIDENCE_GAP' THEN
        IF NOT smart_bi_agent_exact_json_keys(
            payload_value, ARRAY['round','gapCodes','resolvable']
        ) OR payload_value->>'round' !~ '^[12]$'
          OR jsonb_typeof(payload_value->'gapCodes') <> 'array'
          OR jsonb_array_length(payload_value->'gapCodes') > 100
          OR jsonb_typeof(payload_value->'resolvable') <> 'boolean' THEN
            RETURN FALSE;
        END IF;
        FOR code IN SELECT jsonb_array_elements_text(payload_value->'gapCodes') LOOP
            IF code !~ '^[A-Z][A-Z0-9_]{0,95}$' THEN RETURN FALSE; END IF;
        END LOOP;
        RETURN TRUE;
    ELSIF kind = 'REPLAN' THEN
        IF NOT smart_bi_agent_exact_json_keys(
            payload_value, ARRAY['fromRound','toRound','gapCodes','stepIds']
        ) OR payload_value->>'fromRound' <> '1'
          OR payload_value->>'toRound' <> '2'
          OR jsonb_typeof(payload_value->'gapCodes') <> 'array'
          OR jsonb_array_length(payload_value->'gapCodes') > 100
          OR jsonb_typeof(payload_value->'stepIds') <> 'array'
          OR jsonb_array_length(payload_value->'stepIds') > 10 THEN
            RETURN FALSE;
        END IF;
        FOR code IN SELECT jsonb_array_elements_text(payload_value->'gapCodes') LOOP
            IF code !~ '^[A-Z][A-Z0-9_]{0,95}$' THEN RETURN FALSE; END IF;
        END LOOP;
        FOR code IN SELECT jsonb_array_elements_text(payload_value->'stepIds') LOOP
            IF code !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$' THEN RETURN FALSE; END IF;
        END LOOP;
        RETURN TRUE;
    ELSIF kind = 'CLARIFICATION' THEN
        IF NOT smart_bi_agent_exact_json_keys(
            payload_value, ARRAY['clarificationCode','gapCodes']
        ) OR payload_value->>'clarificationCode' !~ '^[A-Z][A-Z0-9_]{0,95}$'
          OR jsonb_typeof(payload_value->'gapCodes') <> 'array'
          OR jsonb_array_length(payload_value->'gapCodes') > 100 THEN
            RETURN FALSE;
        END IF;
        FOR code IN SELECT jsonb_array_elements_text(payload_value->'gapCodes') LOOP
            IF code !~ '^[A-Z][A-Z0-9_]{0,95}$' THEN RETURN FALSE; END IF;
        END LOOP;
        RETURN TRUE;
    ELSIF kind = 'CANCEL_REQUESTED' THEN
        RETURN smart_bi_agent_exact_json_keys(payload_value, ARRAY['requestCode'])
           AND payload_value->>'requestCode' = 'EXPLICIT_SERVER_CANCEL';
    ELSIF kind IN ('STEP_FAILED','RUN_FAILED','RUN_CANCELLED') THEN
        RETURN smart_bi_agent_exact_json_keys(payload_value, ARRAY['failureCode'])
           AND payload_value->>'failureCode' ~ '^[A-Z][A-Z0-9_]{0,95}$';
    ELSIF kind = 'BUDGET_EXCEEDED' THEN
        RETURN smart_bi_agent_exact_json_keys(
            payload_value,
            ARRAY['failureCode','roundsUsed','toolCallsUsed','factsUsed','evidenceBytesUsed']
        ) AND payload_value->>'failureCode' ~ '^[A-Z][A-Z0-9_]{0,95}$'
          AND payload_value->>'roundsUsed' ~ '^\d+$'
          AND (payload_value->>'roundsUsed')::INTEGER BETWEEN 0 AND 2
          AND payload_value->>'toolCallsUsed' ~ '^\d+$'
          AND (payload_value->>'toolCallsUsed')::INTEGER BETWEEN 0 AND 10
          AND payload_value->>'factsUsed' ~ '^\d+$'
          AND payload_value->>'evidenceBytesUsed' ~ '^\d+$';
    ELSIF kind = 'RUN_COMPLETED' THEN
        RETURN smart_bi_agent_outcome_payload_is_safe(payload_value);
    END IF;
    RETURN FALSE;
EXCEPTION WHEN OTHERS THEN
    RETURN FALSE;
END;
$$;

COMMENT ON FUNCTION smart_bi_agent_event_payload_is_safe(TEXT, JSONB) IS
    'Exact safe Event v1 schemas; V06 qualifies jsonb_each values so valid evidence drilldowns pass.';
