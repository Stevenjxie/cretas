package com.cretas.aims.ai.tool.impl.camera;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.isapi.IsapiEventLog;
import com.cretas.aims.repository.isapi.IsapiEventLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 摄像头告警事件查询工具
 *
 * 查询摄像头的告警事件记录，支持按设备筛选和分页。
 *
 * Intent Code: CAMERA_EVENTS
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class CameraEventsTool extends AbstractBusinessTool {

    @Autowired
    private IsapiEventLogRepository eventLogRepository;

    @Override
    public String getToolName() {
        return "camera_events";
    }

    @Override
    public String getDescription() {
        return "查询摄像头告警事件记录。支持按设备ID筛选和分页查询。" +
                "适用场景：查看告警记录、查询事件历史、了解报警情况。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> deviceId = new HashMap<>();
        deviceId.put("type", "string");
        deviceId.put("description", "摄像头设备ID，不传则查询所有设备的事件");
        properties.put("deviceId", deviceId);

        Map<String, Object> todayOnly = new HashMap<>();
        todayOnly.put("type", "boolean");
        todayOnly.put("description", "是否只查询今日事件，默认false");
        todayOnly.put("default", false);
        properties.put("todayOnly", todayOnly);

        Map<String, Object> page = new HashMap<>();
        page.put("type", "integer");
        page.put("description", "页码，从0开始");
        page.put("default", 0);
        page.put("minimum", 0);
        properties.put("page", page);

        Map<String, Object> size = new HashMap<>();
        size.put("type", "integer");
        size.put("description", "每页记录数");
        size.put("default", 20);
        size.put("minimum", 1);
        size.put("maximum", 100);
        properties.put("size", size);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行摄像头告警事件查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        String deviceId = getString(params, "deviceId");
        Boolean todayOnly = getBoolean(params, "todayOnly", false);
        Integer page = getInteger(params, "page", 0);
        Integer size = getInteger(params, "size", 20);

        Pageable pageable = PageRequest.of(page, size);
        Page<IsapiEventLog> events;

        if (deviceId != null && !deviceId.isEmpty()) {
            events = eventLogRepository.findByFactoryIdAndDeviceIdOrderByEventTimeDesc(
                    factoryId, deviceId, pageable);
        } else if (todayOnly) {
            LocalDateTime todayStart = LocalDate.now().atStartOfDay();
            events = eventLogRepository.findByTimeRange(factoryId, todayStart, LocalDateTime.now(), pageable);
        } else {
            events = eventLogRepository.findByFactoryIdOrderByEventTimeDesc(factoryId, pageable);
        }

        List<Map<String, Object>> eventList = events.getContent().stream()
                .map(this::convertEventToMap)
                .collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("events", eventList);
        result.put("totalElements", events.getTotalElements());
        result.put("totalPages", events.getTotalPages());
        result.put("message", String.format("共 %d 条告警记录", events.getTotalElements()));

        log.info("摄像头告警事件查询完成 - 总数: {}", events.getTotalElements());

        return result;
    }

    private Map<String, Object> convertEventToMap(IsapiEventLog event) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", event.getId());
        map.put("deviceId", event.getDeviceId());
        map.put("eventType", event.getEventType());
        map.put("eventTypeName", event.getEventTypeName());
        map.put("eventState", event.getEventState() != null ? event.getEventState().name() : null);
        map.put("eventDescription", event.getEventDescription());
        map.put("channelId", event.getChannelId());
        map.put("eventTime", event.getEventTime());
        map.put("processed", event.getProcessed());
        return map;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "deviceId", "请问要查看哪个摄像头的告警事件？不指定则查看所有设备。",
            "todayOnly", "请问是否只查看今天的告警事件？",
            "page", "请问要查看第几页？",
            "size", "请问每页显示多少条？"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "deviceId", "设备ID",
            "todayOnly", "仅今日",
            "page", "页码",
            "size", "每页数量"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
