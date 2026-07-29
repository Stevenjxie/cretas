package com.cretas.aims.ai.tool.impl.camera;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.isapi.IsapiDevice;
import com.cretas.aims.service.isapi.IsapiDeviceService;
import com.cretas.aims.service.isapi.IsapiEventSubscriptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 摄像头告警订阅工具
 *
 * 订阅摄像头的告警事件推送。支持订阅单个设备或工厂内所有设备。
 *
 * Intent Code: CAMERA_SUBSCRIBE
 *
 * factoryId 隔离豁免说明: subscribeDevice/getDevice 已迁移到 factoryId-aware
 * overload (P0-1 fix commit 79b76adff). 剩余 getActiveSubscriptionCount() 和
 * getActiveSubscriptionIds() 是进程级 in-memory state, 不跨工厂.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class CameraSubscribeTool extends AbstractBusinessTool {

    @Autowired
    private IsapiDeviceService deviceService;

    @Autowired
    private IsapiEventSubscriptionService subscriptionService;

    @Override
    public String getToolName() {
        return "camera_subscribe";
    }

    @Override
    public String getDescription() {
        return "订阅摄像头告警事件。可以订阅单个设备或所有设备的告警推送。" +
                "适用场景：开启告警监听、订阅事件通知、启用报警推送。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> deviceId = new HashMap<>();
        deviceId.put("type", "string");
        deviceId.put("description", "摄像头设备ID。不传则订阅所有设备");
        properties.put("deviceId", deviceId);

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
        log.info("执行摄像头告警订阅 - 工厂ID: {}, 参数: {}", factoryId, params);

        String deviceId = getString(params, "deviceId");

        Map<String, Object> result = new HashMap<>();

        if (deviceId != null && !deviceId.isEmpty()) {
            // 订阅单个设备 (P0-1: 跨工厂隔离校验)
            subscriptionService.subscribeDevice(factoryId, deviceId);
            IsapiDevice device = deviceService.getDevice(factoryId, deviceId);

            result.put("message", "已开启告警订阅: " + device.getDeviceName());
            result.put("deviceId", deviceId);
            result.put("deviceName", device.getDeviceName());
            result.put("subscribed", true);

            log.info("单个设备告警订阅完成 - 设备ID: {}", deviceId);
        } else {
            // 订阅所有设备
            subscriptionService.subscribeAllDevices(factoryId);
            int count = subscriptionService.getActiveSubscriptionCount();

            result.put("message", "已订阅所有在线摄像头告警，共 " + count + " 台");
            result.put("subscribedCount", count);
            result.put("subscribedDeviceIds", subscriptionService.getActiveSubscriptionIds());

            log.info("全部设备告警订阅完成 - 订阅数: {}", count);
        }

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "deviceId", "请问要订阅哪个摄像头的告警？不指定则订阅所有设备。"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "deviceId", "设备ID"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
