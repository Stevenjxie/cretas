package com.cretas.aims.service.rules.aop;

import com.cretas.aims.service.rules.RuleEngine;
import com.cretas.aims.service.rules.annotation.RuleEvaluate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * AOP aspect that intercepts @RuleEvaluate-annotated Service methods and runs
 * RuleEngine.evaluate before the method body.
 *
 * Spec: docs/superpowers/specs/2026-05-18-canvas-rules-phase4a-spec.md §6
 *
 * ⚠️ SKELETON: @Around body throws UnsupportedOperationException. Sister chat to implement:
 *  1. Resolve factoryId (SecurityContext / request scope)
 *  2. Pick inputObject from joinPoint.getArgs() (first non-primitive, or SpEL #root.args[0])
 *  3. Parse ruleEvaluate.value() → RuleScope enum
 *  4. Call ruleEngine.evaluate(factoryId, scope, inputObject)
 *  5. If result.shouldReject → throw new RuleViolationException(result.rejectRuleCode, result.rejectReason)
 *  6. Otherwise → joinPoint.proceed() with same args (MODIFY already mutated inputObject)
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-18
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RuleEvaluateAspect {

    @SuppressWarnings("unused")  // sister chat will use
    private final RuleEngine ruleEngine;

    @Around("@annotation(ruleEvaluate)")
    public Object evaluateRules(ProceedingJoinPoint joinPoint, RuleEvaluate ruleEvaluate) throws Throwable {
        // Sister chat impl per Javadoc above. Until then, fail-fast so attaching the
        // annotation prematurely triggers a clear error rather than silent NO-OP.
        throw new UnsupportedOperationException(
                "RuleEvaluateAspect.evaluateRules not yet implemented (Phase 4a impl). "
                + "Scope=" + ruleEvaluate.value()
                + ", method=" + joinPoint.getSignature().toShortString());
    }
}
