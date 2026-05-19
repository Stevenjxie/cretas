package com.cretas.aims.service.rules.impl;

import com.cretas.aims.entity.rules.BusinessRule;
import com.cretas.aims.entity.rules.RuleActionType;
import com.cretas.aims.entity.rules.RuleExecutionLog;
import com.cretas.aims.entity.rules.RuleScope;
import com.cretas.aims.repository.rules.BusinessRuleRepository;
import com.cretas.aims.repository.rules.RuleExecutionLogRepository;
import com.cretas.aims.service.rules.RuleEngine;
import com.cretas.aims.service.rules.RuleEvaluationResult;
import com.cretas.aims.service.rules.RuleModification;
import com.cretas.aims.service.rules.integration.WorkflowEngineFacade;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.PropertyAccessorFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * RuleEngine impl — Canvas-Rules Phase 4a.
 *
 * Spec: docs/superpowers/specs/2026-05-18-canvas-rules-phase4a-spec.md §2
 *
 * <h3>Evaluation pipeline</h3>
 * <ol>
 *  <li>Load active rules for (factoryId, scope) ordered by priority ASC.</li>
 *  <li>For each rule: parse {@code conditionSpel} via {@link SandboxedSpelEvaluator}
 *      (RCE-safe — SimpleEvaluationContext read-only data binding).</li>
 *  <li>If condition matches, apply action:
 *    <ul>
 *      <li>LOG → audit row only</li>
 *      <li>REJECT → short-circuit + return shouldReject=true</li>
 *      <li>MODIFY → mutate inputObject field(s) via Spring {@link BeanWrapper}</li>
 *      <li>TRIGGER_WORKFLOW → {@link WorkflowEngineFacade}.startWorkflow</li>
 *    </ul>
 *  </li>
 *  <li>Every matched rule writes a {@link RuleExecutionLog} audit row.</li>
 * </ol>
 *
 * <h3>SpEL variable binding</h3>
 * The {@code inputObject} is bound to SpEL as {@code #root} (e.g. {@code totalAmount > 100000}
 * references the object's {@code totalAmount} property directly). It is also exposed as
 * {@code #input} for explicit reference (e.g. {@code #input.discount = 0.05}). Factory id
 * is exposed as {@code #factoryId}.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-18
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleEngineImpl implements RuleEngine {

    private final BusinessRuleRepository ruleRepository;
    private final RuleExecutionLogRepository logRepository;
    private final WorkflowEngineFacade workflowEngineFacade;
    private final SandboxedSpelEvaluator spelEvaluator;

    @Override
    public RuleEvaluationResult evaluate(String factoryId, RuleScope scope, Object inputObject) {
        if (factoryId == null || factoryId.isBlank()) {
            log.warn("RuleEngine.evaluate called without factoryId — skipping evaluation");
            return RuleEvaluationResult.ok(List.of());
        }
        if (scope == null) {
            log.warn("RuleEngine.evaluate called without scope — skipping evaluation");
            return RuleEvaluationResult.ok(List.of());
        }

        List<BusinessRule> rules = ruleRepository
                .findByFactoryIdAndScopeAndEnabledTrueOrderByPriorityAsc(factoryId, scope);
        if (rules.isEmpty()) {
            return RuleEvaluationResult.ok(List.of());
        }

        log.debug("Evaluating {} rules for factory={}, scope={}", rules.size(), factoryId, scope);

        List<UUID> executedRules = new ArrayList<>();
        List<RuleModification> modifications = new ArrayList<>();

        for (BusinessRule rule : rules) {
            boolean matches;
            try {
                matches = evaluateCondition(rule, factoryId, inputObject);
            } catch (Exception e) {
                // Condition errors are surfaced to log + audit but do NOT short-circuit. Per
                // fool-proof Rule 5 we'd want a friendly error; here we log it and skip the rule
                // so a single bad rule doesn't block the whole evaluation chain.
                log.warn("Rule {} (code={}) condition error: {} — skipping",
                        rule.getId(), rule.getRuleCode(), e.getMessage());
                writeExecutionLog(rule, factoryId, inputObject, Map.of(
                        "status", "CONDITION_ERROR",
                        "error", String.valueOf(e.getMessage())));
                continue;
            }
            if (!matches) {
                continue;
            }
            executedRules.add(rule.getId());

            switch (rule.getActionType()) {
                case LOG -> applyLog(rule, factoryId, inputObject);
                case REJECT -> {
                    String reason = stringFromConfig(rule, "reason", "规则拒绝: " + rule.getRuleCode());
                    String actionHint = stringFromConfig(rule, "actionHint", null);
                    // Phase 4a post-review C1: propagate severity to FE via exception → ApiResponse.
                    String severity = stringFromConfig(rule, "severity", "warning");
                    Map<String, Object> resultJson = new LinkedHashMap<>();
                    resultJson.put("action", "REJECT");
                    resultJson.put("reason", reason);
                    if (actionHint != null) {
                        resultJson.put("actionHint", actionHint);
                    }
                    resultJson.put("severity", severity);
                    writeExecutionLog(rule, factoryId, inputObject, resultJson);
                    return RuleEvaluationResult.reject(
                            rule.getRuleCode(), reason, actionHint, severity, executedRules);
                }
                case MODIFY -> {
                    RuleModification mod = applyModify(rule, factoryId, inputObject);
                    if (mod != null) {
                        modifications.add(mod);
                    }
                }
                case TRIGGER_WORKFLOW -> applyTriggerWorkflow(rule, factoryId, inputObject);
                default -> log.warn("Unknown action type {} on rule {}",
                        rule.getActionType(), rule.getRuleCode());
            }
        }

        return new RuleEvaluationResult(false, null, null, null, null, modifications, executedRules);
    }

    @Override
    public boolean preview(String factoryId, UUID ruleId, Object inputObject) {
        BusinessRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "BusinessRule not found: id=" + ruleId));
        if (!factoryId.equals(rule.getFactoryId())) {
            throw new IllegalArgumentException(
                    "Rule factoryId mismatch — rule.factoryId=" + rule.getFactoryId()
                  + " requested=" + factoryId);
        }
        try {
            return evaluateCondition(rule, factoryId, inputObject);
        } catch (Exception e) {
            log.warn("preview() condition error for rule={}: {}", rule.getRuleCode(), e.getMessage());
            return false;
        }
    }

    // ==================== Condition evaluation ====================

    /** Empty/blank condition → always true (rule unconditional). */
    private boolean evaluateCondition(BusinessRule rule, String factoryId, Object inputObject) {
        String spel = rule.getConditionSpel();
        if (spel == null || spel.isBlank()) {
            return true;
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put("input", inputObject);
        vars.put("factoryId", factoryId);
        return spelEvaluator.evaluateBoolean(spel, vars);
    }

    // ==================== Action: LOG ====================

    private void applyLog(BusinessRule rule, String factoryId, Object inputObject) {
        String level = stringFromConfig(rule, "level", "INFO");
        String message = stringFromConfig(rule, "message", "Rule " + rule.getRuleCode() + " triggered");
        log.info("[RuleEngine.LOG][{}] {} ruleCode={} factory={}",
                level, message, rule.getRuleCode(), factoryId);
        Map<String, Object> resultJson = new LinkedHashMap<>();
        resultJson.put("action", "LOG");
        resultJson.put("level", level);
        resultJson.put("message", message);
        writeExecutionLog(rule, factoryId, inputObject, resultJson);
    }

    // ==================== Action: MODIFY ====================

    /**
     * Apply a MODIFY action by reflectively setting a field on inputObject.
     *
     * <p>Config: {@code {"field":"discount","value":0.05}} or
     * {@code {"field":"discount","valueSpel":"#input.totalAmount * 0.05"}}.
     *
     * <p>Nested paths supported via Spring {@link BeanWrapper}: {@code "items[0].unitPrice"}.
     */
    private RuleModification applyModify(BusinessRule rule, String factoryId, Object inputObject) {
        Map<String, Object> cfg = rule.getActionConfigJson();
        if (cfg == null || cfg.isEmpty()) {
            log.warn("MODIFY rule {} has empty actionConfigJson — skipping", rule.getRuleCode());
            return null;
        }
        Object fieldObj = cfg.get("field");
        if (fieldObj == null) {
            log.warn("MODIFY rule {} missing 'field' in actionConfigJson", rule.getRuleCode());
            return null;
        }
        String field = fieldObj.toString();

        Object newValue;
        if (cfg.containsKey("valueSpel")) {
            String valueSpel = String.valueOf(cfg.get("valueSpel"));
            Map<String, Object> vars = new HashMap<>();
            vars.put("input", inputObject);
            vars.put("factoryId", factoryId);
            try {
                newValue = spelEvaluator.evaluate(valueSpel, vars);
            } catch (Exception e) {
                log.warn("MODIFY rule {} valueSpel evaluation failed: {} — skipping",
                        rule.getRuleCode(), e.getMessage());
                return null;
            }
        } else {
            newValue = cfg.get("value");
        }

        Object oldValue;
        try {
            BeanWrapper wrapper = PropertyAccessorFactory.forBeanPropertyAccess(inputObject);
            wrapper.setAutoGrowNestedPaths(false);
            oldValue = wrapper.isReadableProperty(field) ? wrapper.getPropertyValue(field) : null;
            wrapper.setPropertyValue(field, newValue);
        } catch (Exception e) {
            log.warn("MODIFY rule {} failed to set field '{}': {} — skipping",
                    rule.getRuleCode(), field, e.getMessage());
            writeExecutionLog(rule, factoryId, inputObject, Map.of(
                    "action", "MODIFY",
                    "status", "FAILED",
                    "field", field,
                    "error", String.valueOf(e.getMessage())));
            return null;
        }

        Map<String, Object> resultJson = new LinkedHashMap<>();
        resultJson.put("action", "MODIFY");
        resultJson.put("field", field);
        resultJson.put("oldValue", oldValue);
        resultJson.put("newValue", newValue);
        writeExecutionLog(rule, factoryId, inputObject, resultJson);

        log.info("[RuleEngine.MODIFY] ruleCode={} factory={} field={} {} -> {}",
                rule.getRuleCode(), factoryId, field, oldValue, newValue);
        return new RuleModification(field, oldValue, newValue, rule.getId());
    }

    // ==================== Action: TRIGGER_WORKFLOW ====================

    private void applyTriggerWorkflow(BusinessRule rule, String factoryId, Object inputObject) {
        Map<String, Object> cfg = rule.getActionConfigJson();
        String workflowCode = stringFromConfig(rule, "workflowCode", null);
        if (workflowCode == null) {
            log.warn("TRIGGER_WORKFLOW rule {} missing 'workflowCode' — skipping",
                    rule.getRuleCode());
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> ctxRaw = cfg != null && cfg.get("ctx") instanceof Map
                ? (Map<String, Object>) cfg.get("ctx")
                : new HashMap<>();
        // SpEL-resolve any "#..." values in ctx against inputObject — gives rules a way to pull
        // dynamic ids from the request (e.g. {"materialId": "#input.materialId"}).
        Map<String, Object> resolvedCtx = new LinkedHashMap<>();
        Map<String, Object> vars = new HashMap<>();
        vars.put("input", inputObject);
        vars.put("factoryId", factoryId);
        for (Map.Entry<String, Object> e : ctxRaw.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String s && s.startsWith("#")) {
                try {
                    resolvedCtx.put(e.getKey(), spelEvaluator.evaluate(s, vars));
                } catch (Exception ex) {
                    log.warn("TRIGGER_WORKFLOW rule {} ctx['{}'] SpEL '{}' failed: {} — using null",
                            rule.getRuleCode(), e.getKey(), s, ex.getMessage());
                    resolvedCtx.put(e.getKey(), null);
                }
            } else {
                resolvedCtx.put(e.getKey(), v);
            }
        }

        // Phase 4a post-review I3: businessEntityId="" violates Phase 1 unique constraint
        // uq_aw_instances_active(factory_id, module_code, business_entity_id) WHERE status='RUNNING'.
        // Strategy: (1) explicit config value, then (2) SpEL auto-fill from #input.id, then (3) skip.
        String businessEntityId = stringFromConfig(rule, "businessEntityId", null);
        if (businessEntityId == null || businessEntityId.isBlank()) {
            try {
                Object id = spelEvaluator.evaluate("#input?.id", vars);
                if (id != null) {
                    businessEntityId = id.toString();
                }
            } catch (Exception ex) {
                log.debug("TRIGGER_WORKFLOW rule {} #input.id auto-fill failed: {}",
                        rule.getRuleCode(), ex.getMessage());
            }
        }
        if (businessEntityId == null || businessEntityId.isBlank()) {
            // Skip — empty businessEntityId would violate Phase 1 unique-active constraint on
            // concurrent rule firings. Audit the skip so admin can fix the rule config.
            log.warn("TRIGGER_WORKFLOW rule {} skipped: businessEntityId not provided in "
                  + "actionConfigJson and #input has no 'id' property", rule.getRuleCode());
            Map<String, Object> skipResult = new LinkedHashMap<>();
            skipResult.put("action", "TRIGGER_WORKFLOW");
            skipResult.put("workflowCode", workflowCode);
            skipResult.put("ctx", resolvedCtx);
            skipResult.put("status", "SKIPPED_NO_BUSINESS_ENTITY_ID");
            skipResult.put("hint", "Set actionConfigJson.businessEntityId or ensure input has 'id' property");
            writeExecutionLog(rule, factoryId, inputObject, skipResult);
            return;
        }

        Map<String, Object> resultJson = new LinkedHashMap<>();
        resultJson.put("action", "TRIGGER_WORKFLOW");
        resultJson.put("workflowCode", workflowCode);
        resultJson.put("businessEntityId", businessEntityId);
        resultJson.put("ctx", resolvedCtx);

        try {
            var instance = workflowEngineFacade.startWorkflow(factoryId, workflowCode,
                    businessEntityId, resolvedCtx, /* initiatorUserId */ null);
            if (instance != null) {
                resultJson.put("instanceId", String.valueOf(instance.getId()));
                resultJson.put("status", "STARTED");
            } else {
                resultJson.put("status", "SKIPPED_NO_ACTIVE_WORKFLOW");
            }
        } catch (Exception e) {
            log.warn("TRIGGER_WORKFLOW rule {} failed to start workflow {}: {}",
                    rule.getRuleCode(), workflowCode, e.getMessage());
            resultJson.put("status", "ERROR");
            resultJson.put("error", String.valueOf(e.getMessage()));
        }
        writeExecutionLog(rule, factoryId, inputObject, resultJson);
    }

    // ==================== Audit log helper ====================

    private void writeExecutionLog(BusinessRule rule, String factoryId, Object inputObject,
                                   Map<String, Object> resultJson) {
        try {
            Map<String, Object> inputJson = sanitizeInputForAudit(inputObject);
            RuleExecutionLog logEntry = RuleExecutionLog.builder()
                    .ruleId(rule.getId())
                    .factoryId(factoryId)
                    .triggerEvent("RuleEngine.evaluate scope=" + rule.getScope())
                    .inputJson(inputJson)
                    .resultJson(new LinkedHashMap<>(resultJson))
                    .executedAt(LocalDateTime.now())
                    .build();
            logRepository.save(logEntry);
        } catch (Exception e) {
            // Log write failure should NEVER break business flow — at worst we lose audit row.
            log.warn("Failed to persist rule_execution_log for rule {}: {}",
                    rule.getRuleCode(), e.getMessage());
        }
    }

    /**
     * Best-effort serialization of inputObject for audit. Falls back to toString on failure.
     * Avoids reflective dumping of large entity graphs.
     */
    private Map<String, Object> sanitizeInputForAudit(Object inputObject) {
        if (inputObject == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            BeanWrapper wrapper = PropertyAccessorFactory.forBeanPropertyAccess(inputObject);
            for (var pd : wrapper.getPropertyDescriptors()) {
                String name = pd.getName();
                if ("class".equals(name) || pd.getReadMethod() == null) continue;
                Object v;
                try {
                    v = wrapper.getPropertyValue(name);
                } catch (Exception ignore) {
                    continue;
                }
                // Skip collections of arbitrary depth — store size hint only.
                if (v instanceof java.util.Collection<?> coll) {
                    out.put(name + "_size", coll.size());
                } else if (v instanceof java.util.Map<?, ?> m) {
                    out.put(name + "_size", m.size());
                } else if (v == null
                        || v instanceof Number
                        || v instanceof CharSequence
                        || v instanceof Boolean
                        || v instanceof Enum<?>
                        || v instanceof java.time.temporal.Temporal
                        || v instanceof java.util.UUID) {
                    out.put(name, v);
                } else {
                    // Other complex types: store class name to avoid huge nested graphs.
                    out.put(name, v.getClass().getSimpleName());
                }
            }
        } catch (Exception e) {
            out.put("toString", String.valueOf(inputObject));
        }
        return out;
    }

    // ==================== Misc helpers ====================

    private String stringFromConfig(BusinessRule rule, String key, String defaultValue) {
        Map<String, Object> cfg = rule.getActionConfigJson();
        if (cfg == null) return defaultValue;
        Object v = cfg.get(key);
        return v == null ? defaultValue : v.toString();
    }

    @SuppressWarnings("unused")  // visible for testing — keep for future @TestComponent override
    Optional<BusinessRule> findRule(String factoryId, String ruleCode) {
        return ruleRepository.findByFactoryIdAndRuleCode(factoryId, ruleCode);
    }
}
