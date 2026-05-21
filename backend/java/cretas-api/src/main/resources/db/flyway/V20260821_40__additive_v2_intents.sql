-- V20260821_40__additive_v2_intents.sql
--
-- Sprint 9 P2.F 食品行业法定 — 添加剂限量 V2 智能化 Tool 意图注册 (2026-05-21)
--
-- 配套 V20260821_39 (additive_limits_v2_seed: 跨类目 50+ entries + aliases JSONB).
-- 配套 V1 V20260820_07 (sprint8_p3_food_safety_intents: 已注册 ADDITIVE_CHECK).
--
-- 新增 2 Tool intents:
--   ADDITIVE_SMART_MATCH         → additive_smart_match (按俗名/英文/INS模糊 → GB 2760 entry)
--   ADDITIVE_BOM_COMPLIANCE      → additive_bom_compliance_check (BOM 反查 + 跨 ingredient 综合 report)
--
-- Pattern mirrors V20260820_07. ON CONFLICT DO UPDATE for idempotent re-run.

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    created_at, updated_at
)
VALUES
    (gen_random_uuid(), 'ADDITIVE_SMART_MATCH', '添加剂智能匹配', 'QUERY',
     'additive_smart_match', 'LOW',
     '["智能匹配","查添加剂","这个添加剂","在系统里","VC 是什么","MSG 是什么","Sodium Benzoate","Aspartame","查 INS","INS 代码","俗名","英文名","查 山梨酸钾","识别这个添加剂"]'::jsonb,
     'Sprint 9 P2.F V2 — GB 2760-2014 添加剂智能匹配 (Tool 直接执行). ' ||
     '用户输入俗名/英文/化学名/INS code 不全 → 自动找 GB 2760 entry. ' ||
     '5 级匹配策略: 精确 code → 精确 name → aliases → name LIKE → Levenshtein top-3. ' ||
     '跨食品类目聚合 (一个 additive 多个类目). read-only. ' ||
     'Cretas vs HJ 食品垂直差异化护城河 +1.',
     80, true, NOW(), NOW()),

    (gen_random_uuid(), 'ADDITIVE_BOM_COMPLIANCE', 'BOM 添加剂综合校验', 'ANALYSIS',
     'additive_bom_compliance_check', 'LOW',
     '["BOM 合规","配方添加剂","BOM 添加剂","配方超限","X 产品 BOM 合规","查 BOM 添加剂","BOM 检查添加剂","配方违法添加剂","P-X 配方合规","卤猪蹄 BOM","产品 BOM 添加剂"]'::jsonb,
     'Sprint 9 P2.F V2 — BOM 反查 + GB 2760-2014 综合合规校验. ' ||
     '操作员只需提供 productTypeId / bomRecipeId, Tool 自动: ' ||
     '查 BomRecipe → 遍历 items → fuzzy match GB 2760 → 算 perKgUsage → ' ||
     '0-80% green / 80-100% yellow / >100% red 综合 report. ' ||
     '防呆: 含 productName + recipeCode + outputQty 上下文, 找不到 BOM 提示去配置页. read-only.',
     80, true, NOW(), NOW())

ON CONFLICT (intent_code) DO UPDATE SET
    tool_name = EXCLUDED.tool_name,
    intent_name = EXCLUDED.intent_name,
    intent_category = EXCLUDED.intent_category,
    sensitivity_level = EXCLUDED.sensitivity_level,
    keywords = EXCLUDED.keywords,
    description = EXCLUDED.description,
    priority = EXCLUDED.priority,
    is_active = EXCLUDED.is_active,
    updated_at = NOW();
