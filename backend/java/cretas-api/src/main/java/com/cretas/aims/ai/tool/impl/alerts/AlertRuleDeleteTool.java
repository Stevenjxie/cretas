package com.cretas.aims.ai.tool.impl.alerts;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.alerts.AlertEventStatus;
import com.cretas.aims.entity.alerts.AlertRule;
import com.cretas.aims.repository.alerts.AlertEventRepository;
import com.cretas.aims.repository.alerts.AlertRuleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI Tool — 删除告警规则 (软删, Phase 2 Canvas-Alerts).
 *
 * <p>Preview 显示规则名 + 历史事件数 — per fool-proof Rule 2 让用户清楚后果.
 * Execute 调 {@code BaseEntity.softDelete()}.
 *
 * @since 2026-05-18 (Phase 2 impl)
 */
@Slf4j
@Component
public class AlertRuleDeleteTool extends AbstractBusinessTool {

    @Autowired
    private AlertRuleRepository ruleRepository;

    @Autowired
    private AlertEventRepository eventRepository;

    @Override
    public String getToolName() {
        return "alert_rule_delete";
    }

    @Override
    public String getDescription() {
        return "删除告警规则 (软删, 保留历史事件). 必填 ruleId. "
                + "适用场景: 永久关闭某条不再需要的规则.";
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
        long openCount = eventRepository.countByRuleIdAndStatus(existing.getId(), AlertEventStatus.OPEN);
        long ackCount = eventRepository.countByRuleIdAndStatus(existing.getId(), AlertEventStatus.ACKNOWLEDGED);
        long resolvedCount = eventRepository.countByRuleIdAndStatus(existing.getId(), AlertEventStatus.RESOLVED);
        long historyCount = openCount + ackCount + resolvedCount;

        Map<String, Object> result = new HashMap<>();
        result.put("status", "PREVIEW");
        result.put("action", "DELETE");
        result.put("ruleId", existing.getId().toString());
        result.put("ruleName", existing.getRuleName());
        result.put("alertType", existing.getAlertType().name());
        result.put("eventCounts", Map.of(
                "open", openCount,
                "acknowledged", ackCount,
                "resolved", resolvedCount,
                "total", historyCount));
        result.put("message", String.format(
                "即将软删除告警规则 %s (id=%s, %s); 关联 %d 条历史事件不会删除. 是否继续?",
                existing.getRuleName(), existing.getId(),
                existing.getAlertType().name(), historyCount));
        return result;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        AlertRule existing = loadRule(factoryId, params);
        existing.softDelete();
        AlertRule saved = ruleRepository.save(existing);
        log.info("alert_rule_delete: factoryId={}, ruleId={}, ruleName={}",
                factoryId, saved.getId(), saved.getRuleName());

        Map<String, Object> data = new HashMap<>();
        data.put("ruleId", saved.getId().toString());
        data.put("factoryId", factoryId);
        data.put("ruleName", saved.getRuleName());
        data.put("deletedAt", saved.getDeletedAt() != null ? saved.getDeletedAt().toString() : null);

        return buildSimpleResult(String.format("告警规则 %s (id=%s) 已软删除",
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

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
