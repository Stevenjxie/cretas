package com.cretas.aims.ai.tool.impl.alerts;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.alerts.AlertEvent;
import com.cretas.aims.entity.alerts.AlertEventStatus;
import com.cretas.aims.repository.alerts.AlertEventRepository;
import com.cretas.aims.service.alerts.AlertEngineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI Tool — 确认告警事件 (Phase 2 Canvas-Alerts).
 *
 * <p>OPEN → ACKNOWLEDGED 状态转换. 责任人从 context.userId 取.
 *
 * @since 2026-05-18 (Phase 2 impl)
 */
@Slf4j
@Component
public class AlertEventAcknowledgeTool extends AbstractBusinessTool {

    @Autowired
    private AlertEngineService alertEngineService;

    @Autowired
    private AlertEventRepository eventRepository;

    @Override
    public String getToolName() {
        return "alert_event_acknowledge";
    }

    @Override
    public String getDescription() {
        return "确认告警事件 (OPEN → ACKNOWLEDGED). 必填 eventId. "
                + "确认人 = 当前登录用户. "
                + "适用场景: '我已看到这条告警, 标记确认'.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> eventId = new HashMap<>();
        eventId.put("type", "string");
        eventId.put("description", "告警事件 ID (UUID)");

        Map<String, Object> properties = new HashMap<>();
        properties.put("eventId", eventId);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("eventId"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("eventId");
    }

    @Override
    public boolean supportsPreview() {
        return true;
    }

    @Override
    protected Map<String, Object> doPreview(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        AlertEvent existing = loadEvent(factoryId, params);
        Long userId = getUserId(context);

        Map<String, Object> result = new HashMap<>();
        result.put("status", "PREVIEW");
        result.put("action", "ACKNOWLEDGE");
        result.put("eventId", existing.getId().toString());
        result.put("currentStatus", existing.getStatus().name());
        result.put("newStatus", AlertEventStatus.ACKNOWLEDGED.name());
        result.put("ackedByUserId", userId);
        result.put("ruleId", existing.getRuleId() != null ? existing.getRuleId().toString() : null);
        result.put("severity", existing.getSeverity() != null ? existing.getSeverity().name() : null);
        result.put("message", String.format(
                "即将确认告警事件 (id=%s, 严重度=%s); 当前状态=%s, 新状态=ACKNOWLEDGED. 确认人=%s",
                existing.getId(),
                existing.getSeverity() != null ? existing.getSeverity().name() : "未知",
                existing.getStatus().name(),
                userId));
        return result;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        AlertEvent existing = loadEvent(factoryId, params);
        Long userId = getUserId(context);

        AlertEvent saved = alertEngineService.acknowledge(existing.getId(), userId);
        log.info("alert_event_acknowledge: factoryId={}, eventId={}, userId={}",
                factoryId, saved.getId(), userId);

        Map<String, Object> data = new HashMap<>();
        data.put("eventId", saved.getId().toString());
        data.put("factoryId", factoryId);
        data.put("status", saved.getStatus().name());
        data.put("ackedByUserId", saved.getAckedByUserId());
        data.put("ackedAt", saved.getAckedAt() != null ? saved.getAckedAt().toString() : null);

        return buildSimpleResult(
                String.format("告警事件 (id=%s) 已确认", saved.getId()), data);
    }

    private AlertEvent loadEvent(String factoryId, Map<String, Object> params) {
        String eventIdStr = getString(params, "eventId");
        UUID eventUuid;
        try {
            eventUuid = UUID.fromString(eventIdStr);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("eventId 不是合法 UUID: " + eventIdStr);
        }
        AlertEvent event = eventRepository.findById(eventUuid)
                .orElseThrow(() -> new IllegalArgumentException("告警事件不存在: id=" + eventIdStr));
        if (!event.getFactoryId().equals(factoryId)) {
            throw new IllegalArgumentException(
                    "告警事件不属于工厂 " + factoryId + " (实际工厂=" + event.getFactoryId() + ")");
        }
        return event;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
