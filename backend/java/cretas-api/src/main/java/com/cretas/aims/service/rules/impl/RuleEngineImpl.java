package com.cretas.aims.service.rules.impl;

import com.cretas.aims.entity.rules.RuleScope;
import com.cretas.aims.repository.rules.BusinessRuleRepository;
import com.cretas.aims.repository.rules.RuleExecutionLogRepository;
import com.cretas.aims.service.rules.RuleEngine;
import com.cretas.aims.service.rules.RuleEvaluationResult;
import com.cretas.aims.service.rules.integration.WorkflowEngineFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * RuleEngine skeleton implementation.
 *
 * Spec: docs/superpowers/specs/2026-05-18-canvas-rules-phase4a-spec.md §2 + §9
 *
 * ⚠️ SKELETON: methods throw UnsupportedOperationException. Sister chat to implement:
 *  - SpEL parsing (org.springframework.expression.spel.standard.SpelExpressionParser)
 *  - Rule loading (BusinessRuleRepository.findByFactoryIdAndScopeAndEnabledTrueOrderByPriorityAsc)
 *  - LOG action (RuleExecutionLogRepository.save)
 *  - REJECT action (throw RuleViolationException, short-circuit)
 *  - MODIFY action (reflectively set field on inputObject, e.g. via SpelExpressionParser write)
 *  - TRIGGER_WORKFLOW action (WorkflowEngineFacade.startWorkflow — stubbed for Phase 1 dep)
 *  - Aggregate RuleEvaluationResult
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-18
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleEngineImpl implements RuleEngine {

    @SuppressWarnings("unused")  // wired now so sister chat has constructor injection ready
    private final BusinessRuleRepository ruleRepository;

    @SuppressWarnings("unused")
    private final RuleExecutionLogRepository logRepository;

    @SuppressWarnings("unused")
    private final WorkflowEngineFacade workflowEngineFacade;

    @Override
    public RuleEvaluationResult evaluate(String factoryId, RuleScope scope, Object inputObject) {
        // Sister chat: load rules, parse SpEL, apply actions per spec §2.2
        throw new UnsupportedOperationException(
                "RuleEngine.evaluate not yet implemented (Phase 4a impl). "
                + "Sister chat: implement SpEL evaluator + 4 action types per spec §2.");
    }

    @Override
    public boolean preview(String factoryId, UUID ruleId, Object inputObject) {
        // Sister chat: parse rule.conditionSpel, evaluate, return result. NO side effects.
        throw new UnsupportedOperationException(
                "RuleEngine.preview not yet implemented (Phase 4a impl). "
                + "Sister chat: SpEL dry-run, no log write, no mutation.");
    }
}
