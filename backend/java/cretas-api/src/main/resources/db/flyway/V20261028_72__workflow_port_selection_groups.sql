ALTER TABLE workflow_task_ports
    ADD COLUMN selection_group_id VARCHAR(128),
    ADD COLUMN selection_group_label VARCHAR(255),
    ADD COLUMN selection_group_mode VARCHAR(32),
    ADD COLUMN selection_group_min_selections INTEGER,
    ADD COLUMN selection_group_max_selections INTEGER,
    ADD CONSTRAINT ck_workflow_task_port_selection_group_mode
        CHECK (selection_group_mode IS NULL OR selection_group_mode IN (
            'ALL_REQUIRED', 'EXACTLY_ONE', 'AT_LEAST_ONE', 'OPTIONAL')),
    ADD CONSTRAINT ck_workflow_task_port_selection_group_bounds
        CHECK (
            (selection_group_id IS NULL
                AND selection_group_label IS NULL
                AND selection_group_mode IS NULL
                AND selection_group_min_selections IS NULL
                AND selection_group_max_selections IS NULL)
            OR
            (selection_group_id IS NOT NULL
                AND selection_group_label IS NOT NULL
                AND selection_group_mode IS NOT NULL
                AND selection_group_min_selections IS NOT NULL
                AND selection_group_max_selections IS NOT NULL
                AND selection_group_min_selections >= 0
                AND selection_group_max_selections >= selection_group_min_selections)
        );
