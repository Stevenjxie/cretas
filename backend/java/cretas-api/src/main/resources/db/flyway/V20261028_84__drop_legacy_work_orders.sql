-- WO-01: remove the empty generic work-order shadow model.
-- Production read-only preflight on 2026-07-19:
--   work_orders=0 rows, sales_orders=490 rows, purchase_orders=256 rows,
--   work_process_tasks=55 rows, no external FK, no view, one table-local update trigger.
-- DROP intentionally omits CASCADE so an unexpected dependency aborts deployment.

-- ORDER_TODAY was incorrectly sharing order_list. Route it to the canonical
-- sales_orders-backed implementation added in the same release.
UPDATE ai_intent_configs
SET tool_name = 'order_today', updated_at = CURRENT_TIMESTAMP
WHERE intent_code = 'ORDER_TODAY'
  AND is_active = TRUE;

-- These generic tools only wrote/read work_orders and have no production binding.
-- Freeze any environment-local binding before removing their implementations.
UPDATE ai_intent_configs
SET is_active = FALSE, tool_name = NULL, updated_at = CURRENT_TIMESTAMP
WHERE tool_name IN (
    'order_create',
    'order_stats',
    'order_update',
    'report_task_assign_worker',
    'todo_list'
);

DROP TABLE IF EXISTS work_orders;
