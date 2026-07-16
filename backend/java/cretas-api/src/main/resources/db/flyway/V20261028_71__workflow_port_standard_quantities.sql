ALTER TABLE workflow_task_ports
    ADD COLUMN standard_quantity NUMERIC(20,6),
    ADD COLUMN quantity_mode VARCHAR(24),
    ADD CONSTRAINT ck_workflow_task_port_standard_quantity
        CHECK (standard_quantity IS NULL OR standard_quantity > 0),
    ADD CONSTRAINT ck_workflow_task_port_quantity_mode
        CHECK (quantity_mode IS NULL OR quantity_mode IN ('AUTO_CONVERT', 'FIXED_RATIO'));
