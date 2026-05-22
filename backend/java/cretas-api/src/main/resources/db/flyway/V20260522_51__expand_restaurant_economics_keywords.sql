-- V20260522_51__expand_restaurant_economics_keywords.sql
--
-- Sprint 11 — MealClaw Response (Round 4 P1 fix Bug 1).
--
-- Round 3 E2E discovered the spec'd customer happy-path keyword
-- "帮我看上月损溢异常" was misrouted to ALERT_ACTIVE intent
-- (perm-denied for qhj_warehouse_mgr) instead of RESTAURANT_ECONOMICS_ANALYSIS.
--
-- Direct classifier test (Round 3 evidence):
--   "帮我看上月损溢"         → RESTAURANT_WASTAGE_SUMMARY (NEED_CLARIFICATION)
--   "损益分析"               → RESTAURANT_ECONOMICS_ANALYSIS (NEED_CLARIFICATION)
--   "帮我看上月损溢异常"    → ALERT_ACTIVE  ← MISROUTE (keyword "异常" in ALERT_ACTIVE)
--
-- Root cause: V20260522_50 seeded RESTAURANT_ECONOMICS_ANALYSIS.keywords without
-- explicit literal "帮我看上月损溢异常" / "损溢异常" / "损耗异常" — classifier fallback
-- weighted ALERT_ACTIVE higher because "异常" matched its broader keyword list.
--
-- Fix: expand RESTAURANT_ECONOMICS_ANALYSIS.keywords to include the EXACT spec
-- Phase 4 DOD #1 keyword + obvious variations + 上月 / 餐厅 / 食材 anchors.
--
-- This is the primary acceptance criterion from spec §2.11 Phase 4 DOD #1:
--   "真实账号 prod 跑 '帮我看上月损溢异常', 30s 出诊断"
--
-- Hotfix pattern from V20260522_50 (per .claude/rules/concurrent-edit-safety.md):
--   - keywords: ::jsonb cast (V_05 || V_36 jsonb 历史教训)
--   - NO `examples` column (Sprint 9 V_36 lesson)
--   - Direct UPDATE (intent already exists from V20260522_50)

UPDATE ai_intent_configs
SET keywords = '[
  "帮我看上月损溢",
  "帮我看上月损溢异常",
  "上月损溢异常",
  "损溢异常",
  "损耗异常",
  "损益异常",
  "损溢分析",
  "损益分析",
  "成本分析",
  "上月成本",
  "上月经营情况",
  "上月损益",
  "餐厅损溢",
  "餐厅损益",
  "餐厅诊断",
  "经营诊断",
  "菜品损溢",
  "食材损溢",
  "食材损耗",
  "哪个菜亏钱",
  "为啥这个月利润下滑",
  "原材料损溢",
  "本月经营怎么样",
  "本月成本怎么样",
  "上周哪些菜亏钱",
  "损益+根因+建议",
  "一句话查损益"
]'::jsonb,
    updated_at = NOW()
WHERE intent_code = 'RESTAURANT_ECONOMICS_ANALYSIS';
