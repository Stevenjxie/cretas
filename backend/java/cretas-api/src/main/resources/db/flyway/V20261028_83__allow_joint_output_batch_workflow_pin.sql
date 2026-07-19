-- A production plan pins one exact Workflow, but a joint-production report can
-- materialize several finished-good batches. Those output SKUs need not equal
-- the Workflow owner SKU, so the batch FK must protect Workflow tenant/version
-- identity without incorrectly coupling it to the output batch product.

ALTER TABLE product_process_workflows
  ADD CONSTRAINT uk_ppw_id_factory_version
    UNIQUE (id, factory_id, definition_version);

ALTER TABLE production_batches
  DROP CONSTRAINT fk_production_batch_selected_workflow,
  ADD CONSTRAINT fk_production_batch_selected_workflow
    FOREIGN KEY (
      selected_workflow_id,
      factory_id,
      selected_workflow_version
    )
    REFERENCES product_process_workflows(
      id,
      factory_id,
      definition_version
    );
