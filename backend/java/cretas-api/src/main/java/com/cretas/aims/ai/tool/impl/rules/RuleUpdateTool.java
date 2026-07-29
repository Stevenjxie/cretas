package com.cretas.aims.ai.tool.impl.rules;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.rules.BusinessRule;
import com.cretas.aims.entity.rules.RuleActionType;
import com.cretas.aims.entity.rules.RuleScope;
import com.cretas.aims.repository.rules.BusinessRuleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Tool: update an existing Canvas-Rules BusinessRule.
 *
 * Spec: docs/superpowers/specs/2026-05-18-canvas-rules-phase4a-spec.md §5
 *
 * <p>factoryId scoping: lookup is by {@code (factoryId, ruleCode)} so cross-tenant
 * access is impossible.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-18
 */
@Slf4j
@Component
public class RuleUpdateTool extends AbstractBusinessTool {

    @Autowired
    private BusinessRuleRepository ruleRepository;

    @Override
    public String getToolName() {
        return "rule_update";
    }

    @Override
    public String getDescription() {
        return "更新已有业务规则的优先级 / 条件 / 动作配置等。例: '把 vip_discount 改成超 10 万 7% 折扣'。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("ruleCode", Map.of("type", "string", "description", "要更新的规则代码"));
        properties.put("ruleName", Map.of("type", "string", "description", "可选, 新规则显示名"));
        properties.put("conditionSpel", Map.of("type", "string", "description", "可选, 新 SpEL 条件"));
        properties.put("actionConfigJson", Map.of("type", "object", "description", "可选, 新动作配置"));
        properties.put("priority", Map.of("type", "integer", "description", "可选, 新优先级"));
        // Phase 4a post-review I4: actionType + scope are immutable in normal flow, but admin
        // workflow occasionally needs to flip e.g. LOG→REJECT after observing log volume, or
        // re-scope ORDER→INVENTORY after taxonomy correction. Validation against enum below.
        properties.put("actionType", Map.of("type", "string",
                "description", "可选, 新动作类型: LOG / REJECT / MODIFY / TRIGGER_WORKFLOW",
                "enum", Arrays.asList("LOG", "REJECT", "MODIFY", "TRIGGER_WORKFLOW")));
        properties.put("scope", Map.of("type", "string",
                "description", "可选, 新作用域: ORDER / INVENTORY / CUSTOMER / CUSTOM",
                "enum", Arrays.asList("ORDER", "INVENTORY", "CUSTOMER", "CUSTOM")));
        properties.put("enabled", Map.of("type", "boolean", "description", "可选, 启用/禁用规则"));

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Arrays.asList("ruleCode"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("ruleCode");
    }

    @SuppressWarnings("unchecked")
    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String ruleCode = getString(params, "ruleCode");
        BusinessRule rule = ruleRepository.findByFactoryIdAndRuleCode(factoryId, ruleCode)
                .orElseThrow(() -> new IllegalArgumentException(
                        "规则 " + ruleCode + " 不存在 (factoryId=" + factoryId + ")"));

        Map<String, Object> changes = new LinkedHashMap<>();
        if (params.containsKey("ruleName") && params.get("ruleName") != null) {
            String newName = getString(params, "ruleName");
            changes.put("ruleName", Map.of("old", rule.getRuleName(), "new", newName));
            rule.setRuleName(newName);
        }
        if (params.containsKey("conditionSpel") && params.get("conditionSpel") != null) {
            String newCond = getString(params, "conditionSpel");
            changes.put("conditionSpel", Map.of("old", rule.getConditionSpel(), "new", newCond));
            rule.setConditionSpel(newCond);
        }
        if (params.containsKey("actionConfigJson") && params.get("actionConfigJson") instanceof Map cfg) {
            Map<String, Object> newCfg = new LinkedHashMap<>((Map<String, Object>) cfg);
            changes.put("actionConfigJson", Map.of("old", rule.getActionConfigJson(), "new", newCfg));
            rule.setActionConfigJson(newCfg);
        }
        if (params.containsKey("priority") && params.get("priority") != null) {
            Integer newPriority = getInteger(params, "priority");
            changes.put("priority", Map.of("old", rule.getPriority(), "new", newPriority));
            rule.setPriority(newPriority);
        }
        // Phase 4a post-review I4: actionType + scope mutation with enum validation.
        if (params.containsKey("actionType") && params.get("actionType") != null) {
            String raw = getString(params, "actionType");
            RuleActionType newType;
            try {
                newType = RuleActionType.valueOf(raw.toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(
                        "actionType 必须为 LOG / REJECT / MODIFY / TRIGGER_WORKFLOW, 不能是 '" + raw + "'");
            }
            changes.put("actionType", Map.of("old", rule.getActionType(), "new", newType));
            rule.setActionType(newType);
        }
        if (params.containsKey("scope") && params.get("scope") != null) {
            String raw = getString(params, "scope");
            RuleScope newScope;
            try {
                newScope = RuleScope.valueOf(raw.toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(
                        "scope 必须为 ORDER / INVENTORY / CUSTOMER / CUSTOM, 不能是 '" + raw + "'");
            }
            changes.put("scope", Map.of("old", rule.getScope(), "new", newScope));
            rule.setScope(newScope);
        }
        if (params.containsKey("enabled") && params.get("enabled") != null) {
            Boolean newEnabled = (Boolean) params.get("enabled");
            changes.put("enabled", Map.of("old", rule.getEnabled(), "new", newEnabled));
            rule.setEnabled(newEnabled);
        }

        if (changes.isEmpty()) {
            return buildSimpleResult("规则 " + ruleCode + " 无字段需要更新", Map.of(
                    "id", String.valueOf(rule.getId()),
                    "ruleCode", ruleCode));
        }

        BusinessRule saved = ruleRepository.save(rule);
        log.info("rule_update success - factory={}, ruleCode={}, id={}, changedFields={}",
                factoryId, ruleCode, saved.getId(), changes.keySet());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", String.valueOf(saved.getId()));
        data.put("ruleCode", saved.getRuleCode());
        data.put("changes", changes);
        return buildSimpleResult("规则 " + ruleCode + " 更新成功", data);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
