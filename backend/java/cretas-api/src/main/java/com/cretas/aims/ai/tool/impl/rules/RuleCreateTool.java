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
 * AI Tool: create a Canvas-Rules BusinessRule.
 *
 * Spec: docs/superpowers/specs/2026-05-18-canvas-rules-phase4a-spec.md §5
 *
 * <h3>Per fool-proof Rule 4 (idempotency)</h3>
 * If a rule with the same {@code (factoryId, ruleCode)} already exists, returns 409-style
 * payload {@code {status: "DUPLICATE", existingId: ...}} rather than failing silently.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-18
 */
@Slf4j
@Component
public class RuleCreateTool extends AbstractBusinessTool {

    @Autowired
    private BusinessRuleRepository ruleRepository;

    @Override
    public String getToolName() {
        return "rule_create";
    }

    @Override
    public String getDescription() {
        return "新建一条业务规则。LLM 通过自然语言描述生成 SpEL 条件 + 动作配置, 落库到 business_rules 表。"
             + "适用场景: 用户说'黑名单供应商不能下单'/'VIP 客户超 5 万自动 5% 折扣'/'缺货自动调拨'等。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("ruleCode", Map.of("type", "string",
                "description", "规则代码 (factoryId 内唯一), 英文蛇形, 如 po_blacklist / vip_discount"));
        properties.put("ruleName", Map.of("type", "string", "description", "规则显示名"));
        properties.put("scope", Map.of("type", "string", "enum", List.of("ORDER", "INVENTORY", "CUSTOMER", "CUSTOM"),
                "description", "适用范围"));
        properties.put("conditionSpel", Map.of("type", "string",
                "description", "SpEL 条件表达式, 如 #input.supplier.blacklisted == true"));
        properties.put("actionType", Map.of("type", "string", "enum", List.of("LOG", "REJECT", "MODIFY", "TRIGGER_WORKFLOW"),
                "description", "动作类型"));
        properties.put("actionConfigJson", Map.of("type", "object",
                "description", "动作配置, 形态依 actionType, 见 spec §2.2"));
        properties.put("priority", Map.of("type", "integer",
                "description", "优先级, 越小越先, 默认 100"));

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Arrays.asList("ruleCode", "scope", "actionType", "conditionSpel"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("ruleCode", "scope", "actionType", "conditionSpel");
    }

    @SuppressWarnings("unchecked")
    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String ruleCode = getString(params, "ruleCode");
        String ruleName = getString(params, "ruleName", ruleCode);
        RuleScope scope = RuleScope.valueOf(getString(params, "scope").toUpperCase());
        RuleActionType actionType = RuleActionType.valueOf(getString(params, "actionType").toUpperCase());
        String conditionSpel = getString(params, "conditionSpel");
        Integer priority = getInteger(params, "priority", 100);

        Object cfg = params.get("actionConfigJson");
        Map<String, Object> actionConfig = cfg instanceof Map
                ? new LinkedHashMap<>((Map<String, Object>) cfg)
                : new LinkedHashMap<>();

        // Per fool-proof Rule 4: idempotent — if (factoryId, ruleCode) exists, return DUPLICATE.
        var existing = ruleRepository.findByFactoryIdAndRuleCode(factoryId, ruleCode);
        if (existing.isPresent()) {
            BusinessRule e = existing.get();
            log.info("rule_create duplicate - factory={}, ruleCode={}, existingId={}",
                    factoryId, ruleCode, e.getId());
            Map<String, Object> dup = new LinkedHashMap<>();
            dup.put("status", "DUPLICATE");
            dup.put("existingId", String.valueOf(e.getId()));
            dup.put("ruleCode", ruleCode);
            dup.put("actionHint", "请使用 rule_update 修改, 或先 rule_delete 删除旧规则");
            return buildSimpleResult(
                    "规则 " + ruleCode + " 已存在 — 不重复创建 (per fool-proof Rule 4)", dup);
        }

        BusinessRule rule = BusinessRule.builder()
                .factoryId(factoryId)
                .ruleCode(ruleCode)
                .ruleName(ruleName)
                .scope(scope)
                .conditionSpel(conditionSpel)
                .actionType(actionType)
                .actionConfigJson(actionConfig)
                .priority(priority)
                .enabled(true)
                .build();
        BusinessRule saved = ruleRepository.save(rule);

        log.info("rule_create success - factory={}, ruleCode={}, scope={}, actionType={}, id={}",
                factoryId, ruleCode, scope, actionType, saved.getId());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", String.valueOf(saved.getId()));
        data.put("ruleCode", saved.getRuleCode());
        data.put("ruleName", saved.getRuleName());
        data.put("scope", saved.getScope());
        data.put("actionType", saved.getActionType());
        data.put("priority", saved.getPriority());
        data.put("enabled", saved.getEnabled());
        return buildSimpleResult("规则创建成功 — " + ruleName + " (" + ruleCode + ")", data);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
