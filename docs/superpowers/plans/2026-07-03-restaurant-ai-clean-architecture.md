# Restaurant AI Clean Architecture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove leftover Java/Python/frontend owner-action redundancy so restaurant boss decision chat has one governed Java entry and one Python analysis backend.

**Architecture:** Web Admin owner-action questions must enter through Java `/ai-intents/execute`; Java routes through `ToolRegistry` to `restaurant_owner_action_advisor`; Python remains the analysis engine behind that governed Tool. Direct frontend Python owner-action APIs are removed or kept only as internal Java-to-Python bridge code.

**Tech Stack:** Java 21 + Spring Boot Tool-Skill architecture, Python FastAPI SmartBI restaurant sections, Vue Web Admin TypeScript, Expo React Native TypeScript.

## Global Constraints

- Keep production behavior stable: owner-action answers still return `answer`, `sessionId`, `scenario`, `charts`, `roleActionPlan`, `suggestedFollowups`, and `dataReadiness`.
- Do not remove Python `/api/smartbi/restaurant/sections/owner-action-chat`; Java `PythonSmartBIClient` still needs it.
- Do not remove Python `owner_action_chat()` helper; Python tests call it directly.
- Frontend owner-action demo must not bypass Java governance.
- Follow TDD: write failing tests or assertions before production changes.

---

### Task 1: Java Orchestrator Dependency Cleanup

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/IntentExecutionOrchestrator.java`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/RestaurantOpsGoldRouteTest.java`

**Interfaces:**
- Consumes: `ToolRegistry.getExecutor("restaurant_owner_action_advisor")`
- Produces: `executeRestaurantOwnerActionChat(...)` without direct `PythonSmartBIClient` field dependency

- [x] **Step 1: Verify the stale direct dependency exists**

Run:
```powershell
git grep -n "PythonSmartBIClient" -- backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/IntentExecutionOrchestrator.java
```
Expected: import and field are present before cleanup.

- [x] **Step 2: Remove the unused import and field**

Delete:
```java
import com.cretas.aims.client.PythonSmartBIClient;
```
and:
```java
@Autowired
private PythonSmartBIClient pythonSmartBIClient;
```

- [x] **Step 3: Run Java route test**

Run:
```powershell
mvn -q "-Dtest=RestaurantOpsGoldRouteTest#ownerActionExecutionUsesGovernedTool" test
```
Expected: PASS, proving Java still routes through ToolRegistry.

### Task 2: Owner-Action Source Contract

**Files:**
- Modify: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/RestaurantOpsGoldRouteTest.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/restaurant/RestaurantOwnerActionAdvisorTool.java`
- Modify: `web-admin/src/views/smart-bi/AIQuery.vue`

**Interfaces:**
- Consumes: Java Tool result `source`
- Produces: a frontend-recognizable source while preserving advisor trace

- [x] **Step 1: Add/adjust failing assertion for source contract**

In `ownerActionExecutionUsesGovernedTool`, assert:
```java
assertThat(((Map<?, ?>) response.getResultData()).get("source")).isEqualTo("restaurant_owner_action");
assertThat(((Map<?, ?>) response.getResultData()).get("advisorSource")).isEqualTo("restaurant_owner_action_advisor");
```
Expected before implementation: FAIL because source is currently `restaurant_owner_action_advisor`.

- [x] **Step 2: Implement source normalization in Java Tool**

Set:
```java
result.put("source", "restaurant_owner_action");
result.put("advisorSource", "restaurant_owner_action_advisor");
```
Keep unavailable responses consistent.

- [x] **Step 3: Keep Web Admin compatible**

In `AIQuery.vue`, recognize both:
```ts
const isOwnerActionResponse = resultDataAny?.source === 'restaurant_owner_action'
  || resultDataAny?.advisorSource === 'restaurant_owner_action_advisor';
```

- [x] **Step 4: Run Java tests**

Run:
```powershell
mvn -q "-Dtest=RestaurantOwnerActionAdvisorToolTest,RestaurantOpsGoldRouteTest" test
```
Expected: PASS.

### Task 3: Remove Frontend Python Bypass APIs

**Files:**
- Modify: `web-admin/src/api/smartbi/restaurant-chat.ts`
- Modify: `frontend/CretasFoodTrace/src/services/api/smartbi.ts`

**Interfaces:**
- Consumes: Java intent route only for boss decision chat
- Produces: no exported owner-action helper that directly calls Python

- [x] **Step 1: Verify direct Python owner-action exports exist**

Run:
```powershell
git grep -n "owner-action-chat\|askRestaurantOwnerActionQuestion\|restaurantOwnerActionChat" -- web-admin/src frontend/CretasFoodTrace/src
```
Expected: Web Admin and RN direct Python helpers exist before cleanup.

- [x] **Step 2: Remove unused Web Admin direct helper**

Delete `OwnerActionChatRequest`, `OwnerActionChatResponse`, `pythonFetch`, and `PYTHON_LLM_TIMEOUT_MS` imports from `restaurant-chat.ts`, and delete `askRestaurantOwnerActionQuestion`.

- [x] **Step 3: Remove unused RN direct helper**

Delete `restaurantOwnerActionChat` from `frontend/CretasFoodTrace/src/services/api/smartbi.ts` if no call sites exist.

- [x] **Step 4: Verify no frontend direct owner-action Python route remains**

Run:
```powershell
git grep -n "owner-action-chat" -- web-admin/src frontend/CretasFoodTrace/src
```
Expected: no output.

### Task 4: Verification And Commit

**Files:**
- All modified files

**Interfaces:**
- Produces: verified clean architecture branch ready for merge

- [x] **Step 1: Java targeted regression**

Run:
```powershell
mvn -q "-Dtest=RestaurantOwnerActionAdvisorToolTest,RestaurantOpsGoldRouteTest,ToolRegistryTest" test
```
Expected: PASS.

- [x] **Step 2: Python owner-action regression**

Run:
```powershell
python -m pytest smartbi/services/restaurant/tests/test_owner_action_data_readiness.py smartbi/services/restaurant/tests/test_boss_decision_brief.py -q
```
Expected: PASS.

- [x] **Step 3: Web Admin type/test smoke**

Run:
```powershell
npm run test -- --run src/__tests__/auth.store.test.ts
```
Expected: PASS if dependency setup supports it; otherwise report exact blocker.

- [x] **Step 4: Commit**

Run:
```powershell
git status --short
git add <changed files>
git commit -m "refactor: clean restaurant owner action architecture"
```
Expected: one scoped commit with only architecture cleanup files.
