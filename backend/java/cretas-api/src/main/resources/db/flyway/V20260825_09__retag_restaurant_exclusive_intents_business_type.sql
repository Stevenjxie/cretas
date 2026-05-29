-- Sprint 13 #305 业态门控 (business-type gating) — re-tag restaurant-exclusive analysis intents
--
-- 背景: Cretas 有 2 类客户 — RESTAURANT (餐厅, e.g. RES_3101_009) 与 FACTORY (制造厂, e.g.
-- F006 六膳门卤味). ai_intent_configs.business_type 用于业态隔离 (IntentRecognitionPipelineServiceImpl
-- v32.1 LLM 过滤 + Sprint 13 新增 IntentExecutionOrchestrator.checkBusinessTypeGate 执行层门控).
--
-- 问题: 20 个 RESTAURANT_*-前缀的餐饮专属分析意图被错误标成 business_type='COMMON' (通用),
-- 导致它们在 FACTORY 业态工厂 (如 F006) 也能匹配/执行 → 返半残的 "数据不可用" (F006 无餐饮数据).
-- 其余 32 个 RESTAURANT_* 已正确标为 'RESTAURANT'. 这 20 个均为餐饮专属 (经营分析/翻台/排班/
-- 计件/门店KPI/对账等), 无一是真正通用, 应统一标为 'RESTAURANT'.
--
-- 修复: 把所有 RESTAURANT_*-前缀且 business_type='COMMON' 的意图改为 'RESTAURANT'. 改后:
--   - FACTORY 工厂: v32.1 过滤排除 + 执行层门控返 honest "本厂非餐厅业态 + 工厂替代功能".
--   - RESTAURANT 餐厅 (RES_3101_009): 不受影响, 照常返 P&L (¥1,935,193 Dec / ¥1,840,457 Nov).
--   - business_type 成为业态分类的单一事实来源 (不靠 code 前缀 — FACTORY_CONFIG_AGENT / FACTORY_MR_*
--     是 COMMON 通用配置, 前缀门控会误挡).
--
-- 幂等: 改后再跑影响 0 行 (WHERE business_type='COMMON' 不再匹配).
-- 可逆: 受影响 20 个 code 见 docs/audits/sprint13-business-type-gate-baseline.md §3.

UPDATE ai_intent_configs
SET business_type = 'RESTAURANT',
    updated_at = NOW()
WHERE intent_code LIKE 'RESTAURANT%'
  AND business_type = 'COMMON';
