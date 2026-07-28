package com.cretas.aims.ai.tool.impl.camera;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.isapi.IsapiDevice;
import com.cretas.aims.service.isapi.IsapiDeviceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 摄像头信息同步工具
 *
 * 从摄像头设备同步最新的配置信息到系统。
 *
 * Intent Code: CAMERA_SYNC
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class CameraSyncTool extends AbstractBusinessTool {

    @Autowired
    private IsapiDeviceService deviceService;

    @Override
    public String getToolName() {
        return "camera_sync";
    }

    @Override
    public String getDescription() {
        return "同步摄像头设备信息。从摄像头拉取最新的配置和状态信息。" +
                "适用场景：同步设备配置、更新设备信息、刷新设备状态。";
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
        log.info("执行摄像头信息同步 - 工厂ID: {}, 参数: {}", factoryId, params);

        String deviceId = getString(params, "deviceId");

        // P0-1: authz before sync — 避免 syncDeviceInfo 自身无 factoryId 校验导致跨工厂同步
        deviceService.getDevice(factoryId, deviceId);
        deviceService.syncDeviceInfo(deviceId);
        IsapiDevice device = deviceService.getDevice(factoryId, deviceId);

        Map<String, Object> result = new HashMap<>();
        result.put("message", "同步成功: " + device.getDeviceName());
        result.put("device", deviceService.toDTO(device));

        log.info("摄像头信息同步完成 - 设备ID: {}, 设备名: {}", deviceId, device.getDeviceName());

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "deviceId", "请问要同步哪个摄像头的信息？请提供设备ID。"
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
