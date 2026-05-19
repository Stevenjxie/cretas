package com.cretas.aims.ai.tool.impl.alerts;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Tool — 更新告警规则 (Phase 2 Canvas-Alerts skeleton).
 *
 * <p>sister chat impl 注意: preview 模式必返当前值 + 新值对比 (per fool-proof Rule 1).
 *
 * @since 2026-05-18 (Phase 2 skeleton)
 */
@Slf4j
@Component
public class AlertRuleUpdateTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "alert_rule_update";
    }

    @Override
    public String getDescription() {
        return "更新告警规则字段 (ruleName / triggerConditionSpel / severity / "
                + "notifyChannels / notifyRoles / enabled). 必填 ruleId. "
                + "适用场景: 调整库存阈值 / 改通知渠道 / 改严重度.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> ruleId = new HashMap<>();
        ruleId.put("type", "string");
        ruleId.put("description", "告警规则 ID (UUID)");

        Map<String, Object> ruleName = new HashMap<>();
        ruleName.put("type", "string");
        ruleName.put("description", "新规则名称 (工厂内唯一, 可选)");

        Map<String, Object> spel = new HashMap<>();
        spel.put("type", "string");
        spel.put("description", "新 SpEL 触发条件 (可选)");

        Map<String, Object> severity = new HashMap<>();
        severity.put("type", "string");
        severity.put("description", "新严重度 (可选)");
        severity.put("enum", List.of("LOW", "MID", "HIGH"));

        Map<String, Object> notifyChannels = new HashMap<>();
        notifyChannels.put("type", "array");
        notifyChannels.put("description", "新通知渠道 list (可选)");

        Map<String, Object> notifyRoles = new HashMap<>();
        notifyRoles.put("type", "array");
        notifyRoles.put("description", "新通知角色 list (可选)");

        Map<String, Object> enabled = new HashMap<>();
        enabled.put("type", "boolean");
        enabled.put("description", "启用状态 (可选, 优先用 alert_rule_toggle)");

        Map<String, Object> properties = new HashMap<>();
        properties.put("ruleId", ruleId);
        properties.put("ruleName", ruleName);
        properties.put("triggerConditionSpel", spel);
        properties.put("severity", severity);
        properties.put("notifyChannels", notifyChannels);
        properties.put("notifyRoles", notifyRoles);
        properties.put("enabled", enabled);

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
                "Phase 2 sister chat impl pending: alert_rule_update");
    }
}
