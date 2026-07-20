-- Align immutable AgentOps experiment storage with the bounded runtime-shadow
-- operation already exposed by the Python service. Existing RUN/RERUN rows
-- retain their original limits; only RUNTIME_SHADOW receives the wider timeout.

ALTER TABLE smart_bi_agent_experiment
    ALTER COLUMN operation_kind TYPE VARCHAR(32);

-- V04 used anonymous CHECK constraints. Remove only the checks whose
-- definitions are being replaced below so the migration remains independent
-- of PostgreSQL's generated constraint names.
DO $$
DECLARE
    constraint_row RECORD;
BEGIN
    FOR constraint_row IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'smart_bi_agent_experiment'::regclass
          AND contype = 'c'
          AND (
              pg_get_constraintdef(oid) ILIKE '%operation_kind%'
              OR pg_get_constraintdef(oid)
                  ILIKE '%smart_bi_agentops_bounds_are_safe(runner_bounds)%'
          )
    LOOP
        EXECUTE format(
            'ALTER TABLE smart_bi_agent_experiment DROP CONSTRAINT %I',
            constraint_row.conname
        );
    END LOOP;
END;
$$;

CREATE OR REPLACE FUNCTION smart_bi_agentops_shadow_bounds_are_safe(value JSONB)
RETURNS BOOLEAN LANGUAGE sql IMMUTABLE AS $$
    SELECT jsonb_typeof(value) = 'object'
       AND (SELECT COUNT(*) FROM jsonb_object_keys(value)) = 3
       AND value ?& ARRAY['maxCases','maxConcurrency','perCaseTimeoutMs']
       AND value->>'maxCases' ~ '^\d+$'
       AND value->>'maxConcurrency' ~ '^\d+$'
       AND value->>'perCaseTimeoutMs' ~ '^\d+$'
       AND (value->>'maxCases')::INTEGER BETWEEN 1 AND 20
       AND (value->>'maxConcurrency')::INTEGER BETWEEN 1 AND 2
       AND (value->>'perCaseTimeoutMs')::INTEGER BETWEEN 1000 AND 75000
$$;

ALTER TABLE smart_bi_agent_experiment
    ADD CONSTRAINT smart_bi_agent_experiment_operation_kind_check
        CHECK (operation_kind IN ('RUN', 'RERUN', 'RUNTIME_SHADOW')),
    ADD CONSTRAINT smart_bi_agent_experiment_operation_source_check
        CHECK (
            (operation_kind = 'RUN' AND source_experiment_id IS NULL)
            OR (operation_kind = 'RERUN' AND source_experiment_id IS NOT NULL)
            OR (
                operation_kind = 'RUNTIME_SHADOW'
                AND source_experiment_id IS NULL
            )
        ),
    ADD CONSTRAINT smart_bi_agent_experiment_runner_bounds_check
        CHECK (
            (
                operation_kind IN ('RUN', 'RERUN')
                AND smart_bi_agentops_bounds_are_safe(runner_bounds)
            )
            OR (
                operation_kind = 'RUNTIME_SHADOW'
                AND smart_bi_agentops_shadow_bounds_are_safe(runner_bounds)
            )
        );

COMMENT ON FUNCTION smart_bi_agentops_shadow_bounds_are_safe(JSONB) IS
    'Validates bounded Runtime Shadow batches: 20 cases, concurrency 2, timeout 1-75 seconds.';
