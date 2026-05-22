package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Sprint 11 Round 2 (2026-05-22) — P0 fix verification.
 *
 * <p>Round 1 E2E (loop-6-restaurant-ai.spec.ts) found that all 4 spec'd
 * RES_3101_009 accounts (qhj_warehouse_mgr / qhj_finance_mgr / qhj_sales_mgr /
 * qhj_operator) were blocked at {@code POST /api/mobile/{factoryId}/ai-intents/execute}
 * with HTTP 403 because the controller method had
 * {@code @RequirePermission({"system:read_write"})}.
 *
 * <p>Customer-facing roles (warehouse_manager / finance_manager / sales_manager /
 * operator) hold module-scoped perms (e.g. "warehouse:*", "finance:*") but NOT
 * "system:read_write" — which is reserved for super admins. This made the AI
 * spec'd happy path "帮我看上月损溢异常" completely unreachable for the spec'd
 * customer accounts.
 *
 * <p>Per-intent permission gating is already enforced by
 * {@link com.cretas.aims.service.execution.IntentExecutionOrchestrator}
 * line 240 via {@code aiIntentService.hasPermission(intentCode, userRole)} —
 * intents with {@code sensitivity_level=LOW} and empty {@code required_roles}
 * allow all authenticated users; HIGH-sensitivity intents enforce role checks.
 *
 * <p><b>Fix</b>: Remove the controller-level {@code @RequirePermission} on read-only
 * + execution endpoints. Keep it ONLY on admin-only management endpoints (CRUD on
 * intent config, cleanup, cache invalidation, rollback).
 *
 * <p>These tests use reflection to assert the annotation state — they don't run
 * the Spring MVC interceptor stack. Verification is structural: the contract is
 * "method X must not have @RequirePermission with system:read_write".
 *
 * @see com.cretas.aims.config.PermissionInterceptor — the runtime enforcer
 * @see docs/superpowers/handoffs/2026-05-22-mealclaw-e2e-rounds.md — Round 1 P0 evidence
 */
@DisplayName("AIIntentConfigController perm gate — Sprint 11 Round 2 P0 fix")
class AIIntentConfigControllerTest {

    /** Endpoints that MUST NOT have @RequirePermission({"system:read_write"})
     *  — they are AI-execution or read-only diagnostic + feedback paths used by
     *  customer-facing roles like warehouse_manager / finance_manager. */
    private static final List<String> AI_USER_FACING_METHODS = List.of(
            "executeIntent",
            "executeMultiIntent",
            "executeIntentStream",
            "previewIntent",
            "confirmIntent",
            "recognizeIntent",
            "recognizeAllIntents",
            "confirmParameters",
            "recordPositiveFeedback",
            "recordNegativeFeedback",
            "submitIntentFeedback"
    );

    /** Endpoints that MUST KEEP @RequirePermission({"system:read_write"})
     *  — they perform admin-level mutations (CRUD on config, cleanup, cache
     *  management, rollback). Customer roles MUST NOT be able to mutate
     *  intent configurations. */
    private static final List<String> ADMIN_ONLY_METHODS = List.of(
            "createIntent",
            "updateIntent",
            "setIntentActive",
            "deleteIntent",
            "cleanupLowEffectivenessKeywords",
            "deleteExtractionRule",
            "cleanupLowSuccessRules",
            "rollbackIntent",
            "rollbackAllIntents",
            "refreshCache",
            "clearCache"
    );

    @Test
    @DisplayName("P0 fix: executeIntent MUST NOT have @RequirePermission({\"system:read_write\"})")
    void executeIntent_must_not_require_system_read_write() throws NoSuchMethodException {
        Method m = findMethod("executeIntent");
        assertNotNull(m, "executeIntent method exists");
        RequirePermission ann = m.getAnnotation(RequirePermission.class);
        if (ann != null && containsSystemReadWrite(ann.value())) {
            fail("executeIntent still has @RequirePermission({\"system:read_write\"})! "
                    + "This blocks customer-facing roles (warehouse_manager, finance_manager, "
                    + "sales_manager, operator) from using AI. See Round 1 P0 finding in "
                    + "docs/superpowers/handoffs/2026-05-22-mealclaw-e2e-rounds.md");
        }
    }

    @Test
    @DisplayName("P0 sweep: ALL AI user-facing methods MUST NOT have system:read_write gate")
    void all_ai_user_facing_methods_must_not_require_system_read_write() {
        List<String> stillGated = AI_USER_FACING_METHODS.stream()
                .filter(name -> {
                    Method m = findMethod(name);
                    if (m == null) return false;
                    RequirePermission ann = m.getAnnotation(RequirePermission.class);
                    return ann != null && containsSystemReadWrite(ann.value());
                })
                .collect(Collectors.toList());

        if (!stillGated.isEmpty()) {
            fail("The following AI user-facing methods STILL have @RequirePermission({\"system:read_write\"}) "
                    + "which blocks non-admin roles: " + stillGated
                    + ". Sprint 11 Round 2 P0 fix requires these to be open (JWT-auth-only). "
                    + "Per-intent permission is enforced by IntentExecutionOrchestrator + "
                    + "AIIntentService.hasPermission() based on intent.required_roles.");
        }
    }

    @Test
    @DisplayName("Negative regression: admin-only methods STILL require system:read_write")
    void admin_only_methods_still_require_system_read_write() {
        List<String> missingGate = ADMIN_ONLY_METHODS.stream()
                .filter(name -> {
                    Method m = findMethod(name);
                    if (m == null) return true; // missing method = also a problem
                    RequirePermission ann = m.getAnnotation(RequirePermission.class);
                    return ann == null || !containsSystemReadWrite(ann.value());
                })
                .collect(Collectors.toList());

        if (!missingGate.isEmpty()) {
            fail("REGRESSION: The following admin-only methods LOST their "
                    + "@RequirePermission({\"system:read_write\"}) gate: " + missingGate
                    + ". These mutate intent configurations and MUST be gated to super admins.");
        }
    }

    /** Helper: find a controller method by name (any signature). */
    private Method findMethod(String name) {
        return Arrays.stream(AIIntentConfigController.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    /** Helper: check if an annotation's value array contains "system:read_write". */
    private boolean containsSystemReadWrite(String[] perms) {
        return Stream.of(perms).anyMatch("system:read_write"::equals);
    }
}
