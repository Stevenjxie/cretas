-- V20260824_50__sprint12_restaurant_economics_negative_keywords.sql
--
-- Sprint 12 P0 — NL Routing Root-Cause Fix (defense in depth).
--
-- Per AI 工厂 Goal v5 UI audit (docs/audits/sprint-11-ux-audit/verdict-2026-05-23.md),
-- restaurant-finance NL phrases ("帮我看上月损溢异常" / "损益分析" / "上月成本" / "哪个菜亏钱")
-- misrouted to DAILY_CUSTOMER_FOLLOWUP-style LLM conversational output. 12/12 customer demo
-- cases hit Class D 错路由 (75%) or Class F 错误信息 (25%).
--
-- The CORE fix is the orchestrator-level phrase shortcut moved before
-- handleEarlyQuestionTypeDetection (IntentExecutionOrchestrator.java #0.25). This migration
-- is DEFENSE IN DEPTH only — adds negative_keywords on both intents to prevent the LLM tier
-- from misrouting if both miss the phrase shortcut for any reason.
--
-- NOTE: per .claude/rules/concurrent-edit-safety.md HARD `feedback_negative_keywords_useless_in_cretas_intentmatching.md`,
-- negative_keywords on their own have limited effect (phraseWeight 1.0 dominates over
-- keywordWeight 0.25 + 13+ IntentKnowledgeBase phrase shortcuts swamp 0.15/kw penalty).
-- They are a backup signal, not the load-bearing fix.
--
-- Idempotency: UPDATE without ON CONFLICT (these are existing intents, not inserts).
-- jsonb cast required per `database-entity-sync.md` Sprint 9 V_36 lesson.

-- Update DAILY_CUSTOMER_FOLLOWUP to exclude restaurant-finance signals
UPDATE ai_intent_configs SET
    negative_keywords = '["损溢","损益","损耗","成本","毛利","亏钱","赚钱","赔钱","经营情况","菜品成本","餐厅经营","门店经营","餐饮成本","厨房成本"]'::jsonb,
    updated_at = NOW()
WHERE intent_code = 'DAILY_CUSTOMER_FOLLOWUP';

-- Update RESTAURANT_ECONOMICS_ANALYSIS to exclude customer-followup signals
UPDATE ai_intent_configs SET
    negative_keywords = '["客户跟进","跟进客户","客户优先","商机","微信","电话回访","电话跟进","客户拜访","客户回访","客户名单"]'::jsonb,
    updated_at = NOW()
WHERE intent_code = 'RESTAURANT_ECONOMICS_ANALYSIS';

-- Bump RESTAURANT_ECONOMICS_ANALYSIS priority above DAILY_CUSTOMER_FOLLOWUP for tie-breaks.
-- Sprint 11 D7 (V_23_11) bumped INDICATOR_QUERY to 90; restaurant economics also 90; bump to 92.
UPDATE ai_intent_configs SET
    priority = 92,
    updated_at = NOW()
WHERE intent_code = 'RESTAURANT_ECONOMICS_ANALYSIS';
