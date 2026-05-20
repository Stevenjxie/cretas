package com.cretas.aims.controller;

import com.cretas.aims.dto.ai.CreateAiAgentRuleRequest;
import com.cretas.aims.dto.ai.UpdateAiAgentRuleRequest;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.smartbi.AiAgentRule;
import com.cretas.aims.repository.smartbi.AiAgentRuleRepository;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hardening tests for {@link AiAgentRuleController} — fixes P0 SpEL injection
 * (B-A7 sister, 2026-05-19 Canvas Phase 2-5 QA finding).
 *
 * <p><b>Vulnerability before fix</b>: POST/PUT {@code /api/mobile/{factoryId}/ai/agent/rules}
 * accepted {@code conditionExpression: "T(java.lang.Runtime).getRuntime().exec(...)"}
 * unvalidated. {@link com.cretas.aims.service.impl.SopAgentOrchestratorImpl#evaluateCondition}
 * (lines 197-219 pre-fix) used raw {@link org.springframework.expression.spel.support.StandardEvaluationContext}
 * — full reflection + Type + static methods + constructors — so any
 * {@code factory_super_admin}/{@code permission_admin} could plant a rule then trigger RCE
 * by uploading any SOP file.
 *
 * <p><b>Fix</b>: inject {@link SandboxedSpelEvaluator}, call {@code validateSyntax(...)}
 * on POST/PUT before persist. Wrapping {@code #{...}} stripped to mirror existing
 * runtime behavior.
 *
 * <p>Tests use a real {@link SandboxedSpelEvaluator} instance (not mock) — the
 * sandbox itself has tests in {@code SandboxedSpelEvaluatorTest}; here we verify
 * Controller invokes it on the right paths with the right error envelope
 * ({@code errorCode="VALIDATION"} / {@code code=400}).
 *
 * @since 2026-05-19 (Canvas Phase 2-5 QA hotfix sister)
 */
@DisplayName("AiAgentRuleController SpEL 注入加固 (B-A7 P0 sister)")
@ExtendWith(MockitoExtension.class)
class AiAgentRuleControllerTest {

    @Mock
    private AiAgentRuleRepository aiAgentRuleRepository;

    /** Use real SpEL evaluator — verifies actual sandbox behavior, not a stub.
     *  Mirrors CanvasAlertControllerTest pattern from PR #48. */
    private final SandboxedSpelEvaluator spelEvaluator = new SandboxedSpelEvaluator();

    private AiAgentRuleController controller;

    private static final String FACTORY_ID = "F006";

    @BeforeEach
    void setUp() {
        controller = new AiAgentRuleController(aiAgentRuleRepository, spelEvaluator);
    }

    private CreateAiAgentRuleRequest validCreateRequest() {
        CreateAiAgentRuleRequest r = new CreateAiAgentRuleRequest();
        r.setTriggerType(AiAgentRule.TRIGGER_SOP_UPLOAD);
        r.setTriggerEntity("SOP");
        r.setRuleName("测试规则");
        r.setRuleDescription("测试描述");
        r.setToolChainConfig("{\"tools\":[]}");
        r.setUseLlmSelection(false);
        r.setPriority(100);
        r.setIsActive(true);
        return r;
    }

    private UpdateAiAgentRuleRequest validUpdateRequest() {
        UpdateAiAgentRuleRequest r = new UpdateAiAgentRuleRequest();
        r.setRuleName("更新后规则");
        r.setRuleDescription("更新描述");
        r.setTriggerType(AiAgentRule.TRIGGER_SOP_UPLOAD);
        r.setTriggerEntity("SOP");
        r.setToolChainConfig("{\"tools\":[]}");
        r.setUseLlmSelection(false);
        r.setPriority(50);
        return r;
    }

    private AiAgentRule existingRule() {
        AiAgentRule rule = new AiAgentRule();
        rule.setId("rule-001");
        rule.setFactoryId(FACTORY_ID);
        rule.setRuleName("原规则");
        rule.setTriggerType(AiAgentRule.TRIGGER_SOP_UPLOAD);
        rule.setToolChainConfig("{\"tools\":[]}");
        rule.setIsActive(true);
        rule.setUseLlmSelection(false);
        rule.setPriority(100);
        return rule;
    }

    // ==================== B-A7 P0: SpEL injection (POST) ====================

    @Test
    @DisplayName("B-A7 P0: POST 拒绝 T(java.lang.Runtime).getRuntime().exec(...) RCE 注入")
    void testSpelInjectionInCreateRejected() {
        CreateAiAgentRuleRequest req = validCreateRequest();
        req.setConditionExpression("T(java.lang.Runtime).getRuntime().exec(\"calc\")");

        ResponseEntity<ApiResponse<AiAgentRule>> resp = controller.createRule(FACTORY_ID, req);

        assertNotNull(resp);
        assertEquals(400, resp.getStatusCode().value());
        ApiResponse<AiAgentRule> body = resp.getBody();
        assertNotNull(body);
        assertFalse(Boolean.TRUE.equals(body.getSuccess()));
        assertEquals("VALIDATION", body.getErrorCode());
        assertTrue(body.getMessage().contains("SpEL"),
                "message 必须含 'SpEL' 关键字让前端能定位错误");
        // CRITICAL: rule must NOT be persisted
        verify(aiAgentRuleRepository, never()).save(any(AiAgentRule.class));
    }

    @Test
    @DisplayName("B-A7 P0: POST 拒绝 new java.lang.ProcessBuilder() 构造器注入")
    void testSpelInjectionInCreateRejectedConstructor() {
        CreateAiAgentRuleRequest req = validCreateRequest();
        req.setConditionExpression("new java.lang.ProcessBuilder('ls').start()");

        ResponseEntity<ApiResponse<AiAgentRule>> resp = controller.createRule(FACTORY_ID, req);

        assertEquals(400, resp.getStatusCode().value());
        ApiResponse<AiAgentRule> body = resp.getBody();
        assertNotNull(body);
        assertEquals("VALIDATION", body.getErrorCode());
        verify(aiAgentRuleRepository, never()).save(any(AiAgentRule.class));
    }

    @Test
    @DisplayName("B-A7 P0: POST 拒绝 #{T(java.lang.Runtime).exec(...)} 包装的 RCE (mirror runtime stripping)")
    void testSpelInjectionInCreateRejectedWrappedHash() {
        CreateAiAgentRuleRequest req = validCreateRequest();
        // SopAgentOrchestratorImpl strips wrapping #{...} before parse. Validator must
        // mirror this so wrapped RCE is rejected at write time, not silently shipped.
        req.setConditionExpression("#{T(java.lang.Runtime).getRuntime().exec(\"id\")}");

        ResponseEntity<ApiResponse<AiAgentRule>> resp = controller.createRule(FACTORY_ID, req);

        assertEquals(400, resp.getStatusCode().value());
        ApiResponse<AiAgentRule> body = resp.getBody();
        assertNotNull(body);
        assertEquals("VALIDATION", body.getErrorCode());
        verify(aiAgentRuleRepository, never()).save(any(AiAgentRule.class));
    }

    // ==================== B-A7 P0: SpEL injection (PUT) ====================

    @Test
    @DisplayName("B-A7 P0: PUT 拒绝 T(java.lang.Runtime).getRuntime().exec(...) (update path 也必须 gate)")
    void testSpelInjectionInUpdateRejected() {
        AiAgentRule existing = existingRule();
        when(aiAgentRuleRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        UpdateAiAgentRuleRequest req = validUpdateRequest();
        req.setConditionExpression("T(java.lang.Runtime).getRuntime().exec(\"rm\")");

        ResponseEntity<ApiResponse<AiAgentRule>> resp =
                controller.updateRule(FACTORY_ID, existing.getId(), req);

        assertNotNull(resp);
        assertEquals(400, resp.getStatusCode().value());
        ApiResponse<AiAgentRule> body = resp.getBody();
        assertNotNull(body);
        assertFalse(Boolean.TRUE.equals(body.getSuccess()));
        assertEquals("VALIDATION", body.getErrorCode());
        assertTrue(body.getMessage().contains("SpEL"));
        // CRITICAL: rule must NOT be persisted
        verify(aiAgentRuleRepository, never()).save(any(AiAgentRule.class));
    }

    @Test
    @DisplayName("B-A7 P0: PUT 拒绝反射逃逸 #own.getClass().forName(...)")
    void testSpelInjectionInUpdateRejectedReflection() {
        AiAgentRule existing = existingRule();
        when(aiAgentRuleRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        UpdateAiAgentRuleRequest req = validUpdateRequest();
        req.setConditionExpression("#own.getClass().forName('java.lang.Runtime')");

        ResponseEntity<ApiResponse<AiAgentRule>> resp =
                controller.updateRule(FACTORY_ID, existing.getId(), req);

        assertEquals(400, resp.getStatusCode().value());
        verify(aiAgentRuleRepository, never()).save(any(AiAgentRule.class));
    }

    // ==================== Valid SpEL still accepted (regression) ====================

    @Test
    @DisplayName("B-A7 regression: 合法 SpEL 表达式 #input.event != null 通过 POST")
    void testValidSpelAcceptedPost() {
        CreateAiAgentRuleRequest req = validCreateRequest();
        // Use property-access syntax (#var.field) — sandbox dry-run with empty variables
        // swallows "Property or field ... cannot be found" / "on null" errors. Mirror pattern
        // from CanvasRuleControllerTest.
        req.setConditionExpression("#input.event != null");

        when(aiAgentRuleRepository.save(any(AiAgentRule.class))).thenAnswer(inv -> {
            AiAgentRule r = inv.getArgument(0);
            if (r.getId() == null) r.setId("new-id");
            return r;
        });

        ResponseEntity<ApiResponse<AiAgentRule>> resp = controller.createRule(FACTORY_ID, req);

        assertNotNull(resp);
        assertEquals(200, resp.getStatusCode().value());
        ApiResponse<AiAgentRule> body = resp.getBody();
        assertNotNull(body);
        assertTrue(Boolean.TRUE.equals(body.getSuccess()));
        verify(aiAgentRuleRepository).save(any(AiAgentRule.class));
    }

    @Test
    @DisplayName("B-A7 regression: null conditionExpression (空条件 = 始终满足) 通过 POST")
    void testNullSpelAcceptedPost() {
        CreateAiAgentRuleRequest req = validCreateRequest();
        req.setConditionExpression(null);

        when(aiAgentRuleRepository.save(any(AiAgentRule.class))).thenAnswer(inv -> {
            AiAgentRule r = inv.getArgument(0);
            if (r.getId() == null) r.setId("new-id");
            return r;
        });

        ResponseEntity<ApiResponse<AiAgentRule>> resp = controller.createRule(FACTORY_ID, req);

        assertEquals(200, resp.getStatusCode().value());
        verify(aiAgentRuleRepository).save(any(AiAgentRule.class));
    }

    @Test
    @DisplayName("B-A7 regression: blank conditionExpression 通过 POST")
    void testBlankSpelAcceptedPost() {
        CreateAiAgentRuleRequest req = validCreateRequest();
        req.setConditionExpression("   ");

        when(aiAgentRuleRepository.save(any(AiAgentRule.class))).thenAnswer(inv -> {
            AiAgentRule r = inv.getArgument(0);
            if (r.getId() == null) r.setId("new-id");
            return r;
        });

        ResponseEntity<ApiResponse<AiAgentRule>> resp = controller.createRule(FACTORY_ID, req);

        assertEquals(200, resp.getStatusCode().value());
        verify(aiAgentRuleRepository).save(any(AiAgentRule.class));
    }

    @Test
    @DisplayName("B-A7 regression: 合法 SpEL 表达式通过 PUT 更新")
    void testValidSpelAcceptedPut() {
        AiAgentRule existing = existingRule();
        when(aiAgentRuleRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(aiAgentRuleRepository.save(any(AiAgentRule.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateAiAgentRuleRequest req = validUpdateRequest();
        req.setConditionExpression("#sopType == 'PRODUCTION'");

        ResponseEntity<ApiResponse<AiAgentRule>> resp =
                controller.updateRule(FACTORY_ID, existing.getId(), req);

        assertNotNull(resp);
        assertEquals(200, resp.getStatusCode().value());
        verify(aiAgentRuleRepository).save(any(AiAgentRule.class));
    }
}
