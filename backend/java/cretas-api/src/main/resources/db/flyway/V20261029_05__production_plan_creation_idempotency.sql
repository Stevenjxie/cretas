ALTER TABLE production_plans
    ADD COLUMN IF NOT EXISTS client_request_id VARCHAR(128);

CREATE UNIQUE INDEX IF NOT EXISTS uk_production_plan_create_request
    ON production_plans (factory_id, created_by, client_request_id)
    WHERE client_request_id IS NOT NULL;
