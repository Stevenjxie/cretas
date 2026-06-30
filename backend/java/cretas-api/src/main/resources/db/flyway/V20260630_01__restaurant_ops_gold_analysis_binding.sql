-- Bind restaurant ops intents to the Gold-backed analysis bridge.
-- Keep this migration ASCII-only because this repository is often edited from
-- Windows shells with mixed console encodings.

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active, created_at, updated_at
) VALUES
    (gen_random_uuid(), 'RESTAURANT_OPS_WASTAGE_TOP', 'Restaurant ops wastage analysis', 'SMARTBI', 'restaurant_ops_gold_analysis',
     'LOW', '["wastage","loss","spoilage","reason","ranking"]',
     'Analyze restaurant wastage amount, reason mix, and corrective actions from Gold ops tables.', 95, true, NOW(), NOW()),
    (gen_random_uuid(), 'RESTAURANT_OPS_STOCK_SHORTAGE', 'Restaurant ops stock shortage analysis', 'SMARTBI', 'restaurant_ops_gold_analysis',
     'LOW', '["stocktaking","shortage","inventory variance","stock loss"]',
     'Analyze stocktaking shortages, inventory variance hotspots, and recovery actions from Gold ops tables.', 95, true, NOW(), NOW()),
    (gen_random_uuid(), 'RESTAURANT_OPS_REQUISITION_TREND', 'Restaurant ops requisition trend analysis', 'SMARTBI', 'restaurant_ops_gold_analysis',
     'LOW', '["requisition","ingredient usage","material usage","trend"]',
     'Analyze requisition cost trend, ingredient usage, and control actions from Gold ops tables.', 95, true, NOW(), NOW()),
    (gen_random_uuid(), 'RESTAURANT_OPS_RECIPE_COST', 'Restaurant ops recipe cost analysis', 'SMARTBI', 'restaurant_ops_gold_analysis',
     'LOW', '["recipe cost","dish cost","ingredient cost"]',
     'Analyze dish ingredient cost, cost coverage, and margin actions from Gold ops tables.', 95, true, NOW(), NOW()),
    (gen_random_uuid(), 'RESTAURANT_OPS_GROSS_MARGIN', 'Restaurant ops gross margin analysis', 'SMARTBI', 'restaurant_ops_gold_analysis',
     'LOW', '["gross margin","margin rate","profit","dish"]',
     'Analyze dish gross margin, missing cost coverage, and pricing or cost-control actions from Gold ops tables.', 95, true, NOW(), NOW()),
    (gen_random_uuid(), 'RESTAURANT_OPS_STORE_MARGIN', 'Restaurant ops store margin analysis', 'SMARTBI', 'restaurant_ops_gold_analysis',
     'LOW', '["store","branch","gross margin","profit"]',
     'Analyze store margin differences and store-level actions from Gold ops tables.', 95, true, NOW(), NOW()),
    (gen_random_uuid(), 'RESTAURANT_OPS_SALES_SUMMARY', 'Restaurant ops sales summary', 'SMARTBI', 'restaurant_ops_gold_analysis',
     'LOW', '["revenue","sales","avg bill","orders","stores"]',
     'Analyze restaurant revenue, avg bill, order count, store gap, and operating actions from Gold ops tables.', 95, true, NOW(), NOW()),
    (gen_random_uuid(), 'RESTAURANT_OPS_TREND_ANALYSIS', 'Restaurant ops trend analysis', 'SMARTBI', 'restaurant_ops_gold_analysis',
     'LOW', '["yoy","mom","trend","growth","decline","monthly"]',
     'Analyze monthly trend, year-over-year and month-over-month changes, and response actions from Gold ops tables.', 95, true, NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET
    tool_name = EXCLUDED.tool_name,
    intent_name = EXCLUDED.intent_name,
    intent_category = EXCLUDED.intent_category,
    sensitivity_level = EXCLUDED.sensitivity_level,
    keywords = EXCLUDED.keywords,
    description = EXCLUDED.description,
    priority = EXCLUDED.priority,
    is_active = true,
    updated_at = NOW();
