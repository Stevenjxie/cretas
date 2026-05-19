package com.cretas.aims.ai.tool.impl.alerts;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Tool — 查询告警事件 (Phase 2 Canvas-Alerts skeleton).
 *
 * <p>无必填参数, default 返 OPEN 事件第一页 20 条. sister chat impl 调
 * {@code AlertEngineService.findEvents(factoryId, status, pageable)}.
 *
 * @since 2026-05-18 (Phase 2 skeleton)
 */
@Slf4j
@Component
public class AlertEventQueryTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "alert_event_query";
    }

    @Override
    public String getDescription() {
        return "查询告警事件 (分页). 可按 status 过滤 (OPEN/ACKNOWLEDGED/RESOLVED). "
                + "无参时默认返 OPEN 第一页 20 条. "
                + "适用场景: '今天有几条告警' / '看待处理事件'.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> status = new HashMap<>();
        status.put("type", "string");
        status.put("description", "事件状态过滤 (可选)");
        status.put("enum", List.of("OPEN", "ACKNOWLEDGED", "RESOLVED"));

        Map<String, Object> page = new HashMap<>();
        page.put("type", "integer");
        page.put("description", "页码 (从 0 开始, 默认 0)");

        Map<String, Object> size = new HashMap<>();
        size.put("type", "integer");
        size.put("description", "每页数量 (默认 20)");

        Map<String, Object> properties = new HashMap<>();
        properties.put("status", status);
        properties.put("page", page);
        properties.put("size", size);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        throw new UnsupportedOperationException(
                "Phase 2 sister chat impl pending: alert_event_query");
    }
}
