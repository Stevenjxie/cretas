package com.cretas.aims.controller;

import com.cretas.aims.dto.ai.UpdateAiAgentRuleRequest;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.smartbi.AiAgentRule;
import com.cretas.aims.exception.BusinessException;
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

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rule 17.1 cleanup verification for
 * {@link AiAgentRuleController#updateRule} (Issue #384 batch 6 final).
 *
 * <p>Asserts that the Controller correctly:
 * <ol>
 *   <li>Binds {@link UpdateAiAgentRuleRequest} instead of the
 *       {@link AiAgentRule} entity directly (Rule 17.1 anti-pattern fix)</li>
 *   <li>Preserves immutable fields ({@code id}, {@code factoryId}) regardless
 *       of wire payload — they belong to the path / existing record</li>
 *   <li>Mutates wire-supplied fields exactly as before the refactor
 *       (controller behaviour: unconditional setter call, mirroring pre-DTO contract)</li>
 *   <li>Cross-factory edits are rejected before any mutation reaches the entity</li>
 *   <li>Global rules (factoryId = "DEFAULT") are rejected</li>
 *   <li>Missing rule returns 404</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class AiAgentRuleControllerUpdateTest {

    @Mock AiAgentRuleRepository aiAgentRuleRepository;

    /**
     * SpEL sandbox evaluator is constructor-injected into the controller
     * (PR #63 B-A7 P0 fix). Without this @Mock the controller's
     * {@code validateSpel(...)} path throws NPE when the test sets a
     * non-null {@code conditionExpression}. Mock default behaviour
     * (no-op on the {@code void validateSyntax}) is exactly what these
     * tests need — they cover Rule 17.1 update-path mutation contract,
     * not SpEL validation semantics (that's PR #63 / #64 territory).
     */
    @Mock SandboxedSpelEvaluator spelEvaluator;

    @InjectMocks AiAgentRuleController controller;

    @Test
    void updateRule_explicitFieldChanges_areApplied() {
        AiAgentRule existing = new AiAgentRule();
        existing.setId("rule-001");
        existing.setFactoryId("F001");
        existing.setRuleName("旧名称");
        existing.setTriggerType("SOP_UPLOAD");
        existing.setPriority(100);
        existing.setIsActive(true);
        existing.setUseLlmSelection(false);
        existing.setToolChainConfig("{\"old\":true}");

        UpdateAiAgentRuleRequest req = new UpdateAiAgentRuleRequest();
        req.setRuleName("新名称");
        req.setRuleDescription("新描述");
        req.setTriggerType("QUALITY_ALERT");
        req.setTriggerEntity("BATCH");
        req.setToolChainConfig("{\"new\":true}");
        req.setUseLlmSelection(true);
        req.setLlmSelectionPrompt("新 prompt");
        req.setConditionExpression("${severity} == 'HIGH'");
        req.setPriority(50);

        when(aiAgentRuleRepository.findById("rule-001")).thenReturn(Optional.of(existing));
        when(aiAgentRuleRepository.save(any(AiAgentRule.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<ApiResponse<AiAgentRule>> resp =
                controller.updateRule("F001", "rule-001", req);

        ArgumentCaptor<AiAgentRule> captor = ArgumentCaptor.forClass(AiAgentRule.class);
        verify(aiAgentRuleRepository).save(captor.capture());
        AiAgentRule saved = captor.getValue();

        assertNotNull(resp);
        // id / factoryId untouched (immutable)
        assertEquals("rule-001", saved.getId());
        assertEquals("F001", saved.getFactoryId());
        // Wire-supplied fields applied
        assertEquals("新名称", saved.getRuleName());
        assertEquals("新描述", saved.getRuleDescription());
        assertEquals("QUALITY_ALERT", saved.getTriggerType());
        assertEquals("BATCH", saved.getTriggerEntity());
        assertEquals("{\"new\":true}", saved.getToolChainConfig());
        assertTrue(saved.getUseLlmSelection());
        assertEquals("新 prompt", saved.getLlmSelectionPrompt());
        assertEquals("${severity} == 'HIGH'", saved.getConditionExpression());
        assertEquals(50, saved.getPriority());
        // isActive untouched (not in UpdateAiAgentRuleRequest scope)
        assertTrue(saved.getIsActive());
    }

    @Test
    void updateRule_missingId_throwsBusinessException404_noSaveAttempted() {
        when(aiAgentRuleRepository.findById("missing-rule")).thenReturn(Optional.empty());

        UpdateAiAgentRuleRequest req = new UpdateAiAgentRuleRequest();
        req.setRuleName("无效更新");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.updateRule("F001", "missing-rule", req));
        assertEquals(404, ex.getCode());

        verify(aiAgentRuleRepository, never()).save(any(AiAgentRule.class));
    }

    @Test
    void updateRule_globalRule_throws409_noSaveAttempted() {
        AiAgentRule globalRule = new AiAgentRule();
        globalRule.setId("global-rule-001");
        globalRule.setFactoryId("DEFAULT");
        globalRule.setRuleName("全局规则");
        when(aiAgentRuleRepository.findById("global-rule-001"))
                .thenReturn(Optional.of(globalRule));

        UpdateAiAgentRuleRequest req = new UpdateAiAgentRuleRequest();
        req.setRuleName("尝试覆盖全局");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.updateRule("F001", "global-rule-001", req));
        assertEquals(409, ex.getCode());

        verify(aiAgentRuleRepository, never()).save(any(AiAgentRule.class));
    }

    @Test
    void updateRule_crossFactoryEdit_throws403_noSaveAttempted() {
        AiAgentRule otherFactoryRule = new AiAgentRule();
        otherFactoryRule.setId("rule-f002");
        otherFactoryRule.setFactoryId("F002");
        when(aiAgentRuleRepository.findById("rule-f002"))
                .thenReturn(Optional.of(otherFactoryRule));

        UpdateAiAgentRuleRequest req = new UpdateAiAgentRuleRequest();
        req.setRuleName("跨工厂尝试");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.updateRule("F001", "rule-f002", req));
        assertEquals(403, ex.getCode());

        verify(aiAgentRuleRepository, never()).save(any(AiAgentRule.class));
    }

    @Test
    void updateRule_nullWireFields_setUnconditionally_preservesPreDtoBehavior() {
        // Pre-DTO behavior: each setter is called unconditionally regardless of
        // wire payload. A null wire field clears the existing DB value. DTO route
        // must mirror this contract exactly.
        AiAgentRule existing = new AiAgentRule();
        existing.setId("rule-003");
        existing.setFactoryId("F001");
        existing.setRuleName("现有名");
        existing.setRuleDescription("现有描述");
        existing.setTriggerType("SOP_UPLOAD");
        existing.setUseLlmSelection(true);
        existing.setLlmSelectionPrompt("现有 prompt");

        UpdateAiAgentRuleRequest req = new UpdateAiAgentRuleRequest();
        // ALL fields intentionally null (caller PUT empty body, which under the
        // pre-DTO contract would have cleared every field — DTO route preserves
        // this exact contract for back-compat).

        when(aiAgentRuleRepository.findById("rule-003")).thenReturn(Optional.of(existing));
        when(aiAgentRuleRepository.save(any(AiAgentRule.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        controller.updateRule("F001", "rule-003", req);

        ArgumentCaptor<AiAgentRule> captor = ArgumentCaptor.forClass(AiAgentRule.class);
        verify(aiAgentRuleRepository).save(captor.capture());
        AiAgentRule saved = captor.getValue();

        // All wire-supplied fields are null → existing values cleared
        assertEquals(null, saved.getRuleName());
        assertEquals(null, saved.getRuleDescription());
        assertEquals(null, saved.getTriggerType());
        assertEquals(null, saved.getUseLlmSelection());
        assertEquals(null, saved.getLlmSelectionPrompt());
        // id + factoryId still preserved (immutable)
        assertEquals("rule-003", saved.getId());
        assertEquals("F001", saved.getFactoryId());
    }

    @Test
    void updateRule_responseEnvelope_successTrueDataPresent() {
        AiAgentRule existing = new AiAgentRule();
        existing.setId("rule-001");
        existing.setFactoryId("F001");
        when(aiAgentRuleRepository.findById("rule-001")).thenReturn(Optional.of(existing));
        when(aiAgentRuleRepository.save(any(AiAgentRule.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        UpdateAiAgentRuleRequest req = new UpdateAiAgentRuleRequest();
        req.setRuleName("Updated");

        ResponseEntity<ApiResponse<AiAgentRule>> resp =
                controller.updateRule("F001", "rule-001", req);

        assertNotNull(resp);
        ApiResponse<AiAgentRule> body = resp.getBody();
        assertNotNull(body);
        assertTrue(Boolean.TRUE.equals(body.getSuccess()));
        assertEquals("规则更新成功", body.getMessage());
        assertFalse(body.getData() == null);
    }
}
