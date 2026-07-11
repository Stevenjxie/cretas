ALTER TABLE production_batches
  ADD COLUMN workflow_selection_mode VARCHAR(16) NOT NULL DEFAULT 'LEGACY',
  ADD COLUMN selected_workflow_id BIGINT,
  ADD COLUMN selected_workflow_version INTEGER;

ALTER TABLE production_batches
  ADD CONSTRAINT ck_production_batch_workflow_selection
    CHECK (
      (workflow_selection_mode = 'LEGACY'
        AND selected_workflow_id IS NULL
        AND selected_workflow_version IS NULL)
      OR
      (workflow_selection_mode = 'WORKFLOW'
        AND selected_workflow_id IS NOT NULL
        AND selected_workflow_version IS NOT NULL)
    ),
  ADD CONSTRAINT fk_production_batch_selected_workflow
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
  pinned_workflow_id BIGINT;
  pinned_definition_version INTEGER;
BEGIN
  SELECT activation.active_workflow_id, activation.active_definition_version
    INTO pinned_workflow_id, pinned_definition_version
    FROM product_process_workflow_activations activation
   WHERE activation.factory_id = NEW.factory_id
     AND activation.product_type_id = NEW.product_type_id
     AND activation.enabled = TRUE
     AND activation.deleted_at IS NULL
   FOR SHARE;

  IF FOUND THEN
    NEW.workflow_selection_mode := 'WORKFLOW';
    NEW.selected_workflow_id := pinned_workflow_id;
    NEW.selected_workflow_version := pinned_definition_version;
  ELSE
    NEW.workflow_selection_mode := 'LEGACY';
    NEW.selected_workflow_id := NULL;
    NEW.selected_workflow_version := NULL;
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_pin_production_batch_workflow_selection
BEFORE INSERT ON production_batches
FOR EACH ROW
EXECUTE FUNCTION pin_production_batch_workflow_selection();
