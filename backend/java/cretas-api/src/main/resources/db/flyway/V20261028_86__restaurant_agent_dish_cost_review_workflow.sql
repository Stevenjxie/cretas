-- One allowlisted Restaurant Agent proposal may start this human-only approval workflow.
-- Approval does not invoke an ERP writer, Tool, listener or applier. The application maps an
-- APPROVED instance to the fixed navigation target /restaurant/recipes and nothing else.

INSERT INTO approval_workflows (
    id, factory_id, decision_type, name, description,
    nodes_json, edges_json, start_node_id,
    version, publish_status, enabled, priority,
    created_at, updated_at
)
SELECT
    md5('restaurant.dish-cost-data-review.v1:' || f.id),
    f.id,
    'RESTAURANT_AGENT_ACTION_REVIEW',
    'restaurant.dish-cost-data-review.v1',
    'Human review of missing dish cost data. Approval only unlocks navigation to the recipe data page.',
    $json$
    [
      {"id":"start","type":"start","label":"Submit review","position":{"x":60,"y":120},"config":{}},
      {"id":"human_review","type":"approval","label":"Review dish cost data","position":{"x":320,"y":120},"config":{"approverRoles":["restaurant_owner","restaurant_manager","finance_manager"],"requiredApprovers":1,"timeoutMinutes":1440}},
      {"id":"approved","type":"end","label":"Approved","position":{"x":600,"y":120},"config":{"outcome":"APPROVED"}}
    ]
    $json$::jsonb,
    $json$
    [
      {"id":"start_review","source":"start","target":"human_review","priority":0},
      {"id":"review_approved","source":"human_review","target":"approved","priority":0}
    ]
    $json$::jsonb,
    'start',
    1, 'published', TRUE, 1000,
    NOW(), NOW()
FROM factories f
WHERE f.type IN ('RESTAURANT', 'BRANCH')
  AND f.is_active = TRUE
ON CONFLICT (factory_id, decision_type, name) DO NOTHING;
