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
  -- The same transaction-scoped 64-bit key is acquired by activation writes.
  -- A hash collision can only over-serialize unrelated products; it cannot mix decisions.
  PERFORM pg_advisory_xact_lock(hashtextextended(
    NEW.factory_id || E'\\x1f' || NEW.product_type_id,
    0
  ));

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
  RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION lock_product_process_workflow_activation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
  lock_factory_id VARCHAR(64);
  lock_product_type_id VARCHAR(64);
BEGIN
  IF TG_OP = 'DELETE' THEN
    lock_factory_id := OLD.factory_id;
    lock_product_type_id := OLD.product_type_id;
  ELSE
    lock_factory_id := NEW.factory_id;
    lock_product_type_id := NEW.product_type_id;
  END IF;

  PERFORM pg_advisory_xact_lock(hashtextextended(
    lock_factory_id || E'\\x1f' || lock_product_type_id,
    0
  ));

  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_lock_product_process_workflow_activation
BEFORE INSERT OR UPDATE OR DELETE ON product_process_workflow_activations
FOR EACH ROW
EXECUTE FUNCTION lock_product_process_workflow_activation();

CREATE TRIGGER trg_pin_production_batch_workflow_selection
BEFORE INSERT ON production_batches
FOR EACH ROW
EXECUTE FUNCTION pin_production_batch_workflow_selection();
