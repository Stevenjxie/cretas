# Restaurant Domain Routing Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep restaurant owner-action analysis and factory SmartBI/tool-skill execution separated so restaurant and factory tenants do not pollute each other through duplicated routing or shared chat state.

**Architecture:** Java remains the authoritative entry point for `/ai-intents/execute`; frontend only forwards continuity context returned by the server. Python restaurant owner-action chat keeps scenario continuity only for the explicit `sessionId`, never by whole factory fallback.

**Tech Stack:** Java 21 + Spring Boot tests with JUnit/Mockito; Python FastAPI module tests with pytest; Vue 3 TypeScript web-admin.

## Global Constraints

- Do not reintroduce IntentHandler; keep Tool-Skill architecture.
- Do not make Python a second top-level intent router; Python receives already-routed restaurant owner-action requests.
- Keep factory and restaurant behavior compatible with current demo URL.
- Do not touch unrelated dirty E2E/warehouse files in the parent workspace.
- No secrets in tracked files.

---

### Task 1: Java Owner-Action Routing Boundary

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/IntentExecutionOrchestrator.java`
- Modify: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/RestaurantOpsGoldRouteTest.java`

**Interfaces:**
- Consumes: `resolveFactoryDomainSafe(String factoryId)`, `shouldRouteRestaurantOwnerAction(String factoryId, String userInput, Map<String,Object> context)`
- Produces: server-authoritative owner-action routing where restaurant tenants can route owner-action questions and factory tenants cannot, even with owner-action context.

- [ ] **Step 1: Add failing tests for explicit factory isolation**

Add tests to `RestaurantOpsGoldRouteTest`:

```java
@Test
@DisplayName("owner action route refuses manufacturing factories even with owner context")
void ownerActionRouteRefusesFactoryDomainEvenWithContext() {
    IntentConfigManagementService configService = mock(IntentConfigManagementService.class);
    ReflectionTestUtils.setField(orchestrator, "configService", configService);
    when(configService.resolveBusinessDomain("F006")).thenReturn("FACTORY");

    Boolean directQuestion = ReflectionTestUtils.invokeMethod(
            orchestrator,
            "shouldRouteRestaurantOwnerAction",
            "F006",
            "老板今天应该怎么提高营收？",
            Map.of());
    Boolean contextualFollowUp = ReflectionTestUtils.invokeMethod(
            orchestrator,
            "shouldRouteRestaurantOwnerAction",
            "F006",
            "具体怎么执行？",
            Map.of("ownerActionSessionId", "owner-action-leaked"));

    assertThat(directQuestion).isFalse();
    assertThat(contextualFollowUp).isFalse();
}
```

- [ ] **Step 2: Add tests for restaurant follow-up continuity**

Add test to `RestaurantOpsGoldRouteTest`:

```java
@Test
@DisplayName("owner action route accepts restaurant follow-up only when restaurant domain is confirmed")
void ownerActionRouteAcceptsRestaurantContextOnlyForRestaurantDomain() {
    IntentConfigManagementService configService = mock(IntentConfigManagementService.class);
    ReflectionTestUtils.setField(orchestrator, "configService", configService);
    when(configService.resolveBusinessDomain("RES_3101_009")).thenReturn("RESTAURANT");

    Boolean shouldRoute = ReflectionTestUtils.invokeMethod(
            orchestrator,
            "shouldRouteRestaurantOwnerAction",
            "RES_3101_009",
            "具体怎么执行？",
            Map.of("ownerActionSessionId", "owner-action-session-1"));

    assertThat(shouldRoute).isTrue();
}
```

- [ ] **Step 3: Run Java route tests**

Run:

```powershell
cd backend/java/cretas-api
mvn -q "-Dtest=RestaurantOpsGoldRouteTest" test
```

Expected first result before implementation: fail if any existing behavior routes factory context.

- [ ] **Step 4: Keep Java implementation minimal**

Only change `shouldRouteRestaurantOwnerAction` if tests expose a gap. Required behavior:

```java
String factoryDomain = resolveFactoryDomainSafe(factoryId);
if (!"RESTAURANT".equalsIgnoreCase(factoryDomain)) {
    return false;
}
```

This domain check must stay before `hasOwnerActionContinuationContext(context)`.

- [ ] **Step 5: Re-run Java route tests**

Run:

```powershell
cd backend/java/cretas-api
mvn -q "-Dtest=RestaurantOpsGoldRouteTest" test
```

Expected: all tests pass.

---

### Task 2: Python Owner-Action Session Isolation

**Files:**
- Modify: `backend/python/smartbi/api/restaurant_sections.py`
- Modify: `backend/python/smartbi/services/restaurant/tests/test_boss_decision_brief.py`

**Interfaces:**
- Consumes: `OwnerActionChatRequest`, `owner_action_chat(body)`
- Produces: owner-action scenario continuity keyed only by explicit `sessionId`; no factory-level last scenario fallback.

- [ ] **Step 1: Add failing Python test for cross-session factory leakage**

Add test to `backend/python/smartbi/services/restaurant/tests/test_boss_decision_brief.py` near other owner-action chat tests:

```python
def test_owner_action_chat_does_not_reuse_factory_last_scenario_without_session() -> None:
    from smartbi.api.restaurant_sections import _OWNER_ACTION_CHAT_SESSIONS

    _OWNER_ACTION_CHAT_SESSIONS.pop("owner-action-package-seeded", None)
    first = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="RES_DEMO_QHJ",
            message="帮我算一个适合工作日低峰推的小套餐",
            session_id="owner-action-package-seeded",
        )
    )
    assert first["data"]["scenario"] == "package"

    follow_up_without_session = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="RES_DEMO_QHJ",
            message="具体怎么执行？",
        )
    )

    assert follow_up_without_session["data"]["scenario"] == "package"
    assert follow_up_without_session["data"]["sessionId"] != "owner-action-package-seeded"
```

Then change the last assertion after implementation to assert the new request does not inherit by factory:

```python
    assert follow_up_without_session["data"]["scenario"] == "package"
```

Use a second seeded scenario if needed so the post-fix assertion is meaningful:

```python
    first = owner_action_chat(... message="今天下雨加上商场活动，堂食和外卖要怎么调？" ...)
    assert first["data"]["scenario"] == "external_event_response"
    assert follow_up_without_session["data"]["scenario"] == "package"
```

- [ ] **Step 2: Run the Python test to confirm current leak**

Run:

```powershell
cd backend/python
python -m pytest smartbi/services/restaurant/tests/test_boss_decision_brief.py -q -k "does_not_reuse_factory_last_scenario_without_session"
```

Expected before implementation: fail because the no-session follow-up inherits `_OWNER_ACTION_FACTORY_LAST_SCENARIOS`.

- [ ] **Step 3: Remove factory-level owner-action fallback**

In `restaurant_sections.py`, delete `_OWNER_ACTION_FACTORY_LAST_SCENARIOS`, remove the no-session fallback, and stop writing it:

```python
_OWNER_ACTION_CHAT_SESSIONS: dict[str, dict[str, Any]] = {}
```

In `_owner_action_chat_impl`, keep only:

```python
previous = _OWNER_ACTION_CHAT_SESSIONS.get(session_id, {})
```

Delete:

```python
if not previous and _is_follow_up(body.message) and not _has_owner_action_topic(body.message):
    previous = {"scenario": _OWNER_ACTION_FACTORY_LAST_SCENARIOS.get(factory_id, "")}
...
_OWNER_ACTION_FACTORY_LAST_SCENARIOS[factory_id] = scenario
```

- [ ] **Step 4: Re-run focused Python test**

Run:

```powershell
cd backend/python
python -m pytest smartbi/services/restaurant/tests/test_boss_decision_brief.py -q -k "does_not_reuse_factory_last_scenario_without_session or keeps_follow_up_session or explicit_keywords_override"
```

Expected: pass.

---

### Task 3: Frontend Contract Simplification and Verification

**Files:**
- Modify: `web-admin/src/views/smart-bi/AIQuery.vue`

**Interfaces:**
- Consumes: Java `/ai-intents/execute` response `resultData.source === "restaurant_owner_action"`, `sessionId`, `suggestedFollowups.ownerActionScenario`
- Produces: frontend does not become a second top-level router; it only sends owner-action context when it already has session/scenario continuity.

- [ ] **Step 1: Simplify frontend owner-action context decision**

Change `isRestaurantOwnerActionQuery` to only identify continuity context:

```ts
function shouldSendOwnerActionContext(query: string): boolean {
  if (!isRestaurantTenant.value) return false;
  const text = query.trim();
  return Boolean((ownerActionSessionId.value || pendingOwnerActionScenario.value) && isOwnerActionFollowupText(text));
}
```

Update `tryJavaIntentChat`:

```ts
const ownerActionQuery = shouldSendOwnerActionContext(query);
```

Do not add a second initial owner-action regex in frontend. Java remains authoritative for first-turn routing.

- [ ] **Step 2: Preserve chip scenario forwarding**

Keep `triggerRelatedFollowup(f.question, f.ownerActionScenario)` and `pendingOwnerActionScenario` unchanged so backend-provided scenario chips continue to work.

- [ ] **Step 3: Run frontend type/build check**

Run:

```powershell
cd web-admin
npm run build:check
```

Expected: pass.

---

### Task 4: Final Verification

**Files:**
- No new files beyond Tasks 1-3.

**Interfaces:**
- Produces: verified scoped diff.

- [ ] **Step 1: Run Java focused tests**

```powershell
cd backend/java/cretas-api
mvn -q "-Dtest=RestaurantOpsGoldRouteTest,IntentClarificationBusinessTypeFilterTest" test
```

- [ ] **Step 2: Run Python focused tests**

```powershell
cd backend/python
python -m pytest smartbi/services/restaurant/tests/test_boss_decision_brief.py -q -k "owner_action_chat"
```

- [ ] **Step 3: Run frontend build check**

```powershell
cd web-admin
npm run build:check
```

- [ ] **Step 4: Inspect diff scope**

```powershell
git status --short
git diff --stat
```

Expected changed files only:

```text
docs/superpowers/plans/2026-07-02-restaurant-domain-routing-cleanup.md
backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/RestaurantOpsGoldRouteTest.java
backend/python/smartbi/api/restaurant_sections.py
backend/python/smartbi/services/restaurant/tests/test_boss_decision_brief.py
web-admin/src/views/smart-bi/AIQuery.vue
```
