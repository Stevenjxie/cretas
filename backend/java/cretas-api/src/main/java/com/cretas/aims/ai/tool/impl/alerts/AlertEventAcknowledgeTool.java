package com.cretas.aims.ai.tool.impl.alerts;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Tool — 确认告警事件 (Phase 2 Canvas-Alerts skeleton).
 *
 * <p>sister chat impl: 调 {@code AlertEngineService.acknowledge(eventId, userId)},
 * userId 从 context.userId 取. preview 返事件详情 + "确认人将是 X, 是否继续".
 *
 * @since 2026-05-18 (Phase 2 skeleton)
 */
@Slf4j
@Component
public class AlertEventAcknowledgeTool extends AbstractBusinessTool {

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
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        throw new UnsupportedOperationException(
                "Phase 2 sister chat impl pending: alert_event_acknowledge");
    }
}
