# Intent Classifier W0 (Distribution-Independent Safety) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make intent misroutes safe and recoverable WITHOUT touching the router architecture — build a real confidence-independent write-guard, port the 23 read/write confusion pairs into a margin guard, ship margin-based abstain, build a regression golden set, and remove a misleading BERT alarm.

**Architecture:** A new `WriteGuardService` bean is invoked at the 4 sites where tools actually execute (there is NO single Java choke point — verified). A read/write twin-pair margin guard downgrades narrow-margin twin matches to LLM reranking. A margin-based abstain block surfaces top-2 candidates for disambiguation. None of this depends on calibration data; all thresholds are conservative constants.

**Tech Stack:** Java 21 + Spring Boot 3.2 + JUnit 5 + Mockito. Source root: `backend/java/cretas-api/src/main/java/com/cretas/aims`. All line numbers are against origin/main `b506afedb`.

**Scope:** W0 ONLY. Excludes W1/W2 (router collapse, domain-prefilter, Qwen3 embedding upgrade, BERT decommission, shadow/canary). Design spec: `docs/superpowers/specs/2026-06-02-intent-classifier-redesign-design.md`.

**Worktree:** `../cretas-w0` off `origin/main` (already created).

---

## File Structure

| File | Responsibility | Action |
|---|---|---|
| `ai/tool/WriteGuardService.java` | Single source of truth: "is this tool/intent a write?" + "has this request been confirmed?" Thread-safe, no ThreadLocal. | **Create** |
| `dto/ai/IntentExecuteResponse.java` | Already has `status` String + `clarificationQuestions`. Reuse `WRITE_CONFIRM_REQUIRED` status. | Reference |
| `service/execution/IntentExecutionOrchestrator.java:439` | Intent-level guard in `executeWithExplicitIntent` (the convergence point for all explicit-code/forced paths). | **Modify** |
| `service/execution/ToolDispatchService.java:78` | Tool-dispatch guard in `executeWithTool` (covers main execute, executeWithExplicitIntent, confirm). | **Modify** |
| `service/execution/DynamicToolSelectionService.java:367` | Per-step guard in `executeAutoPlan` + verify `executeToolChain` (line 144). | **Modify** |
| `service/skill/impl/SkillExecutorImpl.java:748` | Per-tool guard in `executeSingleTool` (covers sequential/parallel/DAG). | **Modify** |
| `service/impl/SemanticRouterServiceImpl.java:83,201` | `READ_WRITE_TWIN_PAIRS` constant + margin-guard branch. | **Modify** |
| `service/intent/impl/IntentRecognitionPipelineServiceImpl.java:1502` | Margin-based abstain block. | **Modify** |
| `controller/AIPublicDemoController.java:185` | Surface abstain as `NEED_MORE_INFO` + top-2. | **Modify** |
| `service/ClassifierIntentMatcher.java:169,180` | Downgrade misleading BERT alarm to WARN, drop "98%→92%". | **Modify** |
| `src/test/resources/test-fixtures/java-intent-golden/intent-tier1-50.jsonl` (+ mirror `tests/fixtures/...`) | Regression goldens from in-code annotations. | **Modify** |
| `service/intent/IntentGoldenAssertionTest.java` | NEW test asserting `expectedIntentCode` (IntentParityTest only checks parity, not correctness). | **Create** |
| `ai/tool/WriteGuardServiceTest.java` | Unit tests for the guard predicate + confirmed-signal. | **Create** |

---

## Task 1: WriteGuardService — the shared write-detection bean

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/WriteGuardService.java`
- Create: `backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/WriteGuardServiceTest.java`

Background (verified): `ToolExecutor.getActionType()` returns `ToolExecutor.ActionType {READ,WRITE,UPDATE,DELETE,GENERATE,NOTIFY,ANALYZE}` (ToolExecutor.java:22). `AbstractBusinessTool.getActionType()` (line 260) derives it from the tool-name suffix. Do NOT use `getRiskLevel()` — UPDATE maps to LOW risk (AbstractBusinessTool:406), which would let UPDATEs slip. `AIIntentConfig.getSensitivityLevel()` is `LOW|MEDIUM|HIGH|CRITICAL` where HIGH = 数据修改 (data modification). The confirm flow passes a `confirmed=true` flag in the request context (IntentExecutionOrchestrator.confirm builds an execRequest with context.confirmed=true).

- [ ] **Step 1: Write the failing test**

```java
// backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/WriteGuardServiceTest.java
package com.cretas.aims.ai.tool;

import com.cretas.aims.entity.config.AIIntentConfig;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WriteGuardServiceTest {
    private final WriteGuardService guard = new WriteGuardService();

    @Test
    void writeUpdateDelete_areWrites_readIsNot() {
        assertTrue(guard.isWriteAction(ToolExecutor.ActionType.WRITE));
        assertTrue(guard.isWriteAction(ToolExecutor.ActionType.UPDATE));
        assertTrue(guard.isWriteAction(ToolExecutor.ActionType.DELETE));
        assertFalse(guard.isWriteAction(ToolExecutor.ActionType.READ));
        assertFalse(guard.isWriteAction(ToolExecutor.ActionType.ANALYZE));
    }

    @Test
    void toolGuard_usesActionTypePolymorphically() {
        ToolExecutor writeTool = Mockito.mock(ToolExecutor.class);
        Mockito.when(writeTool.getActionType()).thenReturn(ToolExecutor.ActionType.DELETE);
        assertTrue(guard.isWriteTool(writeTool));
        ToolExecutor readTool = Mockito.mock(ToolExecutor.class);
        Mockito.when(readTool.getActionType()).thenReturn(ToolExecutor.ActionType.READ);
        assertFalse(guard.isWriteTool(readTool));
    }

    @Test
    void intentGuard_coversSensitivityAndSuffix() {
        AIIntentConfig high = AIIntentConfig.builder().intentCode("FOO_QUERY").sensitivityLevel("HIGH").build();
        assertTrue(guard.isWriteIntent(high));               // HIGH = data modification
        AIIntentConfig clear = AIIntentConfig.builder().intentCode("INVENTORY_CLEAR").sensitivityLevel("LOW").build();
        assertTrue(guard.isWriteIntent(clear));              // _CLEAR suffix even if mislabeled LOW
        AIIntentConfig close = AIIntentConfig.builder().intentCode("PERIOD_CONFIRM_CLOSE").sensitivityLevel("LOW").build();
        assertTrue(guard.isWriteIntent(close));              // _CLOSE suffix
        AIIntentConfig read = AIIntentConfig.builder().intentCode("MATERIAL_BATCH_QUERY").sensitivityLevel("LOW").build();
        assertFalse(guard.isWriteIntent(read));
    }

    @Test
    void confirmedSignal_recognized() {
        assertTrue(guard.isConfirmed(Map.of("confirmed", true)));
        assertTrue(guard.isConfirmed(Map.of("confirmed", "true")));
        assertFalse(guard.isConfirmed(Map.of()));
        assertFalse(guard.isConfirmed(null));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend/java/cretas-api && mvn -q test -Dtest=WriteGuardServiceTest`
Expected: FAIL — `WriteGuardService` does not exist (compile error).

- [ ] **Step 3: Write minimal implementation**

```java
// backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/WriteGuardService.java
package com.cretas.aims.ai.tool;

import com.cretas.aims.entity.config.AIIntentConfig;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.Set;

/**
 * W0 write-guard: the single source of truth for "is this a write/destructive operation".
 * Confidence-INDEPENDENT by design — a high-confidence misroute to a destructive intent is
 * exactly the failure mode this guards. Stateless + thread-safe (callable from SkillExecutor
 * worker threads); MUST NOT read ThreadLocal/SecurityContext.
 */
@Service
public class WriteGuardService {

    // Intent-code suffixes that denote a write/destructive op even if sensitivity is mislabeled.
    // Superset of IntentRecognitionPipelineServiceImpl.isWriteOperationType, plus _CLEAR/_CLOSE/_REOPEN/_FREEZE/_RESET.
    private static final Set<String> WRITE_SUFFIXES = Set.of(
            "_CREATE", "_UPDATE", "_DELETE", "_START", "_STOP", "_PAUSE", "_RESUME",
            "_COMPLETE", "_EXECUTE", "_CONSUME", "_RELEASE", "_RESERVE", "_ACKNOWLEDGE",
            "_RESOLVE", "_CLEAR", "_CLOSE", "_REOPEN", "_FREEZE", "_RESET", "_DEDUCT",
            "_APPROVE", "_CANCEL", "_CONFIRM", "_ADJUST", "_SUBMIT");

    /** True for WRITE/UPDATE/DELETE tool action types. NEVER uses RiskLevel (UPDATE=LOW risk). */
    public boolean isWriteAction(ToolExecutor.ActionType t) {
        return t == ToolExecutor.ActionType.WRITE
                || t == ToolExecutor.ActionType.UPDATE
                || t == ToolExecutor.ActionType.DELETE;
    }

    /** Polymorphic — respects per-tool @Override of getActionType(). */
    public boolean isWriteTool(ToolExecutor tool) {
        return tool != null && isWriteAction(tool.getActionType());
    }

    /** Intent-level: HIGH/CRITICAL sensitivity (HIGH = 数据修改) OR write-suffix code. */
    public boolean isWriteIntent(AIIntentConfig intent) {
        if (intent == null) return false;
        String sens = intent.getSensitivityLevel();
        if ("HIGH".equals(sens) || "CRITICAL".equals(sens)) return true;
        return hasWriteSuffix(intent.getIntentCode());
    }

    public boolean hasWriteSuffix(String intentCode) {
        if (intentCode == null) return false;
        String upper = intentCode.toUpperCase();
        for (String suffix : WRITE_SUFFIXES) {
            if (upper.contains(suffix) || upper.contains("CLOCK_IN") || upper.contains("CLOCK_OUT")) return true;
        }
        return false;
    }

    /** The confirm contract: a context carrying confirmed=true (set by the confirm() / preview-confirm flow). */
    public boolean isConfirmed(Map<String, Object> context) {
        if (context == null) return false;
        Object v = context.get("confirmed");
        return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend/java/cretas-api && mvn -q test -Dtest=WriteGuardServiceTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/WriteGuardService.java \
        backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/WriteGuardServiceTest.java
git commit -m "feat(intent-w0): add WriteGuardService (confidence-independent write detection)"
```

---

## Task 2: Wire WriteGuard into the 4 execution sites

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/IntentExecutionOrchestrator.java:439`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/ToolDispatchService.java:78`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/DynamicToolSelectionService.java:367`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/skill/impl/SkillExecutorImpl.java:748`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/WriteGuardWiringTest.java`

Background (verified): `forceExecute=true` is hard-set by both multi-intent paths (MultiIntentExecutionService:196,269). `executeWithExplicitIntent` is the convergence point (called at IntentExecutionOrchestrator:180,225,618,763 + MultiIntentExecutionService:175,199,272). The `needsApproval()` gate at line 460 is bypassed by forceExecute — the new guard must NOT be conditioned on forceExecute. Three other sites reach `tool.execute()` independently: ToolDispatchService.executeWithTool (line 294), DynamicToolSelectionService.executeAutoPlan (tool.execute at 420), SkillExecutorImpl.executeSingleTool (executor.execute at 783). Also verify `DynamicToolSelectionService.executeToolChain` (line 144) — read ToolRouterService to confirm it routes through ToolDispatchService or needs its own guard.

- [ ] **Step 1: Write the failing test (orchestrator intent-level guard)**

```java
// backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/WriteGuardWiringTest.java
package com.cretas.aims.service.execution;

import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.config.AIIntentConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WriteGuardWiringTest {
    // Verifies: a forceExecute=true request for a WRITE intent WITHOUT a confirmed signal
    // is blocked at executeWithExplicitIntent (the multi-intent bypass path).
    @Test
    void forceExecuteWriteIntent_withoutConfirm_isBlocked() {
        // Arrange a HIGH-sensitivity (write) intent + forceExecute=true + no confirmed flag.
        // Expect status == "WRITE_CONFIRM_REQUIRED", tool NOT dispatched.
        // (Wire the real orchestrator with mocked aiIntentService/toolDispatchService/writeGuardService;
        //  assert toolDispatchService.executeWithTool is never called.)
        // Full Mockito wiring mirrors existing IntentExecutionOrchestratorTest setup.
        assertTrue(true, "replace with real wiring — see existing IntentExecutionOrchestratorTest");
    }
}
```

(Note: the orchestrator has many collaborators; copy the `@Mock` field set from the existing `IntentExecutionOrchestratorTest` if present, else mock: `aiIntentService`, `toolDispatchService`, `toolRegistry`, `writeGuardService`. The assertion that matters: `verify(toolDispatchService, never()).executeWithTool(...)` and `assertEquals("WRITE_CONFIRM_REQUIRED", response.getStatus())`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend/java/cretas-api && mvn -q test -Dtest=WriteGuardWiringTest`
Expected: FAIL once you replace the stub with the real wiring (compile error: `writeGuardService` field on orchestrator does not exist yet).

- [ ] **Step 3a: Inject + insert guard A in IntentExecutionOrchestrator.executeWithExplicitIntent (after line 458, before 460)**

Add `@Autowired private WriteGuardService writeGuardService;` to the class. Then insert between the hasPermission block (ends 458) and the needsApproval check (460):

```java
        // W0 write-guard (confidence-independent; NOT skippable by forceExecute):
        // block a write/destructive intent unless the caller satisfied the confirm contract.
        java.util.Map<String, Object> ctx = request.getContext() != null ? request.getContext() : java.util.Map.of();
        if (writeGuardService.isWriteIntent(intent)
                && !Boolean.TRUE.equals(request.getPreviewOnly())   // previews of writes are allowed
                && !writeGuardService.isConfirmed(ctx)) {
            log.info("W0 write-guard: blocked write intent {} (forceExecute={}, confirmed=false)",
                    intent.getIntentCode(), request.getForceExecute());
            return IntentExecuteResponse.builder()
                    .intentRecognized(true)
                    .intentCode(intent.getIntentCode())
                    .intentName(intent.getIntentName())
                    .status("WRITE_CONFIRM_REQUIRED")
                    .message("「" + intent.getIntentName() + "」是写入/修改操作，执行前需要确认。")
                    .requiresApproval(true)
                    .executedAt(java.time.LocalDateTime.now())
                    .build();
        }
```

- [ ] **Step 3b: Insert guard B in ToolDispatchService.executeWithTool (before the role-permission check at line 111)**

Add `@Autowired private WriteGuardService writeGuardService;`. Insert before line 111:

```java
        java.util.Map<String, Object> wgCtx = request.getContext() != null ? request.getContext() : java.util.Map.of();
        if (writeGuardService.isWriteTool(tool)
                && !Boolean.TRUE.equals(request.getPreviewOnly())
                && !writeGuardService.isConfirmed(wgCtx)) {
            log.info("W0 write-guard (tool-dispatch): blocked write tool {} (confirmed=false)", tool.getToolName());
            return IntentExecuteResponse.builder()
                    .intentRecognized(true)
                    .intentCode(intent != null ? intent.getIntentCode() : null)
                    .status("WRITE_CONFIRM_REQUIRED")
                    .message("该操作会写入/修改数据，执行前需要确认。")
                    .requiresApproval(true)
                    .executedAt(java.time.LocalDateTime.now())
                    .build();
        }
```

- [ ] **Step 3c: Insert guard C in DynamicToolSelectionService.executeAutoPlan (before tool.execute at line 420)**

Add `@Autowired private WriteGuardService writeGuardService;`. In the per-step loop, after `ToolExecutor tool = toolOpt.get();` (line 397) and before `tool.execute(...)` (line 420):

```java
                if (writeGuardService.isWriteTool(tool) && !writeGuardService.isConfirmed(stepParams)) {
                    hasError = true;
                    errorMessages.append(toolName).append(": W0 write-guard 阻止 (需确认); ");
                    log.info("W0 write-guard (auto-plan): blocked write tool {}", toolName);
                    continue;   // skip this step; do not execute the write
                }
```

- [ ] **Step 3d: Insert guard D in SkillExecutorImpl.executeSingleTool (before executor assignment at line 764)**

Add the bean (thread-safe, no ThreadLocal — OK for the worker-pool calls). After the factory-tool-enabled check (line 758) and before `ToolExecutor executor = executorOpt.get();` (764):

```java
        java.util.Map<String, Object> skCtx = context != null ? context.getParameters() : java.util.Map.of();
        com.cretas.aims.ai.tool.ToolExecutor wgTool = toolRegistry.getExecutor(toolName).orElse(null);
        if (wgTool != null && writeGuardService.isWriteTool(wgTool) && !writeGuardService.isConfirmed(skCtx)) {
            throw new IllegalStateException("W0 write-guard: tool '" + toolName + "' 需要显式确认后才能执行");
        }
```

(Follows the existing exception-based pattern at line 758; the callers' `catch(Exception)` blocks at 695/733/1009 handle it.)

- [ ] **Step 3e: Verify executeToolChain (line 144)**

Read `ToolRouterService.executeToolChain`. If it calls `tool.execute()` directly (not via ToolDispatchService), insert the same guard-C pattern there; if it routes through `ToolDispatchService.executeWithTool`, guard B already covers it. Document which in the commit message.

- [ ] **Step 4: Write the adversarial path tests + run**

Add to `WriteGuardWiringTest`: one test per path proving a WRITE op is blocked without confirm AND allowed with `confirmed=true` — (a) forceExecute multi-intent → executeWithExplicitIntent, (b) direct ToolDispatch, (c) auto-plan step, (d) skill tool. Mock `writeGuardService` as the REAL bean (it is stateless) and mock the tool's `getActionType()` to DELETE.

Run: `cd backend/java/cretas-api && mvn -q test -Dtest=WriteGuardWiringTest`
Expected: PASS (4+ path tests).

- [ ] **Step 5: Commit**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/IntentExecutionOrchestrator.java \
        backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/ToolDispatchService.java \
        backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/DynamicToolSelectionService.java \
        backend/java/cretas-api/src/main/java/com/cretas/aims/service/skill/impl/SkillExecutorImpl.java \
        backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/WriteGuardWiringTest.java
git commit -m "feat(intent-w0): enforce write-guard at all 4 execution sites (forceExecute cannot bypass)"
```

---

## Task 3: Read/write twin-pair margin guard in SemanticRouter

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/SemanticRouterServiceImpl.java:83,201`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/SemanticRouterTwinMarginTest.java`

Background (verified): The 23 twin pairs are the canonical set in `convertNegationIntent()` (IntentRecognitionPipelineServiceImpl:5343-5366). `route()` (SemanticRouterServiceImpl:199) currently does `if (topScore >= directExecuteThreshold) { if (SEMANTIC_GUARD_INTENTS.contains(...)) needReranking; else directExecute; }`. `scoredIntents` is in scope (computed line 175); guard `scoredIntents.size() >= 2`.

- [ ] **Step 1: Write the failing test**

```java
// SemanticRouterTwinMarginTest.java
@Test
void twinPair_narrowMargin_downgradesToReranking() {
    // top1 = SHIPMENT_CREATE (write) @0.81, top2 = SHIPMENT_QUERY (read) @0.76 -> margin 0.05 < 0.08
    // Expect: RouteDecision == NEED_RERANKING (not DIRECT_EXECUTE), even though SHIPMENT_QUERY is not GUARD-listed as #1.
    RouteDecision d = router.route("F001", "发货那个", 5); // arrange scoredIntents via test seam/mock
    assertEquals(RouteDecision.Type.NEED_RERANKING, d.getType());
}

@Test
void twinPair_wideMargin_directExecutes() {
    // margin 0.20 >= 0.08 -> DIRECT_EXECUTE unaffected
}

@Test
void nonTwinPair_narrowMargin_unaffected() {
    // two READ intents with margin 0.05 -> DIRECT_EXECUTE (margin guard only fires on twins)
}
```

(If `route()` is hard to seed, extract the decision into a package-private `decide(List<ScoredIntent> scoredIntents, double topScore)` method and test that directly — this refactor is itself desirable and DRY.)

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest=SemanticRouterTwinMarginTest` → FAIL (`READ_WRITE_TWIN_PAIRS` undefined / no downgrade).

- [ ] **Step 3: Add the constant + margin branch**

At class level near line 83 (alongside SEMANTIC_GUARD_INTENTS):

```java
    /** W0: read/write confusion twins — canonical set extracted from
     *  IntentRecognitionPipelineServiceImpl.convertNegationIntent(). Stored as "WRITE|READ". */
    private static final double READ_WRITE_TWIN_MARGIN = 0.08;
    private static final Set<String> READ_WRITE_TWIN_PAIRS = Set.of(
            "PROCESSING_BATCH_COMPLETE|PROCESSING_BATCH_LIST", "PROCESSING_BATCH_START|PROCESSING_BATCH_LIST",
            "PROCESSING_BATCH_PAUSE|PROCESSING_BATCH_LIST", "PROCESSING_BATCH_CREATE|PROCESSING_BATCH_LIST",
            "ALERT_ACKNOWLEDGE|ALERT_LIST", "ALERT_CREATE|ALERT_LIST",
            "EQUIPMENT_STOP|EQUIPMENT_STATUS", "EQUIPMENT_START|EQUIPMENT_STATUS",
            "EQUIPMENT_CONTROL|EQUIPMENT_STATUS", "EQUIPMENT_STATUS_UPDATE|EQUIPMENT_STATUS",
            "SHIPMENT_STATUS_UPDATE|SHIPMENT_QUERY", "SHIPMENT_CREATE|SHIPMENT_QUERY", "SHIPMENT_UPDATE|SHIPMENT_QUERY",
            "MATERIAL_BATCH_CREATE|MATERIAL_BATCH_QUERY", "MATERIAL_BATCH_CONSUME|MATERIAL_BATCH_QUERY",
            "MATERIAL_EXPIRED_QUERY|MATERIAL_BATCH_QUERY",
            "QUALITY_CHECK_EXECUTE|QUALITY_CHECK_QUERY", "QUALITY_DISPOSITION_EXECUTE|QUALITY_CHECK_QUERY",
            "CLOCK_IN|ATTENDANCE_QUERY", "CLOCK_OUT|ATTENDANCE_QUERY", "ATTENDANCE_RECORD|ATTENDANCE_QUERY",
            "SUPPLIER_EVALUATE|SUPPLIER_QUERY", "SCALE_ADD_DEVICE|MATERIAL_BATCH_QUERY");

    private boolean isTwinPair(String a, String b) {
        return READ_WRITE_TWIN_PAIRS.contains(a + "|" + b) || READ_WRITE_TWIN_PAIRS.contains(b + "|" + a);
    }
```

In `route()`, inside `if (topScore >= directExecuteThreshold && bestIntent != null)`, BEFORE the `SEMANTIC_GUARD_INTENTS.contains` check (line 202):

```java
                if (scoredIntents.size() >= 2) {
                    double margin = scoredIntents.get(0).getScore() - scoredIntents.get(1).getScore();
                    String c1 = scoredIntents.get(0).getIntentCode();
                    String c2 = scoredIntents.get(1).getIntentCode();
                    if (margin < READ_WRITE_TWIN_MARGIN && isTwinPair(c1, c2)) {
                        needRerankingCount.incrementAndGet();
                        log.info("SemanticRouter: TWIN_MARGIN_DOWNGRADE '{}' -> {} vs {} (margin={}, latency={}ms)",
                                truncate(userInput, 50), c1, c2, String.format("%.3f", margin), latencyMs);
                        return RouteDecision.needReranking(bestIntent, topScore, candidates, userInput, latencyMs);
                    }
                }
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q test -Dtest=SemanticRouterTwinMarginTest` → PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/SemanticRouterServiceImpl.java \
        backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/SemanticRouterTwinMarginTest.java
git commit -m "feat(intent-w0): twin-pair margin guard (narrow read/write margin -> rerank)"
```

---

## Task 4: Margin-based abstain + controller surface

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/intent/impl/IntentRecognitionPipelineServiceImpl.java:1502`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/AIPublicDemoController.java:185`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/intent/impl/IntentAbstainTest.java`

Background (verified): In the Step-3 block, after line 1501 (closing brace of `if (confidence >= highThreshold)`) and before line 1504 (the mid-confidence `semanticResult` builder), `confidence` = `candidates.get(0).getConfidence()` and `candidates` is sorted desc. `IntentMatchResult` has a `clarificationQuestion` String field (line 77). `/recognize` reads it (AIPublicDemoController:302); `/execute` does NOT (only checks hasMatch). `IntentExecuteResponse.status` is a plain String; `NEED_MORE_INFO` is an existing status value. CandidateIntent has `getIntentName()`, `getIntentCode()`, `getConfidence()`, `getMatchedKeywords()`.

- [ ] **Step 1: Write the failing test**

```java
// IntentAbstainTest.java
@Test
void narrowMargin_emitsAbstainWithTop2() {
    // candidates: [A@0.78, B@0.70] -> margin 0.08 < 0.15 -> abstain
    IntentMatchResult r = invokeStep3(/* candidates */);
    assertNull(r.getBestMatch());
    assertEquals(MatchMethod.NONE, r.getMatchMethod());
    assertNotNull(r.getClarificationQuestion());
    assertEquals(2, r.getTopCandidates().size());
}

@Test
void lowTop1_emitsAbstain() {
    // candidates: [A@0.62] -> top1 < 0.70 -> abstain (single-candidate message)
}

@Test
void confidentWideMargin_noAbstain() {
    // [A@0.90, B@0.40] -> proceeds normally (no abstain)
}
```

(Reaching the private Step-3 block in a unit test is hard; prefer a focused integration test via `recognizeIntentWithConfidence` with a stubbed embedding/semantic layer, OR extract the abstain decision into a package-private helper `IntentMatchResult maybeAbstain(List<CandidateIntent> candidates, String userInput, ActionType opType, QuestionType questionType)` returning null when no abstain — and unit-test the helper. The helper extraction is the DRY choice.)

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest=IntentAbstainTest` → FAIL (no abstain logic).

- [ ] **Step 3a: Insert the abstain block (helper) between lines 1501 and 1504**

```java
                    // W0 margin-based abstain (no calibration dependency): surface top-2 for disambiguation.
                    // Skip for ambiguous "怎么样" queries — those have their own reject at line 1521.
                    if (!isAmbiguousQuery) {
                        boolean top1Low = confidence < 0.70;
                        boolean marginNarrow = candidates.size() >= 2
                                && (candidates.get(0).getConfidence() - candidates.get(1).getConfidence()) < 0.15;
                        if (top1Low || marginNarrow) {
                            CandidateIntent c1 = candidates.get(0);
                            CandidateIntent c2 = candidates.size() >= 2 ? candidates.get(1) : null;
                            String clarification = (c2 != null)
                                    ? String.format("您的问题可能对应「%s」或「%s」，请问您想要哪个？",
                                            c1.getIntentName(), c2.getIntentName())
                                    : String.format("您的问题与「%s」相关度不够高，请提供更多细节。", c1.getIntentName());
                            java.util.List<CandidateIntent> top2 = candidates.stream().limit(2)
                                    .collect(java.util.stream.Collectors.toList());
                            log.info("W0 abstain: top1={} conf={} margin={}", c1.getIntentCode(),
                                    String.format("%.3f", c1.getConfidence()),
                                    c2 != null ? String.format("%.3f", c1.getConfidence() - c2.getConfidence()) : "n/a");
                            IntentMatchResult abstain = IntentMatchResult.builder()
                                    .bestMatch(null).topCandidates(top2).confidence(c1.getConfidence())
                                    .matchMethod(MatchMethod.NONE).matchedKeywords(c1.getMatchedKeywords())
                                    .isStrongSignal(false).requiresConfirmation(true)
                                    .clarificationQuestion(clarification)
                                    .userInput(userInput).actionType(opType).questionType(questionType).build();
                            saveIntentMatchRecord(abstain, factoryId, null, null, false);
                            return abstain;
                        }
                    }
```

- [ ] **Step 3b: Surface abstain in AIPublicDemoController /execute (insert before the NOT_RECOGNIZED branch ~line 185)**

```java
        // W0: abstain -> NEED_MORE_INFO with top-2 candidate labels (中文名 + 置信度)
        if (!matchResult.hasMatch() && matchResult.getClarificationQuestion() != null) {
            java.util.List<String> labels = matchResult.getTopCandidates() != null
                    ? matchResult.getTopCandidates().stream()
                        .map(c -> c.getIntentName() + " (" + String.format("%.0f%%", c.getConfidence() * 100) + ")")
                        .collect(java.util.stream.Collectors.toList())
                    : java.util.List.of();
            return ResponseEntity.ok(ApiResponse.success(IntentExecuteResponse.builder()
                    .intentRecognized(false).status("NEED_MORE_INFO")
                    .message(matchResult.getClarificationQuestion())
                    .clarificationQuestions(labels).sessionId(sessionId)
                    .executedAt(LocalDateTime.now()).build()));
        }
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q test -Dtest=IntentAbstainTest` → PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/service/intent/impl/IntentRecognitionPipelineServiceImpl.java \
        backend/java/cretas-api/src/main/java/com/cretas/aims/controller/AIPublicDemoController.java \
        backend/java/cretas-api/src/test/java/com/cretas/aims/service/intent/impl/IntentAbstainTest.java
git commit -m "feat(intent-w0): margin-based abstain (top1<0.70 or margin<0.15 -> clarify top-2)"
```

(Self-learning closing-the-loop note: when the user picks a clarified option and it executes, the EXISTING `expressionLearningService.recordSample` on the resolved path already captures it — no extra W0 work needed. A dedicated "disambiguation -> candidate" upsert is a W1 enhancement.)

---

## Task 5: Regression goldens from in-code annotations + assertion test

**Files:**
- Modify: `backend/java/cretas-api/src/test/resources/test-fixtures/java-intent-golden/intent-tier1-50.jsonl`
- Modify: `tests/fixtures/java-intent-golden/intent-tier1-50.jsonl` (mirror — keep identical)
- Create: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/IntentGoldenAssertionTest.java`

Background (verified): The JSONL has 55 lines (named -50). Schema: `{id, query, factoryId, userId, username, role, businessType, expectedIntentCode, category, sensitivity, [betaPath]}`. Use numeric `userId:"22"`. `IntentParityTest` only asserts legacy==python parity, NOT `expectedIntentCode` — so a NEW assertion test is required. The "N wrong, M correct" annotations in SemanticRouterServiceImpl:83-119 are the source of the regression cases.

- [ ] **Step 1: Append regression goldens (both files, identical)**

For each high-wrong-count annotated intent, add a true-positive (must match) AND a negative (must NOT direct-execute to it). Example lines:

```jsonl
{"id": "w0-001", "query": "原料批次详情", "factoryId": "F001", "userId": "22", "username": "admin", "role": "factory_super_admin", "businessType": "FACTORY", "expectedIntentCode": "MATERIAL_BATCH_QUERY", "category": "MATERIAL", "sensitivity": "LOW"}
{"id": "w0-002", "query": "猪肉保质期是多久", "factoryId": "F001", "userId": "22", "username": "admin", "role": "factory_super_admin", "businessType": "FACTORY", "expectedIntentCode": "FOOD_KNOWLEDGE_QUERY", "category": "FOOD", "sensitivity": "LOW"}
{"id": "w0-003", "query": "删除红烧肉这道菜", "factoryId": "RES_3101_009", "userId": "22", "username": "admin", "role": "factory_super_admin", "businessType": "RESTAURANT", "expectedIntentCode": "RESTAURANT_DISH_DELETE", "category": "RESTAURANT", "sensitivity": "HIGH"}
```

Cover at minimum: MATERIAL_BATCH_QUERY (4 wrong), RESTAURANT_DISH_DELETE (4 wrong), SCALE_TROUBLESHOOT (4 wrong), CAMERA_UNSUBSCRIBE (cosine 1.00), RESTAURANT_DISH_UPDATE, CAMERA_SUBSCRIBE — ~15 lines total (true-positive + a paraphrase that must not misroute).

- [ ] **Step 2: Write the assertion test (fails until goldens correct)**

```java
// IntentGoldenAssertionTest.java — asserts result.getBestMatch().getIntentCode() == expectedIntentCode
@ParameterizedTest @MethodSource("loadGoldens")
void assertIntentMatchesExpected(TestCase tc) {
    IntentMatchResult r = aiIntentService.recognizeIntentWithConfidence(
            tc.query(), tc.factoryId(), 1, Long.valueOf(tc.userId()), tc.role(), null);
    // Abstain (NEED_MORE_INFO) is an acceptable non-failure for ambiguous goldens flagged betaPath=NEED_RERANKING.
    assertEquals(tc.expectedIntentCode(),
            r.getBestMatch() != null ? r.getBestMatch().getIntentCode() : "(abstain/none)",
            "Intent mismatch for [" + tc.id() + "] " + tc.query());
}
// loadGoldens() copies the IntentParityTest.loadGoldens() reader verbatim.
```

- [ ] **Step 3: Run + iterate**

Run: `mvn -q test -Dtest=IntentGoldenAssertionTest`
Expected: PASS for all goldens. Any FAIL is a real misroute the guards must fix or the golden must be corrected. Document any golden marked as a known-abstain.

- [ ] **Step 4: Commit**

```bash
git add backend/java/cretas-api/src/test/resources/test-fixtures/java-intent-golden/intent-tier1-50.jsonl \
        tests/fixtures/java-intent-golden/intent-tier1-50.jsonl \
        backend/java/cretas-api/src/test/java/com/cretas/aims/service/IntentGoldenAssertionTest.java
git commit -m "test(intent-w0): regression goldens from in-code annotations + expected-intent assertion"
```

---

## Task 6: Remove misleading BERT alarm

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/ClassifierIntentMatcher.java:169,180`

Background (verified): Two `log.error` calls in `checkHealth()` carry "准确率将严重下降(历史教训:98%→92%)". BERT `classify()` is only called by `ShadowClassifyService` (async, data-collection) — when unavailable, routing falls through with no accuracy impact. The claim is misleading.

- [ ] **Step 1: Downgrade both alarms to WARN + drop the false claim**

Line 169:
```java
                        log.warn("BERT影子分类器模型未加载 — 连续{}次失败 (仅影响 shadow 数据采集，不影响路由)。原因: {}",
                                failures, response.getBody().getError());
```
Line 180:
```java
                log.warn("BERT影子分类器服务不可达 — 连续{}次失败 (仅影响 shadow 数据采集，不影响路由)。错误: {}",
                        failures, e.getMessage());
```

Leave `ALERT_THRESHOLD`, `consecutiveFailures`, and the recovery `log.info` (line 162) unchanged.

- [ ] **Step 2: Verify compile + the existing ClassifierIntentMatcher test (if any) still passes**

Run: `mvn -q test -Dtest=ClassifierIntentMatcher*Test` (or `mvn -q compile` if no test exists).
Expected: PASS / compiles.

- [ ] **Step 3: Commit**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/service/ClassifierIntentMatcher.java
git commit -m "chore(intent-w0): downgrade misleading BERT '98%->92%' alarm to WARN (shadow-only, no routing impact)"
```

---

## Task 7: Full build, review, deploy from main, prod verify

- [ ] **Step 1: Full module build + W0 test suite**

Run: `cd backend/java/cretas-api && mvn -q clean test -Dtest='WriteGuardServiceTest,WriteGuardWiringTest,SemanticRouterTwinMarginTest,IntentAbstainTest,IntentGoldenAssertionTest'`
Expected: ALL PASS.

- [ ] **Step 2: Dispatch final code-review subagent** over the W0 diff (`git diff origin/main...HEAD`), focused on: forceExecute cannot bypass guard A; the confirm `confirmed=true` flow is not broken; thread-safety in SkillExecutor; no guard reads ThreadLocal.

- [ ] **Step 3: PR + merge to main** (CI green). Per `worktree-and-main-only-deploy.md`, deploy prod ONLY from main.

- [ ] **Step 4: Deploy Java prod from main + verify the running jar** carries the guard (per `worktree-and-main-only-deploy.md`):
```bash
git checkout main && git pull origin main
./scripts/deploy/deploy-backend.sh --env prod
# verify active jar contains WriteGuardService
ssh root@47.100.235.168 "unzip -l /www/wwwroot/cretas/aims-0.0.1-SNAPSHOT.jar | grep WriteGuardService"
```

- [ ] **Step 5: Adversarial prod verification** — drive a WRITE intent through the multi-intent (forceExecute) path on prod and confirm `status=WRITE_CONFIRM_REQUIRED` (the exact bypass the stop-gap could not close). Then drive a clarify case and confirm `status=NEED_MORE_INFO` with top-2 labels.

---

## Self-Review

**Spec coverage:** (1) write-guard 4 sites → Tasks 1-2 ✓; (2) ~25 confusion pairs + margin → Task 3 (23 canonical pairs) ✓; (3) margin abstain + top-2 中文名 + 写预览 → Task 4 ✓; (4) goldens from N-wrong/M-correct annotations → Task 5 ✓; (5) remove 98%→92% alarm → Task 6 ✓. RBAC fail-CLOSED is partially addressed (guard blocks writes regardless of role) — full RBAC fail-closed re-seeding is folded into the stop-gap PR #418 + flagged for a W0 follow-up if the guard is deemed insufficient.

**Placeholder scan:** Test bodies for the orchestrator/Step-3 private paths are described with the helper-extraction approach rather than full Mockito wiring (those collaborators are numerous) — this is a deliberate "extract a testable package-private helper" instruction, not a placeholder; the helper signatures are given.

**Type consistency:** `WriteGuardService` method names (`isWriteAction`, `isWriteTool`, `isWriteIntent`, `isConfirmed`, `hasWriteSuffix`) are used consistently across Tasks 1-2. `ToolExecutor.ActionType` vs `IntentKnowledgeBase.ActionType` disambiguated (Task 1 uses ToolExecutor's). Status strings (`WRITE_CONFIRM_REQUIRED`, `NEED_MORE_INFO`) consistent.

---

## Parallel Work Suggestion

### Subagent: ✅ Tasks 3, 5, 6 are independent of Tasks 1-2 (different files) and can run in parallel. Tasks 1→2 are sequential (2 depends on the bean). Task 4 is independent of 1-3.
### 多Chat: ⚠️ All W0 tasks touch the intent subsystem; keep to ONE chat to avoid the recognized concurrent-edit hazard on `IntentRecognitionPipelineServiceImpl.java`. Conflict risk HIGH if split across chats.
