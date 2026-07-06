package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestHeader;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    @DisplayName("restaurant demo report shortcut distinguishes sales summary from trend")
    void restaurant_demo_report_shortcut_distinguishes_sales_and_trend() throws Exception {
        AIIntentConfigController controller = new AIIntentConfigController(
                null, null, null, null, null, null);
        Method shortcut = AIIntentConfigController.class.getDeclaredMethod(
                "applyRestaurantReportIntentShortcut",
                String.class,
                IntentExecuteRequest.class);
        shortcut.setAccessible(true);

        IntentExecuteRequest trend = IntentExecuteRequest.builder()
                .userInput("分析销售额的月度变化趋势")
                .build();
        shortcut.invoke(controller, "DEMO_REST", trend);
        assertEquals("RESTAURANT_OPS_TREND_ANALYSIS", trend.getIntentCode());

        IntentExecuteRequest sales = IntentExecuteRequest.builder()
                .userInput("查询本周营收")
                .build();
        shortcut.invoke(controller, "DEMO_REST", sales);
        assertEquals("RESTAURANT_OPS_SALES_SUMMARY", sales.getIntentCode());

        IntentExecuteRequest review = IntentExecuteRequest.builder()
                .userInput("\u5ba2\u6237\u8bc4\u4ef7\u600e\u4e48\u6837")
                .build();
        shortcut.invoke(controller, "DEMO_REST", review);
        assertEquals("RESTAURANT_REVIEW_SUMMARY", review.getIntentCode());

        IntentExecuteRequest complaint = IntentExecuteRequest.builder()
                .userInput("\u5dee\u8bc4\u5e94\u8be5\u600e\u4e48\u6539\u5584")
                .build();
        shortcut.invoke(controller, "DEMO_REST", complaint);
        assertEquals("RESTAURANT_REVIEW_COMPLAINT", complaint.getIntentCode());

        IntentExecuteRequest lowStarReplyRate = IntentExecuteRequest.builder()
                .userInput("\u4f4e\u661f\u8bc4\u4ef7\u56de\u590d\u7387\u5dee\uff0c\u4eca\u5929\u8981\u5148\u8865\u54ea\u4e9b\u56de\u590d\uff1f")
                .build();
        shortcut.invoke(controller, "DEMO_REST", lowStarReplyRate);
        assertEquals("RESTAURANT_REVIEW_REPLY_RATE", lowStarReplyRate.getIntentCode());

        IntentExecuteRequest kitchenAction = IntentExecuteRequest.builder()
                .userInput("厨房出餐慢和差评变多，今天先改哪三个动作？")
                .build();
        shortcut.invoke(controller, "DEMO_REST", kitchenAction);
        assertEquals(null, kitchenAction.getIntentCode());

        IntentExecuteRequest packageAction = IntentExecuteRequest.builder()
                .userInput("外卖平台今天适合推什么双人套餐？要考虑成本和差评风险")
                .build();
        shortcut.invoke(controller, "DEMO_REST", packageAction);
        assertEquals(null, packageAction.getIntentCode());

        IntentExecuteRequest salesAction = IntentExecuteRequest.builder()
                .userInput("这个星期营收比上周低，今天老板先做哪三个动作？")
                .build();
        shortcut.invoke(controller, "DEMO_REST", salesAction);
        assertEquals(null, salesAction.getIntentCode());

        IntentExecuteRequest roleAction = IntentExecuteRequest.builder()
                .userInput("本周营业额下降，仓管厨师长前台分别要做什么？")
                .build();
        shortcut.invoke(controller, "DEMO_REST", roleAction);
        assertEquals(null, roleAction.getIntentCode());

        IntentExecuteRequest reviewHighFrequency = IntentExecuteRequest.builder()
                .userInput("大众点评评论里高频好评和高频差评分别是什么？")
                .build();
        shortcut.invoke(controller, "DEMO_REST", reviewHighFrequency);
        assertEquals("RESTAURANT_REVIEW_SUMMARY", reviewHighFrequency.getIntentCode());

        IntentExecuteRequest regionalManager = IntentExecuteRequest.builder()
                .userInput("这家店不是最差但客单价不高，区域经理今天看什么？")
                .build();
        shortcut.invoke(controller, "DEMO_REST", regionalManager);
        assertEquals(null, regionalManager.getIntentCode());
    }

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

    // ====================================================================
    // Sprint 11.5 P0 fix (2026-05-23) — Cookie-auth 500 bug verification.
    // ====================================================================
    //
    // Q7/Q8 Playwright subagent (PR #226 audit) found:
    //   Bearer curl → HTTP 200 + JSON business data
    //   Cookie HttpOnly UI → HTTP 500 "系统处理异常 (追踪码: XXX)"
    //
    // Root cause (confirmed via prod log trace [C62DA8C6]):
    //   org.springframework.web.bind.MissingRequestHeaderException:
    //   Required request header 'Authorization' for method parameter type String is not present
    //
    // All 9 AI intent execution endpoints used @RequestHeader("Authorization")
    // which is mandatory by Spring default. Web admin sends cookie-only requests
    // (no Authorization header) → Spring throws MissingRequestHeaderException
    // BEFORE the controller method runs → falls to GlobalExceptionHandler's
    // generic RuntimeException handler → HTTP 500 with sanitized trace code.
    //
    // Fix: use HttpServletRequest + cookie-aware extractToken() helper that
    // mirrors JwtAuthInterceptor.extractToken (Bearer-first, cookie-fallback).
    //
    // These tests verify the contract structurally via reflection — the methods
    // must NOT have @RequestHeader("Authorization") and MUST take HttpServletRequest.

    /** Methods that MUST use HttpServletRequest (not @RequestHeader Authorization)
     *  to support both Bearer (mobile) and cookie (web admin) auth paths. */
    private static final List<String> COOKIE_AWARE_METHODS = List.of(
            "executeIntent",
            "executeMultiIntent",
            "executeIntentStream",
            "previewIntent",
            "confirmIntent",
            "confirmParameters",
            "rollbackIntent",
            "rollbackAllIntents",
            "submitIntentFeedback"
    );

    @Test
    @DisplayName("Sprint 11.5 P0 fix: executeIntent MUST NOT use @RequestHeader(\"Authorization\")")
    void executeIntent_must_not_use_request_header_authorization() {
        Method m = findMethod("executeIntent");
        assertNotNull(m, "executeIntent method exists");
        for (Parameter p : m.getParameters()) {
            RequestHeader rh = p.getAnnotation(RequestHeader.class);
            if (rh != null && "Authorization".equalsIgnoreCase(rh.value())) {
                fail("executeIntent still has @RequestHeader(\"Authorization\")! "
                        + "Web admin cookie-only requests hit MissingRequestHeaderException → HTTP 500. "
                        + "Use HttpServletRequest + extractToken() helper instead. "
                        + "See Sprint 11.5 P0 root cause: prod log trace [C62DA8C6] "
                        + "MissingRequestHeaderException at GlobalExceptionHandler.handleRuntimeException.");
            }
        }
    }

    @Test
    @DisplayName("Sprint 11.5 P0 sweep: all 9 AI execution methods MUST take HttpServletRequest")
    void all_cookie_aware_methods_must_take_http_servlet_request() {
        List<String> stillUsingHeader = COOKIE_AWARE_METHODS.stream()
                .filter(name -> {
                    Method m = findMethod(name);
                    if (m == null) return false;
                    // Must NOT have @RequestHeader("Authorization") AND must have HttpServletRequest param
                    boolean hasAuthHeader = Arrays.stream(m.getParameters())
                            .map(p -> p.getAnnotation(RequestHeader.class))
                            .anyMatch(rh -> rh != null && "Authorization".equalsIgnoreCase(rh.value()));
                    boolean hasHttpRequest = Arrays.stream(m.getParameterTypes())
                            .anyMatch(HttpServletRequest.class::isAssignableFrom);
                    return hasAuthHeader || !hasHttpRequest;
                })
                .collect(Collectors.toList());

        if (!stillUsingHeader.isEmpty()) {
            fail("Sprint 11.5 P0 REGRESSION: the following methods either still have "
                    + "@RequestHeader(\"Authorization\") OR do not take HttpServletRequest: "
                    + stillUsingHeader
                    + ". Web admin cookie-only requests will fail with HTTP 500 "
                    + "(MissingRequestHeaderException → generic 500 trace). Fix: replace "
                    + "@RequestHeader(\"Authorization\") String authorization with "
                    + "HttpServletRequest httpRequest, then call extractToken(httpRequest).");
        }
    }

    @Test
    @DisplayName("Sprint 11.5 P0: extractToken helper method exists on controller")
    void extract_token_helper_exists() {
        Method m = Arrays.stream(AIIntentConfigController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("extractToken"))
                .findFirst()
                .orElse(null);
        assertNotNull(m, "extractToken(HttpServletRequest) helper must exist for cookie-aware auth");
        assertTrue(Arrays.stream(m.getParameterTypes())
                        .anyMatch(HttpServletRequest.class::isAssignableFrom),
                "extractToken must take HttpServletRequest parameter");
    }
}
