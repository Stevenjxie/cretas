# P0-2 Dispatch: LLM hallucinates indicator query → 防幻觉 guard

**Date**: 2026-05-23
**Owner**: BI chat (worktree `sprint11-d5`) OR AI 工厂 chat (worktree `sprint11-indicator`) — Steve assigns
**Severity**: P0 RED (防幻觉 critical violation, 老板看到 600-char 编造文字 误以为真分析)
**Source bug**: AI 工厂 chat Item 2 audit case #13, doc `docs/audits/sprint-11-validation/bi-tool-output-validation.md` (PR #220)

---

## Problem

Item 2 case #13 — natural language query that should route INDICATOR_QUERY but didn't:
```
POST /F006/ai-intents/execute {"userInput":"食安通过率怎么样"}
→ intent: null, tool: null, status: COMPLETED
→ msg: "根据最新的运营数据画像，工厂内部未开展任何实际生产...建议措施: 1. 启动生产计划: 将批次 PB-PP-AUTO... 改为 IN PROGRESS..."
```

600-char 完全编造 production plan, **完全不是 indicator query 答**. 老板看到会以为是真业务建议.

Per `feedback_smoke_validates_usefulness_not_just_no_error` HARD + Cretas project's 防幻觉绝对不妥协 rule.

---

## Root cause (per AI 工厂 chat Item 4 audit)

INDICATOR_QUERY keywords missing exact "食安通过率怎么样" match + scoring algo not strong enough → falls through to LLM fallback → LLM generates random production-plan text.

Item 4 doc `routing-scoring-investigation.md`:
- phraseWeight=1.0 vs keywordWeight=0.25 → 4x bias toward phrase shortcuts
- 13+ hardcoded IntentKnowledgeBase shortcuts dominate
- LLM fallback has no guard against generating off-topic numerical answers

---

## Fix scope (二选一 + 共同 guard)

### Option A: 加 INDICATOR_QUERY 强 keyword/phrase
- Edit `IntentKnowledgeBase.java` (~6755-6967 restaurantPhraseMapping section)
- Add `phraseToIntentMapping.put("食安通过率怎么样", "INDICATOR_QUERY")` + 食安/翻台/损耗/良品率/客单价 等 ≥10 NL variants
- 任何 indicator NL keyword 优先 route INDICATOR_QUERY, **bypass LLM fallback**

### Option B: LLM Fallback 防幻觉 system prompt
- Edit Fallback service (find: `grep -rn "llm.*fallback" backend/java/cretas-api/src/main`)
- Add system prompt: "如果 query 含指标关键词 (客单价/翻台率/食安/良品率/损耗/毛利), 你 MUST 返 '当前没找到该指标查询路径, 请前往 Indicator Center 直接查看' — 禁止编造数字 / 业务建议 / 生产任务"
- 加 unit test: mock query "食安通过率怎么样" + Tool返 null → assert LLM 不返编造 production text

### Option C (must do, regardless of A/B): 防幻觉单测

`backend/java/cretas-api/src/test/java/com/cretas/aims/service/intent/AntiHallucinationTest.java`:
- Mock Tool returns null/empty
- Assert: LLM response contains NO number AND contains NO production/batch/task keywords
- Assert: response contains "暂无数据" / "未找到" / "请前往" 至少 1 个

---

## Test (post-deploy)

Re-run Item 2 case #13 + add 3 more NL hard cases:
```
"食安通过率怎么样" → expect intent: INDICATOR_QUERY or fallback-friendly "请前往..."
"客单价多少多少多少" → 同样
"良品率呢" → 同样
"翻台率怎么算" → 同样
```

DoD: 0/4 fall into LLM hallucination, 4/4 either INDICATOR_QUERY routed OR friendly fallback (no fake numbers).

---

## DoD (depth-first-e2e Rule 1+2+8 same-cause sweep)

- [ ] Pick Option A OR B (recommend B for breadth) — commit SHA recorded
- [ ] Option C unit test added + passes
- [ ] Same-cause sweep: grep `LlmFallback` callsites + verify all wrapped with guard
- [ ] Re-test Item 2 case #13 → PASS (no hallucination)
- [ ] 3 additional hard NL cases PASS
- [ ] Update tracker row P0-2 with PR # / commit SHA / deploy / re-test evidence

---

## Anti-pattern

- ❌ "LLM prompt tweak should work" without proven re-test
- ❌ Skip Option C unit test (regression protection)
- ❌ Only fix the exact "食安通过率怎么样" query, miss sister NL patterns

---

## Cross-references

- Item 2 case #13 raw evidence: PR #220 / `docs/audits/sprint-11-validation/bi-tool-output-validation.md`
- Item 4 root cause: `docs/audits/sprint-11-validation/routing-scoring-investigation.md`
- Sprint 11 防幻觉 HARD: `.claude/rules/ai-intent-tool-skill-architecture.md`
- `feedback_smoke_validates_usefulness_not_just_no_error.md`
