# AI Factory Chat Dispatch Brief — Sprint 12 routing + fault-injection close

**Source**: Canvas/Workdesk chat (this brief is paste-ready into a new Claude Code session)
**Sister deliverable from**: PRs #218 / #233 / #239 / #245 / #246 / #247 / #248 / #250 / #251 (8 merged + 1 OPEN)
**Goal**: close Sprint 12 by shipping the 3 remaining close-gate rows that are AI Factory chat scope per coop split.

---

## PASTE THIS INTO A NEW CLAUDE CODE CHAT

> Subject: Sprint 12 AI Factory chat — close 3 remaining close-gate rows
>
> You're the AI Factory chat for Sprint 12 close. Canvas/Workdesk chat sister has shipped 8 PRs and prepared a handoff doc. Your scope per coop split:
> 1. **9 intent routing bugs** in `docs/audits/2026-05-23-sprint12-e2e-framework/AI-FACTORY-HANDOFF.md` — fix via DB UPDATE on `ai_intent_configs` keywords + optionally bind/disable `FOOD_KNOWLEDGE_QUERY` executor.
> 2. **3 backend fault-injection hooks** (F3/F4/F5) for the LLM-fault-injection close-gate row — add `dev-fault-injection` Spring profile with env-var toggles per `docs/audits/2026-05-23-sprint12-e2e-framework/runner-fault.sh` Section F3-F5.
> 3. **Ship ≥1 PR** so close-gate row "Coop deliverable with AI Factory ≥1 shared" is satisfied with an AI Factory sister PR cited.
>
> **Read these first** (in order):
> - `docs/audits/2026-05-23-sprint12-close.md` — full Canvas-side close report with 11-row close-gate table
> - `docs/audits/2026-05-23-sprint12-e2e-framework/AI-FACTORY-HANDOFF.md` — 9 routing bugs with evidence + SQL remediation
> - `.claude/rules/ai-intent-tool-skill-architecture.md` — Tool-Skill architecture (DO NOT create Handler; Handler is dead)
>
> **Test env**: 139.196.165.140:8097, account `f006_admin`/`123456` factory F006. Verify changes with the 60-path runner:
> ```bash
> bash docs/audits/2026-05-23-sprint12-e2e-framework/runner.sh
> bash docs/audits/2026-05-23-sprint12-e2e-framework/runner-data.sh
> PYTHONIOENCODING=utf-8 python docs/audits/2026-05-23-sprint12-e2e-framework/analyze-combined.py \
>   docs/audits/2026-05-23-sprint12-e2e-framework/runs/<baseline-ts> \
>   docs/audits/2026-05-23-sprint12-e2e-framework/runs/<data-ts>_data
> ```
> Acceptance: strict ≥95% combined, operational 100%, fault-injection 5/5 with backend toggles.
>
> **HARD rules** (per CLAUDE.md):
> - Test URL only: 139.196.165.140:8097 — NEVER deploy/touch prod
> - Default deploy script `--env test`
> - No emoji in B-end UI strings
> - Concurrent edits: use `git commit -- <files>` to lock scope; verify with `git status` after commit
>
> **Scope discipline** — Canvas/Workdesk chat owns formatter/Skill/E2E. You own routing/classifier/LLM-prompt/fault-injection-hooks. Don't refactor Tool implementations — that's Canvas scope. If you find a Tool that needs a fix to support the routing change (e.g. `WAREHOUSE_KEEPER_TODAY_TASKS` Tool needs a new parameter), file an issue and tag Canvas chat.
>
> **Deliverable checklist**:
> - [ ] PR: `feat/sprint12-ai-factory-routing-fixes` — 9 routing bugs fixed via DB migration + grep verify
> - [ ] PR: `feat/sprint12-fault-injection-backend-hooks` — dev-profile toggles for DashScope timeout / Python LLM down / 1-of-N tool fail
> - [ ] Re-run 60+60 audit, paste combined-analysis.json verdict in PR description
> - [ ] Update `docs/audits/2026-05-23-sprint12-close.md` "Coop deliverable" row with your PR #
> - [ ] Comment on PR #251 (https://github.com/Stevenjxie/cretas/pull/251) citing your AI Factory PR # for close-doc cross-reference

---

## Reference: 9 Routing Bugs (extracted from AI-FACTORY-HANDOFF.md)

| # | Input | WRONG intent | Correct intent | Severity |
|---|---|---|---|---|
| 1 | `这个月业绩如何` | REPORT_DASHBOARD_OVERVIEW catch-all | MONTHLY_FINANCIAL_CLOSE | HIGH |
| 2 | `今日 HACCP 状态` | FOOD_KNOWLEDGE_QUERY no executor | FOOD_SAFETY_RECALL | CRITICAL |
| 3 | `近三年所有 HACCP 监控` | FOOD_KNOWLEDGE_QUERY no executor | Same | CRITICAL |
| 4 | `本日待入库` | **MATERIAL_BATCH_CREATE (WRITE!)** | WAREHOUSE_KEEPER_TODAY_TASKS | **CRITICAL** |
| 5 | `上月入库统计` | REPORT_DASHBOARD_OVERVIEW catch-all | Inventory statistics | HIGH |
| 6 | `入库` (bare noun) | **MATERIAL_BATCH_CREATE (WRITE!)** | NEED_CLARIFICATION | **CRITICAL** |
| 7 | `下周采购建议` | ORDER_LIST | PURCHASER_WEEKLY_PLAN | HIGH |
| 8 | `下周补货清单` | RESTAURANT_PROCUREMENT_SUGGESTION (descr leak) | PURCHASER_WEEKLY_PLAN | HIGH |
| 9 | `采购` (bare noun) | ORDER_LIST | NEED_CLARIFICATION | HIGH |

**Suggested SQL pattern** (AI Factory chat to verify schema + run):

```sql
-- Bug #4 #6 #7 #9: remove read-shaped keywords from WRITE / wrong intents
UPDATE ai_intent_configs
SET keywords = '["创建批次","新增批次","录入入库","入库新增","新批次"]'  -- no bare 入库
WHERE intent_code = 'MATERIAL_BATCH_CREATE';

UPDATE ai_intent_configs
SET keywords = '["订单列表","订单查询","查询订单","订单状态"]'  -- no bare 采购
WHERE intent_code = 'ORDER_LIST';

-- Bug #4 #5: add read-query keywords to WAREHOUSE_KEEPER_TODAY_TASKS + inventory stats
UPDATE ai_intent_configs
SET keywords = '["本日待入库","今日入库","今天入库","收什么货","待入库","入库清单"]'
WHERE intent_code = 'WAREHOUSE_KEEPER_TODAY_TASKS';

-- Bug #1: strengthen MONTHLY_FINANCIAL_CLOSE keyword bindings
UPDATE ai_intent_configs
SET keywords = (
  SELECT jsonb_array_elements_text(keywords)::text || ',业绩,月度业绩,本月业绩,业绩如何'
  FROM ai_intent_configs WHERE intent_code = 'MONTHLY_FINANCIAL_CLOSE'
)
WHERE intent_code = 'MONTHLY_FINANCIAL_CLOSE';

-- Bug #2 #3: bind food RAG OR disable FOOD_KNOWLEDGE_QUERY
-- Option A (preferred): bind food RAG Tool
UPDATE ai_intent_configs
SET tool_name = 'food_knowledge_rag_query'
WHERE intent_code = 'FOOD_KNOWLEDGE_QUERY';

-- Option B (fallback): disable + reroute HACCP keywords to FOOD_SAFETY_RECALL
UPDATE ai_intent_configs
SET is_active = false
WHERE intent_code = 'FOOD_KNOWLEDGE_QUERY';

UPDATE ai_intent_configs
SET keywords = (
  SELECT jsonb_array_elements_text(keywords)::text || ',HACCP,食安状态,食安监控,食安告警'
  FROM ai_intent_configs WHERE intent_code = 'FOOD_SAFETY_RECALL'
)
WHERE intent_code = 'FOOD_SAFETY_RECALL';

-- Bug #7 #8: strengthen PURCHASER_WEEKLY_PLAN keyword bindings
UPDATE ai_intent_configs
SET keywords = (
  SELECT jsonb_array_elements_text(keywords)::text || ',下周采购建议,下周补货,下周补货清单,补货清单'
  FROM ai_intent_configs WHERE intent_code = 'PURCHASER_WEEKLY_PLAN'
)
WHERE intent_code = 'PURCHASER_WEEKLY_PLAN';
```

**WARNING**: the `jsonb_array_elements_text(keywords)::text || ',X,Y'` pattern above is psuedo-code — actual JSONB array append needs `jsonb_set` or `keywords || '["X","Y"]'::jsonb`. AI Factory chat to verify against schema and write a proper Flyway migration in `backend/java/cretas-api/src/main/resources/db/flyway/V<YYYYMMDD>_NN__sprint12_routing_fixes.sql`.

---

## Reference: F3/F4/F5 Backend Fault-Injection Hooks Needed

| Fault | Backend env var | Spring config location |
|---|---|---|
| F3 DashScope timeout | `MOCK_DASHSCOPE_DELAY_MS=30000` | DashScope adapter or LLM client wrapper |
| F4 Python LLM down | `MOCK_PYTHON_LLM_UNREACHABLE=true` | PythonSmartBIClient (10010 → 8083 HTTP layer) |
| F5 1-of-N tool fail | `MOCK_TOOL_THROW=monthly_revenue_query` | ToolDispatchService.executeTool wrapper |

Only active under `dev-fault-injection` Spring profile. Production safe — never reached without explicit `-Dspring.profiles.active=dev-fault-injection`.

After backend hooks shipped + deployed test env, Canvas/Workdesk runner-fault.sh F3-F5 sections can be filled in by a small addendum (~30 lines).

---

## After AI Factory Chat Ships

Steve OR AI Factory chat re-runs combined audit:
```bash
bash docs/audits/2026-05-23-sprint12-e2e-framework/runner.sh         # 60 baseline
bash docs/audits/2026-05-23-sprint12-e2e-framework/runner-data.sh    # 60 real-data
bash docs/audits/2026-05-23-sprint12-e2e-framework/runner-fault.sh   # F1-F5 with backend hooks active
PYTHONIOENCODING=utf-8 python docs/audits/2026-05-23-sprint12-e2e-framework/analyze-combined.py \
  docs/audits/2026-05-23-sprint12-e2e-framework/runs/<baseline-ts> \
  docs/audits/2026-05-23-sprint12-e2e-framework/runs/<data-ts>_data
```

Expected: strict ≥95% (5/6 routing bugs fixed) or ≥98% (all 9 fixed) combined, operational 100%, fault-injection 5/5.

Update `docs/audits/2026-05-23-sprint12-close.md`:
- Mark "Operational 100%" row ✅
- Mark "LLM fault-injection 100%" row ✅
- Mark "Coop deliverable ≥1 shared" row ✅ with AI Factory sister PR # cited
- Update strict % from 80.0% to whatever combined audit shows

Final state target: 11/11 close-gate rows met, goal "100% strict-PASS routing" projected ≥95% (intent-classifier limitations may keep absolute 100% out of reach without deeper LLM-prompt-template work).
