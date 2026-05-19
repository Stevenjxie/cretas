package com.cretas.aims.ai.tool.impl.rules;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Tool: toggle a Canvas-Rules BusinessRule enabled flag.
 *
 * Spec: docs/superpowers/specs/2026-05-18-canvas-rules-phase4a-spec.md §5
 *
 * ⚠️ SKELETON: doExecute throws UnsupportedOperationException. Sister chat to wire.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-18
 */
@Slf4j
@Component
public class RuleToggleTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "rule_toggle";
    }

    @Override
    public String getDescription() {
        return "启用或停用某条业务规则。例: '把 vip_discount 停掉' / '启用 po_blacklist'。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("ruleCode", Map.of("type", "string", "description", "要切换的规则代码"));
        properties.put("enabled", Map.of("type", "boolean", "description", "true=启用, false=停用"));

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Arrays.asList("ruleCode", "enabled"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("ruleCode", "enabled");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        throw new UnsupportedOperationException(
                "RuleToggleTool.doExecute not yet implemented (Phase 4a skeleton). "
                + "Sister chat: BusinessRuleService.toggle.");
    }
}
