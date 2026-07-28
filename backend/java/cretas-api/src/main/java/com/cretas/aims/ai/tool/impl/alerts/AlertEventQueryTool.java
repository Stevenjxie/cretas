package com.cretas.aims.ai.tool.impl.alerts;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.alerts.AlertEvent;
import com.cretas.aims.entity.alerts.AlertEventStatus;
import com.cretas.aims.service.alerts.AlertEngineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Tool — 查询告警事件 (Phase 2 Canvas-Alerts).
 *
 * <p>默认 OPEN 第一页 20 条. 可按 status 过滤.
 *
 * @since 2026-05-18 (Phase 2 impl)
 */
@Slf4j
@Component
public class AlertEventQueryTool extends AbstractBusinessTool {

    @Autowired
    private AlertEngineService alertEngineService;

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
        AlertEventStatus status = parseStatus(params);
        int page = getInteger(params, "page", 0);
        int size = getInteger(params, "size", 20);
        if (size > 100) size = 100;
        if (size < 1) size = 20;

        Pageable pageable = PageRequest.of(page, size);
        Page<AlertEvent> result = alertEngineService.findEvents(factoryId, status, pageable);

        List<Map<String, Object>> content = new ArrayList<>();
        for (AlertEvent event : result.getContent()) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", event.getId().toString());
            row.put("ruleId", event.getRuleId() != null ? event.getRuleId().toString() : null);
            row.put("factoryId", event.getFactoryId());
            row.put("businessEntityType", event.getBusinessEntityType());
            row.put("businessEntityId", event.getBusinessEntityId());
            row.put("severity", event.getSeverity() != null ? event.getSeverity().name() : null);
            row.put("message", event.getMessage());
            row.put("status", event.getStatus() != null ? event.getStatus().name() : null);
            row.put("ackedByUserId", event.getAckedByUserId());
            row.put("ackedAt", event.getAckedAt() != null ? event.getAckedAt().toString() : null);
            row.put("resolvedByUserId", event.getResolvedByUserId());
            row.put("resolvedAt", event.getResolvedAt() != null ? event.getResolvedAt().toString() : null);
            row.put("createdAt", event.getCreatedAt() != null ? event.getCreatedAt().toString() : null);
            content.add(row);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("content", content);
        data.put("totalElements", result.getTotalElements());
        data.put("totalPages", result.getTotalPages());
        data.put("currentPage", result.getNumber());
        data.put("hasMore", result.getNumber() < result.getTotalPages() - 1);
        data.put("status", status != null ? status.name() : "ALL");

        return buildSimpleResult(String.format(
                "查询到 %d 条告警事件 (factoryId=%s, status=%s, page=%d/%d)",
                result.getTotalElements(), factoryId,
                status != null ? status.name() : "ALL",
                result.getNumber(), result.getTotalPages()), data);
    }

    private AlertEventStatus parseStatus(Map<String, Object> params) {
        String raw = getString(params, "status");
        if (raw == null || raw.isBlank()) {
            return AlertEventStatus.OPEN;  // default
        }
        if ("ALL".equalsIgnoreCase(raw)) {
            return null;  // no filter
        }
        try {
            return AlertEventStatus.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "status 不合法: " + raw + " (允许 OPEN/ACKNOWLEDGED/RESOLVED/ALL)");
        }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
