-- P2 综合分析意图 → 多维综合分析 (评价+经营+销售合成) Java tool (qhj, 2026-06-02)
--
-- 背景: P2 在 P1 评价深度层 (V20260903_01 / V20260904_01) 之上补"综合/多维"自由问题:
--   "综合分析青花椒的评价和经营" / "VIP和菜品门店的关系" / "整体诊断" →
--   restaurant_comprehensive_synthesis_gold tool → GoldFinanceClient.fetchComprehensiveSynthesis
--   → Python ComprehensiveSynthesisEngine (FactBook → InsightDimensionAnalyzer → grounded LLM
--   → FactReconciler → 多图)。出境脱敏在 Python 共享客户端层 (P0 数据主权)。
--
-- flyway 版本 20260905.01 > prod 已应用 max 20260904.01 (out-of-order=false, 必须更大)。
-- 幂等: ON CONFLICT (intent_code) DO UPDATE, 可重复执行。
-- priority 108 (低于精确单维短语 115, 但高于通用兜底) — 综合问是附加路径, 不抢单维准确路由。
--   单维问 ("评价怎么样"/"总营收") 命中各自 115 意图; 综合问关键词只在多维同现/综合动词时命中。
-- business_type='RESTAURANT' 让业态门控放行 (制造业态工厂不进此意图)。
-- keywords ::jsonb cast 与 V20260903_01 / V20260904_01 一致 (keywords 列跨环境
--   text/json/jsonb 不一致, jsonb 入参必须显式 cast, 否则 fresh-CI DB 报类型错误)。
--
-- ⚠️ 关键词避开"怎么样"形式 (已知歧义查询局限 — 以"怎么样"结尾会被判 GENERAL_QUESTION)。

INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'COMPREHENSIVE_SYNTHESIS', '综合多维分析', 'SMARTBI', 'restaurant_comprehensive_synthesis_gold', 'LOW',
        '["综合分析","整体分析","全面分析","综合诊断","多维分析","经营和评价","评价和经营","VIP和菜品","客群和门店","关系分析","综合评估","经营诊断","整体诊断","多维度分析"]'::jsonb,
        '综合多维分析 (评价+经营+销售合成): 一次返回多维度结论+交叉关系(VIP×星级/时段×营收/平台×评分)+4要素建议+多张图。适用综合/整体/全面/多维分析、经营和评价、VIP和菜品门店关系、综合诊断。',
        108, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET
    tool_name = 'restaurant_comprehensive_synthesis_gold',
    keywords = EXCLUDED.keywords,
    description = EXCLUDED.description,
    priority = 108,
    is_active = true,
    business_type = 'RESTAURANT',
    updated_at = NOW();
