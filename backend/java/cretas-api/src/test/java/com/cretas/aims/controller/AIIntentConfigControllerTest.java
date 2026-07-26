package com.cretas.aims.controller;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cretas.aims.ai.tool.gateway.ConfirmationProof;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.IntentExecutorService;
import com.cretas.aims.service.KeywordEffectivenessService;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.impl.IntentConfigRollbackService;
import com.cretas.aims.utils.CookieAuthHelper;
import com.cretas.aims.utils.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestHeader;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    private static final String CONFIRMATION_TOKEN = "opaque-sensitive-confirmation-token";
    private static final String COMMAND_DIGEST = "a".repeat(64);

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

        IntentExecuteRequest complaintRemedy = IntentExecuteRequest.builder()
                .userInput("哪些门店差评最多，店长今天怎么处理？")
                .build();
        shortcut.invoke(controller, "DEMO_REST", complaintRemedy);
        assertEquals("RESTAURANT_REVIEW_BAD_STORE", complaintRemedy.getIntentCode());

        IntentExecuteRequest regionalManager = IntentExecuteRequest.builder()
                .userInput("这家店不是最差但客单价不高，区域经理今天看什么？")
                .build();
        shortcut.invoke(controller, "DEMO_REST", regionalManager);
        assertEquals(null, regionalManager.getIntentCode());

        for (String rankedOrComparativeQuestion : List.of(
                "本月哪个店营业额最高",
                "今天哪家门店营收最高",
                "本周门店营收排名",
                "分析哪个月营收最高",
                "本月和上月营收对比",
                "昨天的营业额是高于前天还是低于前天？",
                "上个月营业额和上上个月相比怎么样",
                "本月客单价最高的门店是哪家")) {
            IntentExecuteRequest request = IntentExecuteRequest.builder()
                    .userInput(rankedOrComparativeQuestion)
                    .build();
            shortcut.invoke(controller, "DEMO_REST", request);
            assertEquals(
                    null,
                    request.getIntentCode(),
                    rankedOrComparativeQuestion + " must reach object-aware downstream routing");
        }

        for (String diagnosticQuestion : List.of(
                "结合天气、客流和活动分析2026年3月营收高峰原因",
                "请分析最近30天青花椒徐汇光启城店的营收、"
                        + "菜品销售与活动背景，活动只作同期描述，不计算因果ROI",
                "最近30天青花椒南方百联店采购价格稳定吗？",
                "为什么本月营收下降",
                "天气对本月营业额有什么影响",
                "分析本月营收的拉动因素")) {
            IntentExecuteRequest request = IntentExecuteRequest.builder()
                    .userInput(diagnosticQuestion)
                    .build();
            shortcut.invoke(controller, "DEMO_REST", request);
            assertEquals(
                    null,
                    request.getIntentCode(),
                    diagnosticQuestion + " must reach diagnostic/comprehensive downstream routing");
        }

        IntentExecuteRequest plainAnalysis = IntentExecuteRequest.builder()
                .userInput("分析本月营收")
                .build();
        shortcut.invoke(controller, "DEMO_REST", plainAnalysis);
        assertEquals("RESTAURANT_OPS_SALES_SUMMARY", plainAnalysis.getIntentCode());
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

    @Test
    @DisplayName("execute strips caller-controlled confirmation authority before service dispatch")
    void execute_strips_forged_confirmed_and_force_execute_but_preserves_business_context() throws Exception {
        ControllerFixture fixture = controllerFixture();
        when(fixture.executor().execute(eq("F001"), any(), eq(12L), eq("FACTORY_ADMIN")))
                .thenReturn(IntentExecuteResponse.builder().status("WRITE_CONFIRM_REQUIRED").build());

        fixture.mvc().perform(post("/api/mobile/F001/ai-intents/execute")
                        .header("Authorization", "Bearer jwt-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userInput":"create order","intentCode":"ORDER_CREATE",
                                 "forceExecute":true,
                                 "context":{"confirmed":true,"force_execute":true,"amount":5}}
                                """))
                .andExpect(status().isOk());

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(IntentExecuteRequest.class);
        verify(fixture.executor()).execute(eq("F001"), requestCaptor.capture(), eq(12L), eq("FACTORY_ADMIN"));
        IntentExecuteRequest forwarded = requestCaptor.getValue();
        assertFalse(Boolean.TRUE.equals(forwarded.getForceExecute()));
        assertEquals(Map.of("amount", 5), forwarded.getContext());
    }

    @Test
    @DisplayName("parameter confirmation cannot grant write-execution authority")
    void parameter_confirmation_execute_after_confirm_still_requires_token_authority() throws Exception {
        ControllerFixture fixture = controllerFixture();
        when(fixture.executor().execute(eq("F001"), any(), eq(12L), eq("FACTORY_ADMIN")))
                .thenReturn(IntentExecuteResponse.builder().status("WRITE_CONFIRM_REQUIRED").build());

        fixture.mvc().perform(post("/api/mobile/F001/ai-intents/params/confirm")
                        .header("Authorization", "Bearer jwt-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"intentCode":"ORDER_CREATE","userInput":"create order",
                                 "executeAfterConfirm":true,
                                 "confirmedParams":{"confirmed":true,"forceExecute":true,"amount":5}}
                                """))
                .andExpect(status().isOk());

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(IntentExecuteRequest.class);
        verify(fixture.executor()).execute(eq("F001"), requestCaptor.capture(), eq(12L), eq("FACTORY_ADMIN"));
        IntentExecuteRequest forwarded = requestCaptor.getValue();
        assertFalse(Boolean.TRUE.equals(forwarded.getForceExecute()));
        assertEquals(Map.of("amount", 5), forwarded.getContext());
        verify(fixture.learningService()).learnAndConfirm(
                eq("F001"), eq("ORDER_CREATE"), eq("create order"), eq(Map.of("amount", 5)));
    }

    @Test
    @DisplayName("fixed confirmation endpoint keeps the opaque token in the dedicated header")
    void fixed_confirmation_endpoint_uses_header_and_parameter_bound_body() throws Exception {
        ControllerFixture fixture = controllerFixture();
        Instant expiresAt = Instant.now().plusSeconds(300);
        when(fixture.executor().confirm(
                eq("F001"), any(ConfirmationProof.class), eq(12L), eq("FACTORY_ADMIN")))
                .thenReturn(IntentExecuteResponse.builder().status("SUCCESS").build());

        Logger logger = (Logger) LoggerFactory.getLogger(AIIntentConfigController.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            fixture.mvc().perform(post("/api/mobile/F001/ai-intents/confirm")
                            .header("Authorization", "Bearer jwt-token")
                            .header("X-Cretas-Confirmation-Token", CONFIRMATION_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(new ObjectMapper().findAndRegisterModules().writeValueAsString(Map.of(
                                    "commandDigest", COMMAND_DIGEST,
                                    "expiresAt", expiresAt.toString(),
                                    "requestId", "request-12345678",
                                    "idempotencyKey", "idem-12345678"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        var proofCaptor = org.mockito.ArgumentCaptor.forClass(ConfirmationProof.class);
        verify(fixture.executor()).confirm(
                eq("F001"), proofCaptor.capture(), eq(12L), eq("FACTORY_ADMIN"));
        assertEquals(CONFIRMATION_TOKEN, proofCaptor.getValue().proofToken());
        assertEquals(COMMAND_DIGEST, proofCaptor.getValue().commandDigest());
        assertEquals(expiresAt, proofCaptor.getValue().expiresAt());
        assertTrue(appender.list.stream()
                .noneMatch(event -> event.getFormattedMessage().contains(CONFIRMATION_TOKEN)),
                "controller logs must never contain the confirmation header token");
    }

    @Test
    @DisplayName("fixed confirmation endpoint preserves cookie-only authentication")
    void fixed_confirmation_endpoint_accepts_cookie_only_authentication() throws Exception {
        ControllerFixture fixture = controllerFixture();
        when(fixture.executor().confirm(
                eq("F001"), any(ConfirmationProof.class), eq(12L), eq("FACTORY_ADMIN")))
                .thenReturn(IntentExecuteResponse.builder().status("SUCCESS").build());

        fixture.mvc().perform(post("/api/mobile/F001/ai-intents/confirm")
                        .cookie(new Cookie(CookieAuthHelper.ACCESS_TOKEN_COOKIE, "jwt-token"))
                        .header("X-Cretas-Confirmation-Token", CONFIRMATION_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validConfirmationBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        var proofCaptor = org.mockito.ArgumentCaptor.forClass(ConfirmationProof.class);
        verify(fixture.executor()).confirm(
                eq("F001"), proofCaptor.capture(), eq(12L), eq("FACTORY_ADMIN"));
        assertEquals(CONFIRMATION_TOKEN, proofCaptor.getValue().proofToken());
        assertEquals(COMMAND_DIGEST, proofCaptor.getValue().commandDigest());
    }

    @Test
    @DisplayName("legacy path-token endpoint terminates with 410 and no application execution")
    void legacy_path_confirmation_returns_gone_without_binding_or_execution() throws Exception {
        ControllerFixture fixture = controllerFixture();

        fixture.mvc().perform(post("/api/mobile/F001/ai-intents/confirm/{legacyToken}",
                            CONFIRMATION_TOKEN))
                .andExpect(status().isGone())
                .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.errorCode")
                        .value("CONFIRMATION_ENDPOINT_UPGRADE_REQUIRED"));

        verifyNoInteractions(fixture.executor(), fixture.jwtUtil());
    }

    @Test
    @DisplayName("fixed confirmation endpoint rejects invalid or expired proof without echoing values")
    void fixed_confirmation_endpoint_rejects_invalid_or_expired_proof_without_echo() throws Exception {
        ControllerFixture fixture = controllerFixture();
        String invalidDigest = "SECRET-UPPERCASE-DIGEST";
        String requestId = "request-secret-123";
        String idempotencyKey = "idempotency-secret-123";

        fixture.mvc().perform(post("/api/mobile/F001/ai-intents/confirm")
                        .header("Authorization", "Bearer jwt-token")
                        .header("X-Cretas-Confirmation-Token", CONFIRMATION_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().findAndRegisterModules().writeValueAsString(Map.of(
                                "commandDigest", invalidDigest,
                                "expiresAt", Instant.now().minusSeconds(1).toString(),
                                "requestId", requestId,
                                "idempotencyKey", idempotencyKey))))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(Matchers.not(Matchers.containsString(invalidDigest))))
                .andExpect(content().string(Matchers.not(Matchers.containsString(requestId))))
                .andExpect(content().string(Matchers.not(Matchers.containsString(idempotencyKey))))
                .andExpect(content().string(Matchers.not(Matchers.containsString(CONFIRMATION_TOKEN))));

        verifyNoInteractions(fixture.executor());
    }

    @Test
    @DisplayName("fixed confirmation endpoint rejects a blank confirmation header before auth")
    void fixed_confirmation_endpoint_rejects_blank_header_before_auth() throws Exception {
        ControllerFixture fixture = controllerFixture();

        fixture.mvc().perform(post("/api/mobile/F001/ai-intents/confirm")
                        .header("X-Cretas-Confirmation-Token", " ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validConfirmationBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_CONFIRMATION_PROOF"))
                .andExpect(content().string(Matchers.not(Matchers.containsString(CONFIRMATION_TOKEN))));

        verifyNoInteractions(fixture.executor(), fixture.jwtUtil());
    }

    @Test
    @DisplayName("confirmation JSON rejects confirmToken and every other unknown field")
    void confirmation_body_rejects_unknown_fields_without_echoing_values() throws Exception {
        ControllerFixture fixture = controllerFixture();
        for (Map.Entry<String, String> unknown : Map.of(
                "confirmToken", "body-token-must-never-be-accepted",
                "unexpectedField", "unexpected-secret-value").entrySet()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("commandDigest", COMMAND_DIGEST);
            body.put("expiresAt", Instant.now().plusSeconds(300).toString());
            body.put("requestId", "request-12345678");
            body.put("idempotencyKey", "idem-12345678");
            body.put(unknown.getKey(), unknown.getValue());

            fixture.mvc().perform(post("/api/mobile/F001/ai-intents/confirm")
                            .header("Authorization", "Bearer jwt-token")
                            .header("X-Cretas-Confirmation-Token", CONFIRMATION_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(new ObjectMapper().findAndRegisterModules()
                                    .writeValueAsString(body)))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(
                            Matchers.not(Matchers.containsString(unknown.getValue()))))
                    .andExpect(content().string(
                            Matchers.not(Matchers.containsString(CONFIRMATION_TOKEN))));
        }

        verifyNoInteractions(fixture.executor());
    }

    private ControllerFixture controllerFixture() {
        IntentExecutorService executor = mock(IntentExecutorService.class);
        JwtUtil jwtUtil = mock(JwtUtil.class);
        when(jwtUtil.getUserIdFromToken("jwt-token")).thenReturn(12L);
        when(jwtUtil.getRoleFromToken("jwt-token")).thenReturn("FACTORY_ADMIN");
        ParameterExtractionLearningService learningService = mock(ParameterExtractionLearningService.class);
        AIIntentConfigController controller = new AIIntentConfigController(
                mock(AIIntentService.class),
                executor,
                mock(KeywordEffectivenessService.class),
                mock(IntentConfigRollbackService.class),
                learningService,
                jwtUtil);
        return new ControllerFixture(
                MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build(),
                executor,
                jwtUtil,
                learningService);
    }

    private String validConfirmationBody() throws Exception {
        return new ObjectMapper().findAndRegisterModules().writeValueAsString(Map.of(
                "commandDigest", COMMAND_DIGEST,
                "expiresAt", Instant.now().plusSeconds(300).toString(),
                "requestId", "request-12345678",
                "idempotencyKey", "idem-12345678"));
    }

    private record ControllerFixture(
            MockMvc mvc,
            IntentExecutorService executor,
            JwtUtil jwtUtil,
            ParameterExtractionLearningService learningService) {
    }
}
