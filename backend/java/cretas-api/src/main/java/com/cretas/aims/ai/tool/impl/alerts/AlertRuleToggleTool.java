package com.cretas.aims.ai.tool.impl.alerts;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.alerts.AlertRule;
import com.cretas.aims.repository.alerts.AlertRuleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI Tool — 启用/禁用 告警规则 (Phase 2 Canvas-Alerts).
 *
 * <p>Toggle 切换 {@code rule.enabled}. 禁用后 scheduler / listener 不再评估.
 *
 * @since 2026-05-18 (Phase 2 impl)
 */
@Slf4j
@Component
public class AlertRuleToggleTool extends AbstractBusinessTool {

    @Autowired
    private AlertRuleRepository ruleRepository;

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
    protected Map<String, Object> doPreview(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        AlertRule existing = loadRule(factoryId, params);
        boolean currentlyEnabled = Boolean.TRUE.equals(existing.getEnabled());

        Map<String, Object> result = new HashMap<>();
        result.put("status", "PREVIEW");
        result.put("action", "TOGGLE");
        result.put("ruleId", existing.getId().toString());
        result.put("ruleName", existing.getRuleName());
        result.put("currentEnabled", currentlyEnabled);
        result.put("newEnabled", !currentlyEnabled);
        result.put("message", String.format(
                "即将 %s 告警规则 %s (id=%s)",
                currentlyEnabled ? "禁用" : "启用",
                existing.getRuleName(), existing.getId()));
        return result;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        AlertRule existing = loadRule(factoryId, params);
        boolean current = Boolean.TRUE.equals(existing.getEnabled());
        existing.setEnabled(!current);

        AlertRule saved = ruleRepository.save(existing);
        log.info("alert_rule_toggle: factoryId={}, ruleId={}, enabled {} -> {}",
                factoryId, saved.getId(), current, saved.getEnabled());

        Map<String, Object> data = new HashMap<>();
        data.put("ruleId", saved.getId().toString());
        data.put("factoryId", factoryId);
        data.put("ruleName", saved.getRuleName());
        data.put("previousEnabled", current);
        data.put("enabled", saved.getEnabled());

        return buildSimpleResult(String.format("告警规则 %s (id=%s) 已 %s",
                saved.getRuleName(), saved.getId(),
                saved.getEnabled() ? "启用" : "禁用"), data);
    }

    private AlertRule loadRule(String factoryId, Map<String, Object> params) {
        String ruleIdStr = getString(params, "ruleId");
        UUID ruleUuid;
        try {
            ruleUuid = UUID.fromString(ruleIdStr);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("ruleId 不是合法 UUID: " + ruleIdStr);
        }
        AlertRule rule = ruleRepository.findById(ruleUuid)
                .orElseThrow(() -> new IllegalArgumentException("告警规则不存在: id=" + ruleIdStr));
        if (!rule.getFactoryId().equals(factoryId)) {
            throw new IllegalArgumentException(
                    "告警规则不属于工厂 " + factoryId + " (实际工厂=" + rule.getFactoryId() + ")");
        }
        return rule;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
