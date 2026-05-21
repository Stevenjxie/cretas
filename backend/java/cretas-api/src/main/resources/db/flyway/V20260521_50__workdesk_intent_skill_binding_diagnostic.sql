-- V20260521_50__workdesk_intent_skill_binding_diagnostic.sql
--
-- Sprint 9 P0.1 — Workdesk intent dispatch bug RCA + diagnostic.
--
-- BUG (P0, caught by Sprint 9 P0 Playwright smoke):
-- Sprint 8 P1/P2/P3 WORKDESK intents (DAILY_CUSTOMER_FOLLOWUP / MONTHLY_FINANCIAL_CLOSE /
-- FOOD_SAFETY_RECALL 等) bind tool_name=NULL + 依赖 SkillRegistry keyword 匹配走 Skill 路由.
-- 实际 dispatch path:
--   IntentExecutionOrchestrator.execute() line 257-268 trySkillRoute → 用 Skill triggers
--   匹用户原文; 若用户 query 不含 Skill triggers (例 "客户跟进" 在 intent keywords 但
--   Skill triggers 是 "今天跟谁" / "今日跟进"), trySkillRoute 返 null → 路由 fall through
--   到 line 313 buildNoToolResponse → 报 "暂不支持此类型的意图执行: WORKDESK". 完全阻塞.
--
-- FIX (backend code, see PR sprint9/p01-workdesk-intent-binding-fix):
-- 1. DynamicToolSelectionService.tryExplicitSkillRouteForIntent(intent, query, factoryId, userId)
--    — 用 intent_code (UPPER_SNAKE) → skill_name (lower-kebab) 直接 lookup Skill, 绕开 keyword.
-- 2. IntentExecutionOrchestrator.execute() 路由分支加 explicit Skill fallback (3 处插桩).
-- 3. SseStreamingService 同步加 explicit Skill fallback (2 处插桩).
-- 4. Unit tests in DynamicToolSelectionServiceWorkdeskRouteTest 验证 10 个 case.
--
-- 本 migration 仅 diagnostic — NOT modify data. 列出现状给 ops 留 audit log.
-- 真正修复是 Java code change (Flyway 跑通后 cretas-backend-test 自动重启即生效).
--
-- 后续可选 cleanup migration: 把 ai_intent_configs.metadata 字段填 {"skill_name": "..."}
-- 显式 binding (per ai-intent-tool-skill-architecture.md "metadata" 字段定义) — 但当前
-- naming convention (UPPER_SNAKE → lower-kebab) 1:1 mapping 是 Sprint 8 P1-P3 已建立的
-- pattern, code-level fix 已 cover.

-- ===== 1. List current WORKDESK intents (informational SELECT — NOT modifying data) =====

-- NOTE: SELECT 语句在 Flyway migration 里只在日志 visible, 不影响 schema.
-- 用 DO block 兼容 PG (Flyway 不直接执行 SELECT outside DO).
DO $$
DECLARE
    rec RECORD;
    null_tool_workdesk_count INT := 0;
BEGIN
    RAISE NOTICE '=== Sprint 9 P0.1 RCA: WORKDESK intents with tool_name=NULL ===';
    FOR rec IN
        -- 2026-05-21 hotfix: keywords is jsonb; cast to text for LENGTH().
        -- LENGTH(jsonb) does not exist in PG; original sister chat migration
        -- broke prod blue startup 14:18 today. Cast keeps diagnostic semantics
        -- (string length of JSON representation).
        SELECT intent_code, intent_name, intent_category, tool_name,
               LENGTH(COALESCE(keywords::text, '[]')) AS kw_len
        FROM ai_intent_configs
        WHERE intent_category = 'WORKDESK'
          AND tool_name IS NULL
          AND is_active = true
        ORDER BY intent_code
    LOOP
        RAISE NOTICE 'intent_code=% intent_name=% (kw_len=%)',
                     rec.intent_code, rec.intent_name, rec.kw_len;
        null_tool_workdesk_count := null_tool_workdesk_count + 1;
    END LOOP;
    RAISE NOTICE '=== Total WORKDESK + tool_name=NULL intents: % (all rely on Skill route) ===',
                 null_tool_workdesk_count;
END $$;

-- ===== 2. Sanity assertion — Sprint 8 P1/P2/P3 Workdesk skill intents 应该存在 =====
-- 不存在 = Sprint 8 migration 没跑通 (上游 bug), 不是本 P0.1 fix 范畴.

DO $$
DECLARE
    p1_count INT;
    p2_count INT;
    p3_count INT;
BEGIN
    SELECT COUNT(*) INTO p1_count
    FROM ai_intent_configs
    WHERE intent_code = 'DAILY_CUSTOMER_FOLLOWUP' AND is_active = true;

    SELECT COUNT(*) INTO p2_count
    FROM ai_intent_configs
    WHERE intent_code = 'MONTHLY_FINANCIAL_CLOSE' AND is_active = true;

    SELECT COUNT(*) INTO p3_count
    FROM ai_intent_configs
    WHERE intent_code = 'FOOD_SAFETY_RECALL' AND is_active = true;

    RAISE NOTICE 'Sprint 8 Workdesk intent presence — P1: % / P2: % / P3: %',
                 p1_count, p2_count, p3_count;

    IF p1_count = 0 THEN
        RAISE NOTICE 'WARN: DAILY_CUSTOMER_FOLLOWUP missing — Sprint 8 P1 migration V20260820_01 未跑?';
    END IF;
    IF p2_count = 0 THEN
        RAISE NOTICE 'WARN: MONTHLY_FINANCIAL_CLOSE missing — Sprint 8 P2 migration V20260820_02 未跑?';
    END IF;
    IF p3_count = 0 THEN
        RAISE NOTICE 'WARN: FOOD_SAFETY_RECALL missing — Sprint 8 P3 migration V20260820_07 未跑?';
    END IF;
END $$;
