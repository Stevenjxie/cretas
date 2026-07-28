package com.cretas.aims.ai.tool.impl.camera;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.isapi.IsapiDeviceDTO;
import com.cretas.aims.entity.isapi.IsapiDevice;
import com.cretas.aims.service.isapi.IsapiDeviceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 摄像头列表查询工具
 *
 * 查询工厂中所有摄像头设备列表，支持分页，返回设备信息和状态统计。
 *
 * Intent Code: CAMERA_LIST
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class CameraListTool extends AbstractBusinessTool {

    @Autowired
    private IsapiDeviceService deviceService;

    @Override
    public String getToolName() {
        return "camera_list";
    }

    @Override
    public String getDescription() {
        return "查询摄像头列表。返回工厂中所有摄像头设备，包括设备信息和在线状态统计。" +
                "适用场景：查看所有摄像头、浏览监控设备清单、获取摄像头概览。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

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
        log.info("执行摄像头列表查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        Integer page = getInteger(params, "page", 0);
        Integer size = getInteger(params, "size", 20);

        Page<IsapiDevice> devices = deviceService.listDevices(factoryId, PageRequest.of(page, size));

        List<IsapiDeviceDTO> deviceList = devices.getContent().stream()
                .map(deviceService::toDTO)
                .collect(Collectors.toList());

        Map<String, Long> statusStats = deviceService.getStatusStatistics(factoryId);

        long onlineCount = statusStats.getOrDefault("ONLINE", 0L);
        long totalCount = devices.getTotalElements();

        Map<String, Object> result = new HashMap<>();
        result.put("devices", deviceList);
        result.put("totalElements", totalCount);
        result.put("totalPages", devices.getTotalPages());
        result.put("statusStatistics", statusStats);
        result.put("message", String.format("共 %d 台摄像头，%d 台在线", totalCount, onlineCount));

        log.info("摄像头列表查询完成 - 总数: {}, 在线: {}", totalCount, onlineCount);

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "page", "请问要查看第几页的摄像头列表？",
            "size", "请问每页显示多少条记录？"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
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
