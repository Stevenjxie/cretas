package com.cretas.aims.ai.tool.impl.rules;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Tool: soft-delete a Canvas-Rules BusinessRule.
 *
 * Spec: docs/superpowers/specs/2026-05-18-canvas-rules-phase4a-spec.md §5
 *
 * ⚠️ SKELETON: doExecute throws UnsupportedOperationException. Sister chat to wire.
 * Uses BaseEntity soft delete (SQLDelete UPDATE deleted_at=NOW()).
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-18
 */
@Slf4j
@Component
public class RuleDeleteTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "rule_delete";
    }

    @Override
    public String getDescription() {
        return "删除一条业务规则 (软删除)。例: '删除 vip_discount 规则'。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("ruleCode", Map.of("type", "string", "description", "要删除的规则代码"));

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
                "RuleDeleteTool.doExecute not yet implemented (Phase 4a skeleton). "
                + "Sister chat: BusinessRuleService.softDelete.");
    }
}
