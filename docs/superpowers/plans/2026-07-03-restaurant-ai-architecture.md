# Restaurant AI Architecture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make restaurant owner decision chat a governed Tool/Skill path instead of an orchestrator-only special branch, while preserving the demo behavior.

**Architecture:** Add a read-only restaurant Tool that delegates to the existing Python owner-action endpoint and returns normalized decision data. Update the Java orchestrator to call that Tool through `ToolRegistry`, so the capability is visible to Tool governance, domain tags, action type filtering, and future Skill composition.

**Tech Stack:** Java 21, Spring Boot 3.2, JUnit 5, Mockito, Python FastAPI owner-action endpoint.

## Global Constraints

- Do not commit secrets or API keys.
- Keep restaurant and factory routing isolated; factory tenants must not hit restaurant owner-action logic.
- Keep write operations out of this change; owner decision advice is read/analyze only.
- Preserve existing `/api/smartbi/restaurant/sections/owner-action-chat` response shape for web-admin demo.
- Add tests before production code changes.

---

### Task 1: Governed Restaurant Owner Advisor Tool

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/restaurant/RestaurantOwnerActionAdvisorTool.java`
- Create: `backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/impl/restaurant/RestaurantOwnerActionAdvisorToolTest.java`

**Interfaces:**
- Consumes: `PythonSmartBIClient.askRestaurantOwnerActionChat(Map<String,Object>)`
- Produces: Tool name `restaurant_owner_action_advisor`; result keys `dataAvailable`, `message`, `answer`, `source`, `scenario`, `sessionId`, `ownerDecisionPage`, `roleActionPlan`, `charts`, `suggestedFollowups`, `dataReadiness`

### Task 2: Orchestrator Delegates to Tool Registry

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/IntentExecutionOrchestrator.java`
- Modify: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/RestaurantOpsGoldRouteTest.java`

**Interfaces:**
- Consumes: `ToolRegistry.getExecutor("restaurant_owner_action_advisor")`
- Produces: owner-action response still uses intent code `RESTAURANT_OWNER_ACTION_CHAT`, but source metadata includes `restaurant_owner_action_advisor`

### Task 3: Python Data Readiness Metadata

**Files:**
- Modify: `backend/python/smartbi/api/restaurant_sections.py`

**Interfaces:**
- Consumes: owner-action scenario params and owner decision page.
- Produces: `dataReadiness` with `mode`, `sourceTypes`, `missingForProduction`, `mockFields`, and `confidenceNote`.

### Task 4: Final Verification

**Files:**
- No new production files beyond Tasks 1-3.

**Interfaces:**
- Verifies Java Tool governance, orchestrator routing, and Python response shape.
