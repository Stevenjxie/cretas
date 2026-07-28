package com.cretas.aims.ai.tool.impl.camera;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.isapi.IsapiDevice;
import com.cretas.aims.repository.isapi.IsapiDeviceRepository;
import com.cretas.aims.service.isapi.IsapiDeviceService;
import com.cretas.aims.service.isapi.IsapiEventSubscriptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 摄像头状态查询工具
 *
 * 查询工厂中所有摄像头的在线/离线状态统计和离线设备列表。
 *
 * Intent Code: CAMERA_STATUS
 *
 * factoryId 隔离豁免说明: subscriptionService.getActiveSubscriptionCount() 是
 * 进程级 in-memory counter (ConcurrentMap), 返回当前 JVM 活跃订阅总数,
 * 不涉及跨工厂数据读取. 设备查询使用 deviceRepository.findByFactoryId 已带 factoryId 过滤.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class CameraStatusTool extends AbstractBusinessTool {

    @Autowired
    private IsapiDeviceService deviceService;

    @Autowired
    private IsapiDeviceRepository deviceRepository;

    @Autowired
    private IsapiEventSubscriptionService subscriptionService;

    @Override
    public String getToolName() {
        return "camera_status";
    }

    @Override
    public String getDescription() {
        return "查询摄像头设备状态。返回在线/离线/异常的设备统计和离线设备列表。" +
                "适用场景：检查设备运行状态、排查离线设备、监控设备健康度。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", Collections.emptyMap());
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行摄像头状态查询 - 工厂ID: {}", factoryId);

        Map<String, Long> statusStats = deviceService.getStatusStatistics(factoryId);

        long online = statusStats.getOrDefault("ONLINE", 0L);
        long offline = statusStats.getOrDefault("OFFLINE", 0L);
        long error = statusStats.getOrDefault("ERROR", 0L);
        long unknown = statusStats.getOrDefault("UNKNOWN", 0L);
        long total = online + offline + error + unknown;

        // 获取离线设备列表（最多10个）
        List<IsapiDevice> offlineDevices = deviceRepository.findByFactoryIdAndStatus(
                factoryId, IsapiDevice.DeviceStatus.OFFLINE);

        List<Map<String, String>> offlineList = offlineDevices.stream().limit(10)
                .map(d -> Map.of(
                        "id", d.getId(),
                        "name", d.getDeviceName(),
                        "ip", d.getIpAddress(),
                        "lastError", d.getLastError() != null ? d.getLastError() : "未知"
                ))
                .collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("statistics", statusStats);
        result.put("total", total);
        result.put("online", online);
        result.put("offline", offline);
        result.put("error", error);
        result.put("offlineDevices", offlineList);
        result.put("activeSubscriptions", subscriptionService.getActiveSubscriptionCount());
        result.put("message", String.format("摄像头状态: 在线 %d / 总计 %d", online, total));

        log.info("摄像头状态查询完成 - 在线: {}, 总计: {}", online, total);

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
