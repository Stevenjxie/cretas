# P0-1 Dispatch: SMART_INDICATOR_QUERY intent 漏 ship

**Date**: 2026-05-23
**Owner**: BI chat (worktree `sprint11-d5` / branch `feat/sprint11-d5-alert-tool-2026-05-22`)
**Severity**: P0 YELLOW (Skill code shipped, intent missing → 404 on direct call)
**Source bug**: AI 工厂 chat Item 2 audit case #11+#12, doc `docs/audits/sprint-11-validation/bi-tool-output-validation.md` (PR #220)

---

## Problem

D6a commit `a671ed7e9` shipped `smart-indicator-query` Skill code (4-Tool 智能路由) **but missing intent migration**.

Real test (Item 2 case #11):
```
POST /api/mobile/F006/ai-intents/execute
{intentCode:"SMART_INDICATOR_QUERY", userInput:"智能指标"}
→ status FAILED: "未找到意图配置: SMART_INDICATOR_QUERY"
```

Case #12 (natural language "看几个指标"): routes to INDICATOR_COMPARISON (wrong intent) → NEED_MORE_INFO.

---

## Fix scope

### Files (create only, no edits)

1. **Migration**: `backend/java/cretas-api/src/main/resources/db/flyway/V20260823_XX__smart_indicator_query_intent.sql`
   - Find next free V_23 slot (current main has V_23_01..06, V_23_07, V_23_10..13 — pick V_23_14 or 15)
   - INSERT INTO ai_intent_configs:
     ```sql
     INSERT INTO ai_intent_configs (
         id, factory_id, intent_code, intent_name, intent_category,
         skill_name, keywords, negative_keywords,
         business_type, sensitivity_level, priority, is_active,
         created_at, updated_at
     ) VALUES (
         gen_random_uuid()::varchar, NULL, 'SMART_INDICATOR_QUERY', '智能指标查询',
         'DATA_OP', 'smart-indicator-query',
         '["智能指标","看几个指标","看一下指标","指标综合","综合指标分析"]'::jsonb,
         '[]'::jsonb,
         'COMMON', 'LOW', 80, true,
         NOW(), NOW()
     );
     ```
   - VERIFY block: SELECT count(*) FROM ai_intent_configs WHERE intent_code='SMART_INDICATOR_QUERY' → expect 1
2. **No code change** — Skill `smart-indicator-query` already registered (commit `a671ed7e9`).

### Test (e2e-web-admin per HARD)

After deploy, re-run Item 2 case #11+#12:
- Case #11: `intentCode=SMART_INDICATOR_QUERY` → expect status=SUCCESS (not "未找到意图配置")
- Case #12: NL "看几个指标" → expect intentCode=SMART_INDICATOR_QUERY (or fallback to skill match)

Evidence script (use AI 工厂 chat Item 2 test script pattern):
```bash
TOKEN=$(curl ... auth/unified-login | jq .data.token)
curl -X POST .../F006/ai-intents/execute -d '{"intentCode":"SMART_INDICATOR_QUERY","userInput":"智能指标"}' | jq
# Expected: {data: {status: "SUCCESS", intentCode: "SMART_INDICATOR_QUERY", ...}}
```

---

## DoD (depth-first-e2e Rule 1+2)

- [ ] Migration written + commit SHA recorded in tracker
- [ ] PR opened + admin-merge
- [ ] Deploy backend (BG, blue/green) + SSH systemctl is-active double check
- [ ] Re-run Item 2 case #11+#12 → both PASS (status SUCCESS, intent recognized)
- [ ] Update tracker `docs/audits/sprint-11-p0-fix-tracker.md` row P0-1 with PR # / commit SHA / deploy time / re-test PASS evidence

---

## Anti-pattern (forbidden)

- ❌ "应该 ship 了" without PR # evidence
- ❌ Skip post-deploy re-test (status SUCCESS in code-review != prod working)
- ❌ Don't add domain keywords (this Skill is COMMON, not domain-tagged)

---

## Cross-references

- D6a Skill code: commit `a671ed7e9` (`feat/sprint11-d5-alert-tool-2026-05-22` branch)
- SkillRegistryImpl smart-indicator-query Skill registration verify: grep `smart-indicator-query`
- Audit evidence: PR #220 / commit `a254832bc` Item 2 case #11+#12

---

## Coordinator checkin

@AI 工厂 chat coordinator: 6h after this dispatch, expect BI chat ack. If no ack → escalate to Steve.
