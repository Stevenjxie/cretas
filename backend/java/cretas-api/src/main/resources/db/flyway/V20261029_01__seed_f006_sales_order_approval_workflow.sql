-- F006 sales orders must use the same persisted OA workflow infrastructure as
-- purchase orders.  The graph preserves the existing business policy:
-- external-channel orders and orders up to CNY 5,000 are auto-approved;
-- higher-value orders require finance approval.
--
-- Every path still starts WorkflowEngine, so even an automatic outcome has a
-- durable instance and audit trail in "我发起的".  Existing business records
-- are deliberately not backfilled or rewritten.

INSERT INTO approval_workflows (
    id, factory_id, decision_type, name, description,
    nodes_json, edges_json, start_node_id,
    version, publish_status, enabled, priority,
    created_at, updated_at
)
SELECT
    'awf-f006-so-default-v1',
    'F006',
    'SALES_ORDER_APPROVAL',
    '销售订单默认审批（5000元阈值）',
    'F006 销售订单统一 OA：外部渠道及金额不超过5000元自动通过，金额超过5000元由财务审批',
    '[
      {"id":"start_1","type":"start","label":"开始","position":{"x":40,"y":160},"config":{}},
      {"id":"cond_external","type":"condition","label":"外部渠道判断","position":{"x":220,"y":160},"config":{"description":"外部渠道订单免人工审批"}},
      {"id":"cond_amount","type":"condition","label":"金额判断","position":{"x":430,"y":160},"config":{"description":"5000元审批阈值"}},
      {"id":"approval_finance","type":"approval","label":"财务审批","position":{"x":650,"y":80},"config":{"approverRoles":["finance_manager","factory_super_admin"],"requiredApprovers":1,"timeoutMinutes":120}},
      {"id":"end_approved","type":"end","label":"审批通过","position":{"x":880,"y":80},"config":{"outcome":"APPROVED"}},
      {"id":"end_auto","type":"end","label":"自动通过","position":{"x":650,"y":260},"config":{"outcome":"APPROVED"}},
      {"id":"end_rejected","type":"end","label":"审批驳回","position":{"x":880,"y":200},"config":{"outcome":"REJECTED"}}
    ]'::jsonb,
    '[
      {"id":"e_start_external","source":"start_1","target":"cond_external","condition":null,"label":null,"priority":0},
      {"id":"e_external_auto","source":"cond_external","target":"end_auto","condition":"#externalOrder == true","label":"外部渠道","priority":0},
      {"id":"e_external_amount","source":"cond_external","target":"cond_amount","condition":null,"label":"DEFAULT","priority":99},
      {"id":"e_amount_finance","source":"cond_amount","target":"approval_finance","condition":"#amount > 5000","label":"超过5000元","priority":0},
      {"id":"e_amount_auto","source":"cond_amount","target":"end_auto","condition":null,"label":"DEFAULT","priority":99},
      {"id":"e_finance_approved","source":"approval_finance","target":"end_approved","condition":"#decision == ''APPROVE''","label":"通过","priority":0},
      {"id":"e_finance_rejected","source":"approval_finance","target":"end_rejected","condition":"#decision == ''REJECT''","label":"驳回","priority":1}
    ]'::jsonb,
    'start_1',
    1, 'published', TRUE, 0,
    NOW(), NOW()
WHERE NOT EXISTS (
    SELECT 1
    FROM approval_workflows existing
    WHERE existing.factory_id = 'F006'
      AND existing.decision_type = 'SALES_ORDER_APPROVAL'
      AND existing.publish_status = 'published'
      AND existing.enabled = TRUE
)
ON CONFLICT (factory_id, decision_type, name) DO NOTHING;
