package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.rules.BusinessRule;
import com.cretas.aims.entity.rules.RuleActionType;
import com.cretas.aims.entity.rules.RuleScope;
import com.cretas.aims.repository.rules.BusinessRuleRepository;
import com.cretas.aims.repository.rules.RuleExecutionLogRepository;
import com.cretas.aims.service.rules.RuleEngine;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CanvasRuleController} PUT semantics — Canvas-Rules Phase 4a.
 *
 * <p>B-BR2 regression coverage (2026-05-19 Canvas Phase 2-5 QA finding): PUT with
 * {@code scope: null} or {@code actionType: null} previously NPE'd at
 * {@code raw.toUpperCase()}. The F1 fix (PR #44 review I1) added explicit null-check
 * before {@code .toUpperCase()}; these tests lock that behavior in.
 *
 * <p>B-A7 P0 SpEL injection coverage (2026-05-19 Canvas Phase 2-5 QA finding,
 * sister of PR #48 on CanvasAlertController): POST/PUT with
 * {@code conditionSpel = "T(java.lang.Runtime).getRuntime().exec(...)"} was
 * persisted raw without validation. The fix injects {@link SandboxedSpelEvaluator}
 * and calls {@code validateSyntax()} on write paths before persist; these tests
 * lock that behavior in.
 *
 * <p>PATCH semantics: explicit null = "no change". explicit empty string = 400 VALIDATION.
 *
 * <p>Tests use a real {@link SandboxedSpelEvaluator} instance (not mock) — the
 * sandbox itself has tests in {@code SandboxedSpelEvaluatorTest}; here we
 * verify Controller invokes it on the right paths with the right error
 * envelope ({@code errorCode="VALIDATION"} / {@code code=400}).
 *
 * @since 2026-05-19
 */
@DisplayName("CanvasRuleController PUT null-field handling + SpEL injection (B-BR2 + B-A7)")
@ExtendWith(MockitoExtension.class)
class CanvasRuleControllerTest {

    @Mock private BusinessRuleRepository ruleRepository;
    @Mock private RuleExecutionLogRepository logRepository;
    @Mock private RuleEngine ruleEngine;

    // Use real SpEL evaluator — verifies actual sandbox behavior, not a stub.
    // Mirrors CanvasAlertControllerTest pattern from PR #48.
    private final SandboxedSpelEvaluator spelEvaluator = new SandboxedSpelEvaluator();

    private CanvasRuleController controller;

    @BeforeEach
    void setUp() {
        controller = new CanvasRuleController(ruleRepository, logRepository, ruleEngine, spelEvaluator);
    }

    private static final String FACTORY_ID = "F006";

    private BusinessRule existingRule() {
        return BusinessRule.builder()
                .id(UUID.randomUUID())
                .factoryId(FACTORY_ID)
                .ruleCode("test_rule")
                .ruleName("Test rule")
                .scope(RuleScope.ORDER)
                .conditionSpel("#input.amount > 100")
                .actionType(RuleActionType.LOG)
                .actionConfigJson(new HashMap<>(Map.of("level", "INFO", "message", "test")))
                .priority(100)
                .enabled(true)
                .build();
    }

    private void stubFind(BusinessRule rule) {
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));
        when(ruleRepository.save(any(BusinessRule.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ==================== B-BR2: PUT {scope: null} (F1 regression) ====================

    @Test
    @DisplayName("B-BR2: PUT {scope: null} treated as 'no change' — no NPE, scope unchanged")
    void putWithScopeNullHandledGracefully() {
        BusinessRule rule = existingRule();
        stubFind(rule);
        Map<String, Object> body = new HashMap<>();
        body.put("scope", null);

        ApiResponse<Map<String, Object>> resp = controller.updateRule(FACTORY_ID, rule.getId(), body);

        assertNotNull(resp);
        assertTrue(resp.getSuccess(), "scope=null must NOT 500 NPE");
        assertEquals(Integer.valueOf(200), resp.getCode());
        assertEquals(RuleScope.ORDER, rule.getScope(),
                "scope=null (PATCH semantics) → existing scope preserved");
    }

    @Test
    @DisplayName("B-BR2: PUT {scope: ''} returns 400 VALIDATION (not silent 500)")
    void putWithScopeEmptyStringReturns400() {
        BusinessRule rule = existingRule();
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));
        Map<String, Object> body = new HashMap<>();
        body.put("scope", "");

        ApiResponse<Map<String, Object>> resp = controller.updateRule(FACTORY_ID, rule.getId(), body);

        assertNotNull(resp);
        assertFalse(resp.getSuccess());
        assertEquals(Integer.valueOf(400), resp.getCode());
        assertTrue(String.valueOf(resp.getMessage()).contains("scope"),
                "Error message must mention 'scope' field");
        verify(ruleRepository, never()).save(any(BusinessRule.class));
    }

    @Test
    @DisplayName("B-BR2 sister: PUT {actionType: null} treated as 'no change' — no NPE")
    void putWithActionTypeNullHandledGracefully() {
        BusinessRule rule = existingRule();
        stubFind(rule);
        Map<String, Object> body = new HashMap<>();
        body.put("actionType", null);

        ApiResponse<Map<String, Object>> resp = controller.updateRule(FACTORY_ID, rule.getId(), body);

        assertNotNull(resp);
        assertTrue(resp.getSuccess(), "actionType=null must NOT 500 NPE");
        assertEquals(RuleActionType.LOG, rule.getActionType(),
                "actionType=null (PATCH semantics) → existing actionType preserved");
    }

    @Test
    @DisplayName("B-BR2 sister: PUT {actionType: ''} returns 400 VALIDATION (not silent 500)")
    void putWithActionTypeEmptyStringReturns400() {
        BusinessRule rule = existingRule();
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));
        Map<String, Object> body = new HashMap<>();
        body.put("actionType", "");

        ApiResponse<Map<String, Object>> resp = controller.updateRule(FACTORY_ID, rule.getId(), body);

        assertFalse(resp.getSuccess());
        assertEquals(Integer.valueOf(400), resp.getCode());
        assertTrue(String.valueOf(resp.getMessage()).contains("actionType"));
    }

    @Test
    @DisplayName("B-BR2 valid case: PUT {scope: 'INVENTORY'} updates scope")
    void putWithValidScopeUpdates() {
        BusinessRule rule = existingRule();
        stubFind(rule);
        Map<String, Object> body = new HashMap<>();
        body.put("scope", "INVENTORY");

        ApiResponse<Map<String, Object>> resp = controller.updateRule(FACTORY_ID, rule.getId(), body);

        assertTrue(resp.getSuccess());
        assertEquals(RuleScope.INVENTORY, rule.getScope());
    }

    @Test
    @DisplayName("B-BR2: PUT {scope: 'invalid'} returns 400 with friendly hint")
    void putWithInvalidScopeReturns400() {
        BusinessRule rule = existingRule();
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));
        Map<String, Object> body = new HashMap<>();
        body.put("scope", "INVALID_SCOPE_VALUE");

        ApiResponse<Map<String, Object>> resp = controller.updateRule(FACTORY_ID, rule.getId(), body);

        assertFalse(resp.getSuccess());
        assertEquals(Integer.valueOf(400), resp.getCode());
    }

    @Test
    @DisplayName("B-BR2: PUT empty body {} no-op (all PATCH semantics, no NPE)")
    void putWithEmptyBodyIsNoop() {
        BusinessRule rule = existingRule();
        stubFind(rule);
        Map<String, Object> body = new LinkedHashMap<>();

        ApiResponse<Map<String, Object>> resp = controller.updateRule(FACTORY_ID, rule.getId(), body);

        assertTrue(resp.getSuccess());
        // Nothing changed.
        assertEquals(RuleScope.ORDER, rule.getScope());
        assertEquals(RuleActionType.LOG, rule.getActionType());
        assertEquals("test_rule", rule.getRuleCode());
    }

    @Test
    @DisplayName("B-BR2: PUT with multiple null fields — none NPE, all preserved")
    void putWithMultipleNullFieldsAllNoop() {
        BusinessRule rule = existingRule();
        stubFind(rule);
        Map<String, Object> body = new HashMap<>();
        body.put("scope", null);
        body.put("actionType", null);
        body.put("priority", null);
        body.put("enabled", null);  // booleanField returns default (current value)

        ApiResponse<Map<String, Object>> resp = controller.updateRule(FACTORY_ID, rule.getId(), body);

        assertTrue(resp.getSuccess(), "All-null PATCH body must NOT NPE");
        assertEquals(RuleScope.ORDER, rule.getScope());
        assertEquals(RuleActionType.LOG, rule.getActionType());
        assertEquals(Integer.valueOf(100), rule.getPriority());
        assertEquals(Boolean.TRUE, rule.getEnabled());
    }

    // ==================== B-A7 P0: SpEL injection (sister of PR #48) ====================

    private Map<String, Object> validCreateBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("ruleCode", "test_rule_new");
        body.put("ruleName", "Test rule new");
        body.put("scope", "ORDER");
        body.put("actionType", "LOG");
        body.put("priority", 99);
        return body;
    }

    @Test
    @DisplayName("B-A7 P0: POST 拒绝 T(java.lang.Runtime).getRuntime().exec(...) RCE 注入")
    void testSpelInjectionInCreateRejected() {
        Map<String, Object> body = validCreateBody();
        body.put("conditionSpel", "T(java.lang.Runtime).getRuntime().exec(\"calc\")");

        ApiResponse<Map<String, Object>> resp = controller.createRule(FACTORY_ID, body);

        assertNotNull(resp);
        assertEquals(Integer.valueOf(400), resp.getCode());
        assertFalse(Boolean.TRUE.equals(resp.getSuccess()));
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("SpEL"),
                "message 必须含 'SpEL' 关键字让前端能定位错误");
        // CRITICAL: rule must NOT be persisted
        verify(ruleRepository, never()).save(any(BusinessRule.class));
    }

    @Test
    @DisplayName("B-A7 P0: PUT 拒绝 T(java.lang.Runtime).getRuntime().exec(...) RCE 注入 (update path 也必须 gate)")
    void testSpelInjectionInUpdateRejected() {
        BusinessRule rule = existingRule();
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));

        Map<String, Object> body = new HashMap<>();
        body.put("conditionSpel", "T(java.lang.Runtime).getRuntime().exec(\"rm\")");

        ApiResponse<Map<String, Object>> resp = controller.updateRule(FACTORY_ID, rule.getId(), body);

        assertNotNull(resp);
        assertEquals(Integer.valueOf(400), resp.getCode());
        assertFalse(Boolean.TRUE.equals(resp.getSuccess()));
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("SpEL"));
        verify(ruleRepository, never()).save(any(BusinessRule.class));
    }

    @Test
    @DisplayName("B-A7 P0: 合法 SpEL 表达式通过 — #order.amount > 100 (regression)")
    void testValidSpelStillAccepted() {
        // Use property-access syntax (#var.field) not map-indexing (#var['field']) —
        // sandbox dry-run with empty variables swallows "Property or field ... cannot be found"
        // / "on null" errors (these are expected when running with no context) but does NOT
        // swallow "Cannot index into a null value" (raised by ['...'] on null). This mirrors
        // the same constraint observed in CanvasAlertControllerTest (PR #48) which used
        // "#context.amount > 10000" for the same reason.
        Map<String, Object> body = validCreateBody();
        body.put("conditionSpel", "#order.amount > 100");

        when(ruleRepository.findByFactoryIdAndRuleCode(FACTORY_ID, "test_rule_new"))
                .thenReturn(Optional.empty());
        when(ruleRepository.save(any(BusinessRule.class))).thenAnswer(inv -> {
            BusinessRule r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(UUID.randomUUID());
            }
            return r;
        });

        ApiResponse<Map<String, Object>> resp = controller.createRule(FACTORY_ID, body);

        assertNotNull(resp);
        assertEquals(Integer.valueOf(200), resp.getCode());
        assertTrue(Boolean.TRUE.equals(resp.getSuccess()));
        verify(ruleRepository).save(any(BusinessRule.class));
    }
}
