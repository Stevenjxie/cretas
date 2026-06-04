-- #57 成本卡/出菜反推 — RESTAURANT_DISH_COST_QUERY 意图 → restaurant_dish_cost_query 工具
-- (2026-06-04)
--
-- 背景: 旧 STUB RestaurantDishCostAnalysisTool 只返菜品/食材计数 + "精确成本需配置 BOM"
--   占位。#57 落地真成本卡 (逐料食材成本拆解 + 毛利率), 通过两个工具暴露:
--     1) restaurant_dish_cost_query  — 按菜名查单道菜成本卡 (新意图, 本迁移注册)
--     2) restaurant_dish_cost_analysis — 全菜品低毛利概览 (已存在意图, 仅升级工具实现)
--
-- flyway 版本 20260920.01: origin/main 已应用 max 20260919.01 (out-of-order=false,
--   必须更大)。部署前已 collision-check (无 V20260920 / V20260921 占用)。
-- 幂等: ON CONFLICT (intent_code) DO UPDATE, 可重复执行。
-- business_type='RESTAURANT' 让业态门控放行 (制造业态工厂不进此意图)。
-- keywords ::jsonb cast 与既有迁移一致 (keywords 列跨环境 text/json/jsonb 不一致)。
-- ⚠️ 关键词避开"怎么样"形式 (已知歧义查询局限)。
-- sensitivity_level=LOW: 工具内部已 fail-closed 价权门控金额, 读路径不写库。

INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_DISH_COST_QUERY', '菜品成本卡查询', 'SMARTBI', 'restaurant_dish_cost_query', 'LOW',
        '["这道菜成本多少","菜品成本卡","成本卡","食材成本","这道菜毛利","毛利率多少","食材成本占比","出菜反推","菜品成本拆解","这个菜成本","算一下成本","这道菜的成本","菜的毛利"]'::jsonb,
        '菜品成本卡查询 (按菜名): 逐料食材成本拆解、食材总成本、净料率、毛利率 (出菜反推)。需该菜品已配置配方 (BOM); 未配置则提示去配方管理录入。金额对无价权角色 fail-closed 隐藏 (只显毛利率与配方信息)。适用 "这道菜成本多少 / 毛利率多少 / 食材成本占比" 类问题。',
        110, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET
    tool_name = 'restaurant_dish_cost_query',
    intent_category = 'SMARTBI',
    keywords = EXCLUDED.keywords,
    description = EXCLUDED.description,
    priority = 110,
    is_active = true,
    business_type = 'RESTAURANT',
    updated_at = NOW();

-- 既有 RESTAURANT_DISH_COST_ANALYSIS 意图保持绑定 restaurant_dish_cost_analysis 工具
-- (工具实现 #57 已从 STUB 升级为真成本, 无需改绑定; 防御性确保 tool_name 正确 +
--  business_type 已是 RESTAURANT, 不覆盖其它已有字段)。
UPDATE ai_intent_configs
   SET tool_name = 'restaurant_dish_cost_analysis',
       updated_at = NOW()
 WHERE intent_code = 'RESTAURANT_DISH_COST_ANALYSIS'
   AND (tool_name IS NULL OR tool_name = '' OR tool_name = 'restaurant_dish_cost_analysis');
