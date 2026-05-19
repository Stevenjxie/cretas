package com.cretas.aims.ai.tool.impl.rules;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Tool: create a Canvas-Rules BusinessRule.
 *
 * Spec: docs/superpowers/specs/2026-05-18-canvas-rules-phase4a-spec.md §5
 *
 * ⚠️ SKELETON: doExecute throws UnsupportedOperationException. Sister chat to wire to
 * BusinessRuleService.create + return saved rule.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-18
 */
@Slf4j
@Component
public class RuleCreateTool extends AbstractBusinessTool {

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
                "description", "SpEL 条件表达式, 如 supplier.blacklisted == true"));
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

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        throw new UnsupportedOperationException(
                "RuleCreateTool.doExecute not yet implemented (Phase 4a skeleton). "
                + "Sister chat: invoke BusinessRuleService.create + return saved entity.");
    }
}
