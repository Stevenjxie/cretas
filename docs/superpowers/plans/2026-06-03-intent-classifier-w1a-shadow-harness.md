# Intent Classifier W1a — Shadow Router Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Run the existing `SemanticRouterService.route()` as a CHALLENGER alongside the live champion recognition, logging champion-vs-challenger agreement to `intent_match_records` shadow columns — with ZERO impact on the live result — so we can decide (from real prod data) whether the full router rebuild (W1c) is worth it.

**Architecture:** A new `@Async` `ShadowRouterHarnessService` (mirroring the existing dead `ShadowClassifyService`) is fired inside `saveIntentMatchRecord()` after the record is persisted (so the recordId is available). It runs the challenger router off the request thread, compares to the champion's intent, and writes the result via the existing `IntentMatchRecordRepository.updateShadowResult(...)`. Gated by a new flag defaulting OFF. Built on the **existing bge-base-zh-v1.5 embeddings** (no model change — per W1 decision).

**Tech Stack:** Java 21 + Spring Boot 3.2 + JPA + Flyway. Lines vs origin/main `126c665b4`.

**Scope:** W1a ONLY (shadow harness + the agreement dataset). Excludes W1b (domain prefilter), W1c (new kNN router), W1d (embedding upgrade — dropped for now), W1e (calibration + BERT decommission). Design spec: `docs/superpowers/specs/2026-06-02-intent-classifier-redesign-design.md`.

**Worktree:** `../cretas-w1a` off origin/main.

---

## File Structure

| File | Responsibility | Action |
|---|---|---|
| `resources/db/flyway/V20260603_01__intent_records_shadow_columns.sql` | Add 7 shadow columns (`ADD COLUMN IF NOT EXISTS` — idempotent; no-op on prod which already has them, fixes test/CI fresh DBs) | **Create** |
| `service/impl/ShadowRouterHarnessService.java` | `@Async` challenger-shadow: route() → compare → updateShadowResult | **Create** |
| `service/intent/impl/IntentRecognitionPipelineServiceImpl.java` | New `@Value` flag + fire the async shadow in `saveIntentMatchRecord` after save | **Modify** |
| `service/impl/ShadowRouterHarnessServiceTest.java` | Unit tests (agree / disagree / null-challenger / flag-off) | **Create** |

`IntentMatchRecordRepository.updateShadowResult(...)` + `markZpdBoundary(...)` already exist (native SQL) — REUSE, no change.

---

## Task 1: Flyway migration for shadow columns

**Files:**
- Create: `backend/java/cretas-api/src/main/resources/db/flyway/V20260603_01__intent_records_shadow_columns.sql`

Background (verified): `IntentMatchRecordRepository.updateShadowResult` (line 344) writes `shadow_intent_code, shadow_confidence, shadow_agreed, classifier_entropy, classifier_margin, classifier_max_logit` via native SQL; `markZpdBoundary` writes `zpd_boundary`. These columns are LIVE on prod (added by hand) but are in NO Flyway migration → fresh/test/CI DBs lack them → the native UPDATE fails there. The migration must be idempotent (`IF NOT EXISTS`) so it's a no-op on prod. Flyway dir is `db/flyway/` (NOT `db/migration` — a pre-commit hook enforces this). Confirm the next free version `>` the current max (`ls db/flyway/ | tail` — the W0/stop-gap used V20260909_01; check the actual max and pick a non-colliding version, e.g. V20260911_01 if 0909/0910 are taken — DO fetch origin/main + ls first to avoid the recurring version-collision).

- [ ] **Step 1: Verify the current max flyway version + pick a free one**

Run: `ls backend/java/cretas-api/src/main/resources/db/flyway/ | sort | tail -5`
Use a version strictly greater than the max (and greater than any unmerged sibling — `git log origin/main --oneline -10 | grep -i flyway`). The filename below uses `V20260911_01` as a placeholder — REPLACE with the actual next free version.

- [ ] **Step 2: Create the migration**

```sql
-- W1a shadow router harness: columns for champion-vs-challenger agreement logging.
-- LIVE on prod already (hand-added); this brings them under Flyway so test/CI/fresh DBs have them.
-- Idempotent (IF NOT EXISTS) → no-op on prod.
ALTER TABLE intent_match_records ADD COLUMN IF NOT EXISTS shadow_intent_code   VARCHAR(64);
ALTER TABLE intent_match_records ADD COLUMN IF NOT EXISTS shadow_confidence    NUMERIC(6,4);
ALTER TABLE intent_match_records ADD COLUMN IF NOT EXISTS shadow_agreed        BOOLEAN;
ALTER TABLE intent_match_records ADD COLUMN IF NOT EXISTS classifier_entropy   NUMERIC(8,4);
ALTER TABLE intent_match_records ADD COLUMN IF NOT EXISTS classifier_margin    NUMERIC(8,4);
ALTER TABLE intent_match_records ADD COLUMN IF NOT EXISTS classifier_max_logit NUMERIC(10,6);
ALTER TABLE intent_match_records ADD COLUMN IF NOT EXISTS zpd_boundary         BOOLEAN DEFAULT FALSE;
```
(Match the exact column types the prod columns use if discoverable; the above are safe supersets. `VARCHAR(64)` covers all intent codes.)

- [ ] **Step 3: Verify it applies to a fresh DB (or at least parses)**

Run: `cd backend/java/cretas-api && ./mvnw.cmd -o -q flyway:validate 2>&1 | tail -5` (or confirm syntax with a local psql dry-run if available). Expected: no error. (Full flyway:migrate against a fresh DB happens in CI / the test deploy.)

- [ ] **Step 4: Commit**

```bash
git add backend/java/cretas-api/src/main/resources/db/flyway/V20260911_01__intent_records_shadow_columns.sql
git commit -m "feat(intent-w1a): flyway migration for shadow router columns (idempotent, fixes fresh-DB)"
```

---

## Task 2: ShadowRouterHarnessService

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/ShadowRouterHarnessService.java`
- Create: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/ShadowRouterHarnessServiceTest.java`

Background (verified signatures): `SemanticRouterService.route(String factoryId, String userInput, int topN)` → `RouteDecision` with `getBestMatchIntentCode()` (null when NEED_FULL_LLM), `getTopScore()` (double), `getRouteType()` (enum). `IntentMatchRecordRepository.updateShadowResult(String recordId, String shadowIntent, BigDecimal shadowConf, boolean agreed, BigDecimal entropy, BigDecimal margin, BigDecimal maxLogit)` + `markZpdBoundary(String recordId)`. The BERT analog `ShadowClassifyService` uses `@Async("aiAnalysisExecutor") @Transactional` + a sample-rate gate (`matchingConfig.getShadowModeSampleRate()`). Mirror it.

- [ ] **Step 1: Write the failing test**

```java
// ShadowRouterHarnessServiceTest.java
package com.cretas.aims.service.impl;

import com.cretas.aims.dto.intent.RouteDecision;
import com.cretas.aims.repository.IntentMatchRecordRepository;
import com.cretas.aims.service.SemanticRouterService;
import com.cretas.aims.config.IntentMatchingConfig;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import java.math.BigDecimal;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class ShadowRouterHarnessServiceTest {
    @Mock SemanticRouterService semanticRouterService;
    @Mock IntentMatchRecordRepository recordRepository;
    @Mock IntentMatchingConfig matchingConfig;
    @InjectMocks ShadowRouterHarnessService harness;

    @BeforeEach void init() { when(matchingConfig.getShadowModeSampleRate()).thenReturn(1.0); }

    @Test void agreement_writesShadowResult_noZpd() {
        RouteDecision d = RouteDecision.directExecute(null, 0.91, java.util.List.of(), "查库存", 5L);
        // bestMatchIntentCode is set via the static factory path; stub the getter result:
        d.setBestMatchIntentCode("MATERIAL_BATCH_QUERY");
        when(semanticRouterService.route(eq("F001"), eq("查库存"), eq(1))).thenReturn(d);
        harness.shadowRoute("rec-1", "查库存", "MATERIAL_BATCH_QUERY", 0.95, "F001");
        verify(recordRepository).updateShadowResult(eq("rec-1"), eq("MATERIAL_BATCH_QUERY"),
                any(BigDecimal.class), eq(true), any(), any(), any());
        verify(recordRepository, never()).markZpdBoundary(anyString());
    }

    @Test void disagreement_writesShadowResult_andZpd() {
        RouteDecision d = RouteDecision.directExecute(null, 0.80, java.util.List.of(), "查库存", 5L);
        d.setBestMatchIntentCode("REPORT_INVENTORY");
        when(semanticRouterService.route(eq("F001"), eq("查库存"), eq(1))).thenReturn(d);
        harness.shadowRoute("rec-2", "查库存", "MATERIAL_BATCH_QUERY", 0.95, "F001");
        verify(recordRepository).updateShadowResult(eq("rec-2"), eq("REPORT_INVENTORY"),
                any(BigDecimal.class), eq(false), any(), any(), any());
        verify(recordRepository).markZpdBoundary("rec-2");
    }

    @Test void nullChallenger_writesDisagree() {
        RouteDecision d = RouteDecision.needFullLLM(0.40, java.util.List.of(), "随便聊聊", 5L); // bestMatchIntentCode null
        when(semanticRouterService.route(eq("F001"), eq("随便聊聊"), eq(1))).thenReturn(d);
        harness.shadowRoute("rec-3", "随便聊聊", "OUT_OF_DOMAIN", 0.9, "F001");
        verify(recordRepository).updateShadowResult(eq("rec-3"), isNull(), any(), eq(false), any(), any(), any());
    }

    @Test void neverThrowsIntoCaller_onRouterError() {
        when(semanticRouterService.route(anyString(), anyString(), anyInt())).thenThrow(new RuntimeException("boom"));
        Assertions.assertDoesNotThrow(() -> harness.shadowRoute("rec-4", "x", "Y", 0.5, "F001"));
        verify(recordRepository, never()).updateShadowResult(any(), any(), any(), anyBoolean(), any(), any(), any());
    }
}
```

- [ ] **Step 2: Run → FAIL** (`./mvnw.cmd -o -q test -Dtest=ShadowRouterHarnessServiceTest`, JAVA_HOME='C:\Program Files\Zulu\zulu-21'). Expected: class undefined.

- [ ] **Step 3: Implement**

```java
package com.cretas.aims.service.impl;

import com.cretas.aims.config.IntentMatchingConfig;
import com.cretas.aims.dto.intent.RouteDecision;
import com.cretas.aims.repository.IntentMatchRecordRepository;
import com.cretas.aims.service.SemanticRouterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;

/**
 * W1a shadow router harness: runs SemanticRouterService.route() as a CHALLENGER off the request
 * thread and logs champion-vs-challenger agreement to intent_match_records shadow columns.
 * Mirrors the (dead) ShadowClassifyService BERT pattern. NEVER affects the live result; any error
 * is swallowed. Gated upstream by the cretas.router.shadow.enabled flag.
 */
@Slf4j
@Service
public class ShadowRouterHarnessService {

    @Autowired private SemanticRouterService semanticRouterService;
    @Autowired private IntentMatchRecordRepository recordRepository;
    @Autowired private IntentMatchingConfig matchingConfig;

    @Async("aiAnalysisExecutor")
    @Transactional
    public void shadowRoute(String recordId, String userInput, String championIntentCode,
                            double championConfidence, String factoryId) {
        if (recordId == null || userInput == null || userInput.isBlank()) {
            return;
        }
        double sampleRate = matchingConfig.getShadowModeSampleRate();
        if (sampleRate < 1.0 && ThreadLocalRandom.current().nextDouble() > sampleRate) {
            return;
        }
        try {
            long startMs = System.currentTimeMillis();
            RouteDecision decision = semanticRouterService.route(factoryId, userInput, 1);
            long latencyMs = System.currentTimeMillis() - startMs;
            String challengerIntent = decision != null ? decision.getBestMatchIntentCode() : null;
            double challengerScore = decision != null ? decision.getTopScore() : 0.0;
            boolean agreed = challengerIntent != null && challengerIntent.equals(championIntentCode);

            recordRepository.updateShadowResult(recordId, challengerIntent,
                    BigDecimal.valueOf(challengerScore), agreed, null, null, null);
            if (!agreed) {
                recordRepository.markZpdBoundary(recordId);
                log.info("[ShadowRouter] DISAGREE recordId={} champion={} challenger={} (score={}, routeType={}, latency={}ms)",
                        recordId, championIntentCode, challengerIntent,
                        String.format("%.4f", challengerScore),
                        decision != null ? decision.getRouteType() : "N/A", latencyMs);
            } else {
                log.debug("[ShadowRouter] AGREE recordId={} intent={} (latency={}ms)", recordId, challengerIntent, latencyMs);
            }
        } catch (Exception e) {
            log.warn("[ShadowRouter] failed recordId={}: {}", recordId, e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Run → PASS** (4/4). If `RouteDecision` has no `setBestMatchIntentCode` setter (it's `@Data` so it should), adapt the test to build via the static factories with the intent code — read RouteDecision.java to confirm.

- [ ] **Step 5: Commit**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/ShadowRouterHarnessService.java \
        backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/ShadowRouterHarnessServiceTest.java
git commit -m "feat(intent-w1a): ShadowRouterHarnessService (challenger router, async, isolated)"
```

---

## Task 3: Wire the harness into recognition (flag-gated, off by default)

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/intent/impl/IntentRecognitionPipelineServiceImpl.java`

Background (verified): `saveIntentMatchRecord(IntentMatchResult result, String factoryId, Long userId, String sessionId, boolean llmCalled)` (line 4037) builds + saves the record at ~line 4124 (`recordRepository.save(record)`); `record.getId()` is populated after save. There's an existing unused `semanticRouterEnabled` flag (line 264, `@Value("${cretas.ai.semantic-router.enabled:true}")`) — DO NOT reuse it (defaults true; different semantics). Add a NEW dedicated flag defaulting OFF.

- [ ] **Step 1: Add the field + dependency**

Add near the other `@Autowired` fields:
```java
    @Autowired private com.cretas.aims.service.impl.ShadowRouterHarnessService shadowRouterHarnessService;

    @Value("${cretas.router.shadow.enabled:false}")
    private boolean shadowRouterEnabled;
```

- [ ] **Step 2: Fire the async shadow after the save (inside `saveIntentMatchRecord`, right after `recordRepository.save(record)`)**

Read the exact lines around 4124; after the save + the existing debug log, insert:
```java
            // W1a: fire the challenger router in shadow (async, isolated, flag-gated; never affects live result)
            if (shadowRouterEnabled) {
                try {
                    shadowRouterHarnessService.shadowRoute(
                            record.getId(),
                            result.getUserInput(),
                            result.hasMatch() ? result.getBestMatch().getIntentCode() : null,
                            result.getConfidence() != null ? result.getConfidence() : 0.0,
                            factoryId);
                } catch (Exception ignore) {
                    // shadow must never disturb the champion path
                }
            }
```
(Confirm `result.getUserInput()`, `result.hasMatch()`, `result.getBestMatch().getIntentCode()`, `result.getConfidence()` exist — they do per IntentMatchResult. `record.getId()` is the just-saved UUID.)

- [ ] **Step 3: Verify compile + the existing W0 intent tests still pass**

Run: `cd backend/java/cretas-api && ./mvnw.cmd -o -q test -Dtest='ShadowRouterHarnessServiceTest,WriteGuardServiceTest,IntentAbstainTest'` (JAVA_HOME Zulu21). Expected: PASS + clean compile.

- [ ] **Step 4: Commit**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/service/intent/impl/IntentRecognitionPipelineServiceImpl.java
git commit -m "feat(intent-w1a): wire shadow router harness into saveIntentMatchRecord (flag-gated, default off)"
```

---

## Task 4: Build, review, deploy test→prod shadow, accumulate data

- [ ] **Step 1: Full build + W1a + W0 suite**

Run: `cd backend/java/cretas-api && ./mvnw.cmd -o -q clean test -Dtest='ShadowRouterHarnessServiceTest,WriteGuardServiceTest,WriteGuardWiringTest,SemanticRouterTwinMarginTest,IntentAbstainTest,IntentGoldenAssertionTest'`
Expected: ALL PASS.

- [ ] **Step 2: Final code-review subagent** over `git diff origin/main...HEAD` — focus: shadow path is fully isolated (cannot throw into champion), recordId is the persisted id, `@Async` executor `aiAnalysisExecutor` exists, the flag truly defaults OFF, the migration is idempotent.

- [ ] **Step 3: PR + merge to main** (CI green; if e2e-pr-gate is red on the chronic fresh-DB issue and `java-build-test` is green, admin-merge per the established pattern — but FIRST confirm the migration didn't cause a NEW fresh-DB failure).

- [ ] **Step 4: Deploy from main to TEST first** (per dual-env best practice — shadow is new behavior):
```bash
git checkout main && git pull origin main
./scripts/deploy/deploy-backend.sh --env test
# enable shadow on test: set cretas.router.shadow.enabled=true in the test env + restart cretas-backend-test
```
Confirm: test backend healthy, shadow rows start populating (`SELECT count(*) FROM intent_match_records WHERE shadow_intent_code IS NOT NULL AND created_at > now()-interval '10 min'`), and NO error in the test log from the shadow path. Drive a few queries through test to seed it.

- [ ] **Step 5: Deploy prod from main + enable shadow** (only after test is clean):
```bash
./scripts/deploy/deploy-backend.sh --env prod
# enable cretas.router.shadow.enabled=true in prod env + restart; verify running jar + shadow rows accumulate
```

- [ ] **Step 6: Accumulate ≥500 queries / ≥48h**, then query the agreement rate:
```sql
SELECT count(*) total,
       count(*) FILTER (WHERE shadow_agreed) agreed,
       round(100.0*count(*) FILTER (WHERE shadow_agreed)/nullif(count(*),0),1) agree_pct
FROM intent_match_records
WHERE shadow_intent_code IS NOT NULL AND created_at > now() - interval '48 hours';
```
This agreement-rate dataset is the evidence base for deciding whether W1c (the full router rebuild) is justified.

---

## Self-Review

**Spec coverage:** W1a = "stand up the shadow harness + comparison persistence" from the design spec's W1 → Tasks 1-4 ✓. Built on existing bge-base-zh (per W1 decision) ✓. Zero live impact (async + isolated + flag-off) ✓.

**Placeholder scan:** The flyway version `V20260911_01` is a PLACEHOLDER — Task 1 Step 1 requires picking the real next-free version (the recurring collision hazard).

**Type consistency:** `shadowRoute(recordId, userInput, championIntentCode, championConfidence, factoryId)` signature consistent across the service, test, and the wiring call site. `updateShadowResult` 7-arg signature matches the repository.

## Parallel Work Suggestion
### Subagent: ✅ Task 1 (migration) is independent of Tasks 2-3; Task 2→3 sequential (wiring needs the service). Subagents serial (Tasks 2/3 both touch the recognition subsystem).
### 多Chat: ⚠️ Touches IntentRecognitionPipelineServiceImpl — keep to ONE chat (concurrent-edit hazard).
