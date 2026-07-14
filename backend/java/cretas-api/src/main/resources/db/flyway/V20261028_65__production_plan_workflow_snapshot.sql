ALTER TABLE production_plans
  ADD COLUMN workflow_selection_mode VARCHAR(16),
  ADD COLUMN selected_workflow_id BIGINT,
  ADD COLUMN selected_workflow_version INTEGER;

ALTER TABLE production_plans
  ADD CONSTRAINT ck_production_plan_workflow_selection
    CHECK (
      (workflow_selection_mode IS NULL
        AND selected_workflow_id IS NULL
        AND selected_workflow_version IS NULL)
      OR
      (workflow_selection_mode = 'LEGACY'
        AND selected_workflow_id IS NULL
        AND selected_workflow_version IS NULL)
      OR
      (workflow_selection_mode = 'WORKFLOW'
        AND selected_workflow_id IS NOT NULL
        AND selected_workflow_version IS NOT NULL)
    ),
  ADD CONSTRAINT fk_production_plan_selected_workflow
    FOREIGN KEY (
      selected_workflow_id,
      factory_id,
      product_type_id,
      selected_workflow_version
    )
    REFERENCES product_process_workflows(
      id,
      factory_id,
      product_type_id,
      definition_version
    );

CREATE OR REPLACE FUNCTION pin_production_batch_workflow_selection()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
  plan_mode VARCHAR(16);
  plan_workflow_id BIGINT;
  plan_workflow_version INTEGER;
  pinned_workflow_id BIGINT;
  pinned_definition_version INTEGER;
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended(
    NEW.factory_id || E'\\x1f' || NEW.product_type_id,
    0
  ));

  IF NEW.production_plan_id IS NOT NULL THEN
    SELECT workflow_selection_mode, selected_workflow_id, selected_workflow_version
      INTO plan_mode, plan_workflow_id, plan_workflow_version
      FROM production_plans
     WHERE id = NEW.production_plan_id
       AND factory_id = NEW.factory_id;
  END IF;

  IF plan_mode = 'WORKFLOW' THEN
    NEW.workflow_selection_mode := 'WORKFLOW';
    NEW.selected_workflow_id := plan_workflow_id;
    NEW.selected_workflow_version := plan_workflow_version;
  ELSIF plan_mode = 'LEGACY' THEN
    NEW.workflow_selection_mode := 'LEGACY';
    NEW.selected_workflow_id := NULL;
    NEW.selected_workflow_version := NULL;
  ELSE
    -- Historical plans without a snapshot retain the pre-V65 creation-time behavior.
    SELECT activation.active_workflow_id, activation.active_definition_version
      INTO pinned_workflow_id, pinned_definition_version
      FROM product_process_workflow_activations activation
     WHERE activation.factory_id = NEW.factory_id
       AND activation.product_type_id = NEW.product_type_id
       AND activation.enabled = TRUE
       AND activation.deleted_at IS NULL;

    IF FOUND THEN
      NEW.workflow_selection_mode := 'WORKFLOW';
      NEW.selected_workflow_id := pinned_workflow_id;
      NEW.selected_workflow_version := pinned_definition_version;
    ELSE
      NEW.workflow_selection_mode := 'LEGACY';
      NEW.selected_workflow_id := NULL;
      NEW.selected_workflow_version := NULL;
    END IF;
  END IF;
  RETURN NEW;
END;
$$;
