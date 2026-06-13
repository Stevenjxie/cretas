-- N9: correct F006 sales order approval threshold seed.
-- The threshold remains configurable in approval_chain_configs.trigger_condition.
INSERT INTO approval_chain_configs (
    id,
    factory_id,
    decision_type,
    name,
    description,
    trigger_condition,
    approval_level,
    required_approvers,
    approver_roles,
    approver_user_ids,
    timeout_minutes,
    escalation_config_id,
    auto_approve_condition,
    auto_reject_condition,
    priority,
    enabled,
    version,
    created_at,
    updated_at,
    deleted_at
)
VALUES (
    gen_random_uuid()::text,
    'F006',
    'SALES_ORDER_APPROVAL',
    'F006 sales amount threshold',
    'Sales orders with amount greater than 5000 require finance review; external-channel orders are exempt.',
    '{"amount": ">5000"}',
    1,
    1,
    '["finance_manager", "factory_super_admin"]',
    NULL,
    120,
    NULL,
    '{"externalOrder": true}',
    NULL,
    100,
    TRUE,
    1,
    NOW(),
    NOW(),
    NULL
)
ON CONFLICT (factory_id, decision_type, name) DO UPDATE
SET
    description = EXCLUDED.description,
    trigger_condition = EXCLUDED.trigger_condition,
    approval_level = EXCLUDED.approval_level,
    required_approvers = EXCLUDED.required_approvers,
    approver_roles = EXCLUDED.approver_roles,
    approver_user_ids = NULL,
    auto_approve_condition = EXCLUDED.auto_approve_condition,
    timeout_minutes = EXCLUDED.timeout_minutes,
    priority = EXCLUDED.priority,
    enabled = TRUE,
    updated_at = NOW();
