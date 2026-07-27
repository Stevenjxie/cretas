-- Bind the existing governed restaurant dish-delete intent to its registered
-- high-risk write tool. Natural-language deletion requests still pass through
-- W0 confirmation and a read-only preview before any mutation is possible.
UPDATE ai_intent_configs
SET tool_name = 'restaurant_dish_delete',
    required_permission = 'restaurant:read_write',
    sensitivity_level = 'HIGH',
    requires_approval = TRUE,
    updated_at = NOW()
WHERE intent_code = 'RESTAURANT_DISH_DELETE'
  AND (
      tool_name IS DISTINCT FROM 'restaurant_dish_delete'
      OR required_permission IS DISTINCT FROM 'restaurant:read_write'
      OR sensitivity_level IS DISTINCT FROM 'HIGH'
      OR requires_approval IS DISTINCT FROM TRUE
  );
