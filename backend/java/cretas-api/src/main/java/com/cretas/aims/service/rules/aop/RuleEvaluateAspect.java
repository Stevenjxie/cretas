package com.cretas.aims.service.rules.aop;

import com.cretas.aims.entity.rules.RuleScope;
import com.cretas.aims.service.rules.RuleEngine;
import com.cretas.aims.service.rules.RuleEvaluationResult;
import com.cretas.aims.service.rules.RuleViolationException;
import com.cretas.aims.service.rules.annotation.RuleEvaluate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * AOP aspect that intercepts {@link RuleEvaluate}-annotated Service methods and runs
 * {@link RuleEngine#evaluate(String, RuleScope, Object)} before the method body.
 *
 * Spec: docs/superpowers/specs/2026-05-18-canvas-rules-phase4a-spec.md §6
 *
 * <h3>Resolution rules</h3>
 * <ol>
 *   <li>{@code factoryId} — first parameter named "factoryId" (String), or first String arg.</li>
 *   <li>{@code inputObject} — first non-primitive, non-String, non-null arg (the DTO).</li>
 *   <li>{@code scope} — parsed from {@code @RuleEvaluate("ORDER")} value (case-insensitive).</li>
 * </ol>
 *
 * <h3>Behavior</h3>
 * <ul>
 *   <li>{@code shouldReject == true} → throws {@link RuleViolationException} (mapped to HTTP 400
 *       by {@code GlobalExceptionHandler}).</li>
 *   <li>{@code shouldReject == false} → MODIFY actions have already mutated inputObject in-place
 *       via {@link com.cretas.aims.service.rules.impl.RuleEngineImpl} (Spring BeanWrapper).
 *       Aspect proceeds with original args.</li>
 *   <li>If factoryId / scope can't be resolved, a warning is logged and the method is invoked
 *       unchanged (fail-open) — better than blocking business flow on misconfiguration.</li>
 * </ul>
 *
 * <p>{@code @Order(10)} so this aspect runs BEFORE most transaction / module-enabled aspects
 * (they default to 1) — actually, smaller value runs first. We pick 10 so module-enabled
 * (Order 1) gates first, then rule evaluation.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-18
 */
@Slf4j
@Aspect
@Component
@Order(10)
@RequiredArgsConstructor
public class RuleEvaluateAspect {

    private final RuleEngine ruleEngine;

    @Around("@annotation(ruleEvaluate)")
    public Object evaluateRules(ProceedingJoinPoint joinPoint, RuleEvaluate ruleEvaluate) throws Throwable {
        RuleScope scope;
        try {
            scope = RuleScope.valueOf(ruleEvaluate.value().toUpperCase());
        } catch (IllegalArgumentException ex) {
            log.warn("@RuleEvaluate({}) — unknown scope, skipping rule evaluation for method={}",
                    ruleEvaluate.value(), joinPoint.getSignature().toShortString());
            return joinPoint.proceed();
        }

        String factoryId = extractFactoryId(joinPoint);
        if (factoryId == null) {
            log.warn("@RuleEvaluate({}) — factoryId not found in args of method={} (args={}). "
                  + "Proceeding without rule evaluation.",
                    scope, joinPoint.getSignature().toShortString(),
                    Arrays.toString(joinPoint.getArgs()));
            return joinPoint.proceed();
        }

        Object inputObject = extractInputObject(joinPoint);
        if (inputObject == null) {
            log.warn("@RuleEvaluate({}) — no inputObject candidate in args of method={}. "
                  + "Proceeding without rule evaluation.",
                    scope, joinPoint.getSignature().toShortString());
            return joinPoint.proceed();
        }

        RuleEvaluationResult result;
        try {
            result = ruleEngine.evaluate(factoryId, scope, inputObject);
        } catch (Exception e) {
            // Engine failure (e.g. DB outage) should not block business flow — fail open and log loud.
            log.error("RuleEngine.evaluate failed for factory={}, scope={}, method={}: {} — proceeding fail-open",
                    factoryId, scope, joinPoint.getSignature().toShortString(), e.getMessage(), e);
            return joinPoint.proceed();
        }

        if (result.shouldReject()) {
            log.info("Rule {} rejected method={} factory={} reason={}",
                    result.rejectRuleCode(), joinPoint.getSignature().toShortString(),
                    factoryId, result.rejectReason());
            throw new RuleViolationException(result.rejectRuleCode(), result.rejectReason());
        }
        if (!result.modifications().isEmpty()) {
            log.debug("RuleEngine applied {} modifications to method={} factory={}",
                    result.modifications().size(), joinPoint.getSignature().toShortString(), factoryId);
        }

        return joinPoint.proceed();
    }

    /**
     * Find a parameter named "factoryId" (String), or fall back to the first String arg.
     */
    private String extractFactoryId(ProceedingJoinPoint joinPoint) {
        MethodSignature sig = (MethodSignature) joinPoint.getSignature();
        String[] paramNames = sig.getParameterNames();
        Object[] args = joinPoint.getArgs();

        if (paramNames != null) {
            for (int i = 0; i < paramNames.length; i++) {
                if ("factoryId".equals(paramNames[i]) && args[i] instanceof String s && !s.isBlank()) {
                    return s;
                }
            }
        }
        // Fallback: first non-blank String arg.
        for (Object arg : args) {
            if (arg instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    /**
     * Pick the first non-primitive, non-String, non-null arg as inputObject. Reflectively
     * mutated by MODIFY rules via Spring BeanWrapper.
     */
    private Object extractInputObject(ProceedingJoinPoint joinPoint) {
        for (Object arg : joinPoint.getArgs()) {
            if (arg == null) continue;
            if (arg instanceof String) continue;
            if (arg instanceof Number) continue;
            if (arg instanceof Boolean) continue;
            if (arg instanceof Character) continue;
            if (arg instanceof Enum<?>) continue;
            return arg;
        }
        return null;
    }
}
