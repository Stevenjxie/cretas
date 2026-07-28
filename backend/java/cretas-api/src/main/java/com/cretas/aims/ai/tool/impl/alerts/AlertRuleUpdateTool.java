package com.cretas.aims.ai.tool.impl.alerts;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.alerts.AlertRule;
import com.cretas.aims.entity.alerts.AlertSeverity;
import com.cretas.aims.repository.alerts.AlertRuleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI Tool — 更新告警规则 (Phase 2 Canvas-Alerts).
 *
 * <p>支持字段: ruleName / triggerConditionSpel / severity / notifyChannels /
 * notifyRoles / enabled. PATCH 语义 (null 不变).
 *
 * @since 2026-05-18 (Phase 2 impl)
 */
@Slf4j
@Component
public class AlertRuleUpdateTool extends AbstractBusinessTool {

    @Autowired
    private AlertRuleRepository ruleRepository;

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
    protected Map<String, Object> doPreview(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        AlertRule existing = loadRule(factoryId, params);

        Map<String, Object> currentValues = new HashMap<>();
        currentValues.put("ruleName", existing.getRuleName());
        currentValues.put("triggerConditionSpel", existing.getTriggerConditionSpel());
        currentValues.put("severity", existing.getSeverity() != null ? existing.getSeverity().name() : null);
        currentValues.put("notifyChannels", existing.getNotifyChannels());
        currentValues.put("notifyRoles", existing.getNotifyRoles());
        currentValues.put("enabled", existing.getEnabled());

        Map<String, Object> newValues = computeChanges(existing, params);

        Map<String, Object> result = new HashMap<>();
        result.put("status", "PREVIEW");
        result.put("action", "UPDATE");
        result.put("ruleId", existing.getId().toString());
        result.put("currentValues", currentValues);
        result.put("newValues", newValues);
        result.put("message", String.format(
                "即将更新告警规则 %s (id=%s); 共 %d 字段变化",
                existing.getRuleName(), existing.getId(), newValues.size()));
        return result;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        AlertRule existing = loadRule(factoryId, params);

        String newRuleName = getString(params, "ruleName");
        if (newRuleName != null && !newRuleName.isBlank() && !newRuleName.equals(existing.getRuleName())) {
            ruleRepository.findByFactoryIdAndRuleName(factoryId, newRuleName).ifPresent(other -> {
                if (!other.getId().equals(existing.getId())) {
                    throw new IllegalArgumentException(
                            "新规则名称 '" + newRuleName + "' 已被其他规则占用 (id=" + other.getId() + ")");
                }
            });
            existing.setRuleName(newRuleName);
        }

        if (params.containsKey("triggerConditionSpel")) {
            existing.setTriggerConditionSpel(getString(params, "triggerConditionSpel"));
        }

        String severity = getString(params, "severity");
        if (severity != null && !severity.isBlank()) {
            existing.setSeverity(AlertSeverity.valueOf(severity.toUpperCase()));
        }

        if (params.containsKey("notifyChannels")) {
            existing.setNotifyChannels(parseList(params, "notifyChannels"));
        }
        if (params.containsKey("notifyRoles")) {
            existing.setNotifyRoles(parseList(params, "notifyRoles"));
        }

        Boolean enabled = getBoolean(params, "enabled");
        if (enabled != null) {
            existing.setEnabled(enabled);
        }

        AlertRule saved = ruleRepository.save(existing);
        log.info("alert_rule_update: factoryId={}, ruleId={}, ruleName={}",
                factoryId, saved.getId(), saved.getRuleName());

        Map<String, Object> data = new HashMap<>();
        data.put("ruleId", saved.getId().toString());
        data.put("factoryId", factoryId);
        data.put("ruleName", saved.getRuleName());
        data.put("severity", saved.getSeverity().name());
        data.put("enabled", saved.getEnabled());

        return buildSimpleResult(String.format("告警规则 %s (id=%s) 更新成功",
                saved.getRuleName(), saved.getId()), data);
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

    private Map<String, Object> computeChanges(AlertRule existing, Map<String, Object> params) {
        Map<String, Object> changes = new HashMap<>();
        String newName = getString(params, "ruleName");
        if (newName != null && !newName.isBlank() && !newName.equals(existing.getRuleName())) {
            changes.put("ruleName", newName);
        }
        if (params.containsKey("triggerConditionSpel")) {
            changes.put("triggerConditionSpel", getString(params, "triggerConditionSpel"));
        }
        String severity = getString(params, "severity");
        if (severity != null && !severity.isBlank()) {
            changes.put("severity", severity.toUpperCase());
        }
        if (params.containsKey("notifyChannels")) {
            changes.put("notifyChannels", parseList(params, "notifyChannels"));
        }
        if (params.containsKey("notifyRoles")) {
            changes.put("notifyRoles", parseList(params, "notifyRoles"));
        }
        Boolean enabled = getBoolean(params, "enabled");
        if (enabled != null) {
            changes.put("enabled", enabled);
        }
        return changes;
    }

    @SuppressWarnings("unchecked")
    private List<String> parseList(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) {
            return new ArrayList<>();
        }
        if (value instanceof List) {
            List<String> out = new ArrayList<>();
            for (Object o : (List<Object>) value) {
                if (o != null) out.add(o.toString());
            }
            return out;
        }
        return new ArrayList<>();
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
