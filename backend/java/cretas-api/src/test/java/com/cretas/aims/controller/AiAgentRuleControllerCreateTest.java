package com.cretas.aims.controller;

import com.cretas.aims.dto.ai.CreateAiAgentRuleRequest;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.smartbi.AiAgentRule;
import com.cretas.aims.repository.smartbi.AiAgentRuleRepository;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Rule 17.1 cleanup verification for
 * {@link AiAgentRuleController#createRule} (Issue #384 batch 6 final).
 *
 * <p>Asserts that the Controller correctly:
 * <ol>
 *   <li>Binds {@link CreateAiAgentRuleRequest} instead of the
 *       {@link AiAgentRule} entity directly (Rule 17.1 anti-pattern fix)</li>
 *   <li>Forces {@code id = null} and {@code factoryId = @PathVariable} regardless
 *       of wire payload (defense vs cross-tenant write)</li>
 *   <li>Applies controller-owned defaults ({@code priority = 100},
 *       {@code isActive = true}, {@code useLlmSelection = false}) AFTER mapping
 *       — NOT via {@code @Builder.Default} leakage through Jackson
 *       {@code @NoArgsConstructor}</li>
 *   <li>Preserves explicit caller intent when defaults are supplied</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class AiAgentRuleControllerCreateTest {

    @Mock AiAgentRuleRepository aiAgentRuleRepository;

    /**
     * SpEL sandbox evaluator is constructor-injected into the controller
     * (PR #63 B-A7 P0 fix). Without this @Mock the controller's
     * {@code validateSpel(...)} path throws NPE when the test sets a
     * non-null {@code conditionExpression}. Mock default behaviour
     * (no-op on the {@code void validateSyntax}) is exactly what these
     * tests need — they cover Rule 17.1 mapper/defaults contract,
     * not SpEL validation semantics (that's PR #63 / #64 territory).
     */
    @Mock SandboxedSpelEvaluator spelEvaluator;

    @InjectMocks AiAgentRuleController controller;

    @Test
    void createRule_minimumBody_appliesControllerDefaults_andFactoryIdFromContext() {
        // Minimum wire body: required fields only.
        // NO priority (controller 100 fallback). NO isActive (controller true fallback).
        // NO useLlmSelection (controller false fallback). NO factoryId (controller injects).
        CreateAiAgentRuleRequest req = new CreateAiAgentRuleRequest();
        req.setTriggerType("SOP_UPLOAD");
        req.setRuleName("SOP上传自动解析");
        req.setToolChainConfig("{\"executionOrder\":\"SEQUENTIAL\",\"tools\":[]}");

        // Mock returns a distinct instance so captured arg stays un-mutated by the mock —
        // important when asserting the pre-persist state of fields like `id`.
        AiAgentRule persistedReturn = new AiAgentRule();
        persistedReturn.setId("uuid-001");  // simulates Hibernate UUID2 generation
        when(aiAgentRuleRepository.save(any(AiAgentRule.class)))
                .thenReturn(persistedReturn);

        ResponseEntity<ApiResponse<AiAgentRule>> resp = controller.createRule("F001", req);

        ArgumentCaptor<AiAgentRule> captor = ArgumentCaptor.forClass(AiAgentRule.class);
        Mockito.verify(aiAgentRuleRepository).save(captor.capture());
        AiAgentRule saved = captor.getValue();

        assertNotNull(resp);
        // Wire-side fields propagated by mapper
        assertEquals("SOP_UPLOAD", saved.getTriggerType());
        assertEquals("SOP上传自动解析", saved.getRuleName());
        assertEquals("{\"executionOrder\":\"SEQUENTIAL\",\"tools\":[]}", saved.getToolChainConfig());
        // Controller sets factoryId AFTER mapper, forces id=null
        assertEquals("F001", saved.getFactoryId());
        assertNull(saved.getId(),
                "controller MUST set id=null on create — Hibernate generates UUID at persist time");
        // Controller-owned defaults applied AFTER mapping (NOT via @Builder.Default)
        assertEquals(100, saved.getPriority());
        assertTrue(saved.getIsActive());
        assertFalse(saved.getUseLlmSelection());
        // Optional wire fields default to null
        assertNull(saved.getTriggerEntity());
        assertNull(saved.getRuleDescription());
        assertNull(saved.getLlmSelectionPrompt());
        assertNull(saved.getConditionExpression());
    }

    @Test
    void createRule_explicitFields_preservedNotSilentlyOverwritten() {
        // Caller intent priority=10, isActive=false, useLlmSelection=true must be
        // preserved (NOT clobbered by controller defaults).
        CreateAiAgentRuleRequest req = new CreateAiAgentRuleRequest();
        req.setTriggerType("QUALITY_ALERT");
        req.setTriggerEntity("BATCH");
        req.setRuleName("质量告警自动处理");
        req.setRuleDescription("当出现质量告警时自动通知质检主管");
        req.setToolChainConfig("{\"executionOrder\":\"PARALLEL\",\"tools\":[]}");
        req.setUseLlmSelection(true);
        req.setLlmSelectionPrompt("根据告警严重程度选择工具");
        req.setConditionExpression("${severity} == 'HIGH'");
        req.setPriority(10);
        req.setIsActive(false);

        when(aiAgentRuleRepository.save(any(AiAgentRule.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<ApiResponse<AiAgentRule>> resp = controller.createRule("F002", req);

        ArgumentCaptor<AiAgentRule> captor = ArgumentCaptor.forClass(AiAgentRule.class);
        Mockito.verify(aiAgentRuleRepository).save(captor.capture());
        AiAgentRule saved = captor.getValue();

        assertNotNull(resp);
        assertEquals("F002", saved.getFactoryId());
        assertEquals("QUALITY_ALERT", saved.getTriggerType());
        assertEquals("BATCH", saved.getTriggerEntity());
        assertEquals("质量告警自动处理", saved.getRuleName());
        assertEquals("当出现质量告警时自动通知质检主管", saved.getRuleDescription());
        assertEquals("{\"executionOrder\":\"PARALLEL\",\"tools\":[]}", saved.getToolChainConfig());
        // Explicit caller intent preserved — controller defaults NOT applied
        assertEquals(10, saved.getPriority());
        assertFalse(saved.getIsActive());
        assertTrue(saved.getUseLlmSelection());
        assertEquals("根据告警严重程度选择工具", saved.getLlmSelectionPrompt());
        assertEquals("${severity} == 'HIGH'", saved.getConditionExpression());
    }

    @Test
    void createRule_responseEnvelope_successTrueDataPresent() {
        CreateAiAgentRuleRequest req = new CreateAiAgentRuleRequest();
        req.setTriggerType("SOP_UPLOAD");
        req.setRuleName("Test");
        req.setToolChainConfig("{}");

        AiAgentRule persisted = new AiAgentRule();
        persisted.setId("uuid-001");
        persisted.setFactoryId("F001");
        persisted.setRuleName("Test");
        when(aiAgentRuleRepository.save(any(AiAgentRule.class))).thenReturn(persisted);

        ResponseEntity<ApiResponse<AiAgentRule>> resp = controller.createRule("F001", req);

        assertNotNull(resp);
        ApiResponse<AiAgentRule> body = resp.getBody();
        assertNotNull(body);
        assertTrue(Boolean.TRUE.equals(body.getSuccess()));
        assertEquals("规则创建成功", body.getMessage());
        assertEquals(persisted, body.getData());
    }
}
