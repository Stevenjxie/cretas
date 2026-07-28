package com.cretas.aims.ai.tool.impl.camera;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.isapi.IsapiEventSubscriptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 摄像头告警取消订阅工具
 *
 * 取消摄像头的告警事件订阅。支持取消单个设备或所有设备的订阅。
 *
 * Intent Code: CAMERA_UNSUBSCRIBE
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class CameraUnsubscribeTool extends AbstractBusinessTool {

    @Autowired
    private IsapiEventSubscriptionService subscriptionService;

    @Override
    public String getToolName() {
        return "camera_unsubscribe";
    }

    @Override
    public String getDescription() {
        return "取消摄像头告警订阅。可以取消单个设备或所有设备的告警推送。" +
                "适用场景：关闭告警监听、取消事件通知、停止报警推送。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> deviceId = new HashMap<>();
        deviceId.put("type", "string");
        deviceId.put("description", "摄像头设备ID。不传则取消所有设备订阅");
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
        log.info("执行取消摄像头告警订阅 - 工厂ID: {}, 参数: {}", factoryId, params);

        String deviceId = getString(params, "deviceId");

        Map<String, Object> result = new HashMap<>();

        if (deviceId != null && !deviceId.isEmpty()) {
            // 取消单个设备订阅 (P0-1: 跨工厂隔离校验)
            subscriptionService.unsubscribeDevice(factoryId, deviceId);

            result.put("message", "已取消告警订阅");
            result.put("deviceId", deviceId);
            result.put("subscribed", false);

            log.info("取消单个设备告警订阅完成 - 设备ID: {}", deviceId);
        } else {
            // 取消所有订阅
            subscriptionService.unsubscribeAllDevices(factoryId);

            result.put("message", "已取消所有摄像头的告警订阅");
            result.put("activeSubscriptions", 0);

            log.info("取消全部设备告警订阅完成 - 工厂ID: {}", factoryId);
        }

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "deviceId", "请问要取消哪个摄像头的告警订阅？不指定则取消所有设备。"
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
