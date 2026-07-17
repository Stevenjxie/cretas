ALTER TABLE product_process_workflows
    DROP CONSTRAINT IF EXISTS ck_product_process_workflow_status;

ALTER TABLE product_process_workflows
    ADD CONSTRAINT ck_product_process_workflow_status
        CHECK (status IN ('DRAFT', 'SNAPSHOT', 'PUBLISHED'));

COMMENT ON COLUMN product_process_workflows.status IS
    'DRAFT=current editable row; SNAPSHOT=manual immutable version; PUBLISHED=released version';
