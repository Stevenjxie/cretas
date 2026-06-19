-- E2E 第4轮 (AI 意图路由) 发现的真 bug 修复 — 数据-only, 幂等。
--
-- BUG-1 (业态泄漏, HIGH): MATERIAL_BATCH_WIP_QUERY (在制品/WIP 查询, 制造业专属) 的
--   business_type 为 NULL → BusinessTypeGate 对 intentBiz==null 无条件放行 → 餐饮租户
--   也能路由到这个制造业意图。修: 标 FACTORY (它是工厂专属意图)。
--
-- BUG-2 (业态泄漏): 部分 RESTAURANT% 意图 (如 RESTAURANT_ECONOMICS_ANALYSIS) 在
--   business_type 列存在前被 seed, business_type 为 NULL → 在工厂租户也被识别/路由。
--   修: 所有 business_type 仍为 NULL 的 RESTAURANT% 意图标 RESTAURANT。
--
-- BUG-4 (死路): COST_QUERY 意图的 tool_name 未绑定 (列添加时未 SET) → 识别成功但无
--   执行器, 用户得到无后续动作的死路响应。ReportCostQueryTool.getToolName()=
--   'report_cost_query' 已存在。修: 绑定。
--
-- 仅改 business_type 仍为 NULL / tool_name 仍为空的行 (幂等, 不覆盖已正确配置的行)。
-- BUG-3 (warehouse_manager 不在 MATERIAL_BATCH_QUERY 的 required_roles) 暂不在此处理
--   (required_roles 是 JSON, 需单独谨慎处理)。

-- BUG-1
UPDATE ai_intent_configs
SET business_type = 'FACTORY', updated_at = NOW()
WHERE intent_code = 'MATERIAL_BATCH_WIP_QUERY'
  AND business_type IS NULL;

-- BUG-2 (RESTAURANT 域意图不应为 NULL business_type)
UPDATE ai_intent_configs
SET business_type = 'RESTAURANT', updated_at = NOW()
WHERE intent_code LIKE 'RESTAURANT%'
  AND business_type IS NULL;

-- BUG-4
UPDATE ai_intent_configs
SET tool_name = 'report_cost_query', updated_at = NOW()
WHERE intent_code = 'COST_QUERY'
  AND (tool_name IS NULL OR tool_name = '');
