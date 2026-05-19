package com.cretas.aims.ai.tool.impl.rules;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Tool: update an existing Canvas-Rules BusinessRule.
 *
 * Spec: docs/superpowers/specs/2026-05-18-canvas-rules-phase4a-spec.md §5
 *
 * ⚠️ SKELETON: doExecute throws UnsupportedOperationException. Sister chat to wire to
 * BusinessRuleService.update.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-18
 */
@Slf4j
@Component
public class RuleUpdateTool extends AbstractBusinessTool {

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

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        throw new UnsupportedOperationException(
                "RuleUpdateTool.doExecute not yet implemented (Phase 4a skeleton). "
                + "Sister chat: BusinessRuleService.update + return updated entity.");
    }
}
