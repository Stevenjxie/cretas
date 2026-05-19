package com.cretas.aims.ai.tool.impl.alerts;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Tool — 启用/禁用 告警规则 (Phase 2 Canvas-Alerts skeleton).
 *
 * <p>sister chat impl: flip {@code rule.enabled}. preview 返当前状态 + 新状态.
 *
 * @since 2026-05-18 (Phase 2 skeleton)
 */
@Slf4j
@Component
public class AlertRuleToggleTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "alert_rule_toggle";
    }

    @Override
    public String getDescription() {
        return "切换告警规则启用/禁用状态. 必填 ruleId. "
                + "禁用后 scheduler / event listener 不再评估此规则. "
                + "适用场景: 临时关闭某条规则 / 重新启用.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> ruleId = new HashMap<>();
        ruleId.put("type", "string");
        ruleId.put("description", "告警规则 ID (UUID)");

        Map<String, Object> properties = new HashMap<>();
        properties.put("ruleId", ruleId);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("ruleId"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("ruleId");
    }

    @Override
    public boolean supportsPreview() {
        return true;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        throw new UnsupportedOperationException(
                "Phase 2 sister chat impl pending: alert_rule_toggle");
    }
}
