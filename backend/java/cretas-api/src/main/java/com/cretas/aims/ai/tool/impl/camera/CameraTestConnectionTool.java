package com.cretas.aims.ai.tool.impl.camera;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.isapi.IsapiDevice;
import com.cretas.aims.service.isapi.IsapiDeviceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 摄像头连接测试工具
 *
 * 测试指定摄像头的网络连接是否正常。
 *
 * Intent Code: CAMERA_TEST_CONNECTION
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class CameraTestConnectionTool extends AbstractBusinessTool {

    @Autowired
    private IsapiDeviceService deviceService;

    @Override
    public String getToolName() {
        return "camera_test_connection";
    }

    @Override
    public String getDescription() {
        return "测试摄像头网络连接。检查指定摄像头是否可以正常连接。" +
                "适用场景：检测摄像头连通性、排查网络故障、验证设备配置。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> deviceId = new HashMap<>();
        deviceId.put("type", "string");
        deviceId.put("description", "摄像头设备ID");
        properties.put("deviceId", deviceId);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("deviceId"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("deviceId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行摄像头连接测试 - 工厂ID: {}, 参数: {}", factoryId, params);

        String deviceId = getString(params, "deviceId");

        // P0-1: authz before connection test — 避免 testConnection 自身无 factoryId 校验导致跨工厂探测
        deviceService.getDevice(factoryId, deviceId);
        boolean success = deviceService.testConnection(deviceId);
        IsapiDevice device = deviceService.getDevice(factoryId, deviceId);

        Map<String, Object> result = new HashMap<>();
        result.put("deviceId", deviceId);
        result.put("deviceName", device.getDeviceName());
        result.put("connected", success);
        result.put("status", device.getStatus().name());
        result.put("message", success
                ? "连接成功: " + device.getDeviceName()
                : "连接失败: " + device.getDeviceName() + " - " + device.getLastError());

        log.info("摄像头连接测试完成 - 设备ID: {}, 结果: {}", deviceId, success ? "成功" : "失败");

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "deviceId", "请问要测试哪个摄像头的连接？请提供设备ID。"
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
