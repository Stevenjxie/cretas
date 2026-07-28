package com.cretas.aims.ai.tool.impl.camera;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.isapi.IsapiDeviceDTO;
import com.cretas.aims.entity.isapi.IsapiDevice;
import com.cretas.aims.service.isapi.IsapiDeviceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 摄像头详情查询工具
 *
 * 查询指定摄像头设备的详细信息，包括设备状态、配置、通道信息等。
 *
 * Intent Code: CAMERA_DETAIL
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class CameraDetailTool extends AbstractBusinessTool {

    @Autowired
    private IsapiDeviceService deviceService;

    @Override
    public String getToolName() {
        return "camera_detail";
    }

    @Override
    public String getDescription() {
        return "查询摄像头详情。返回指定摄像头的完整信息，包括设备名称、IP地址、状态、通道等。" +
                "适用场景：查看摄像头详细信息、了解设备当前状态和配置。";
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
        log.info("执行摄像头详情查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        String deviceId = getString(params, "deviceId");

        IsapiDevice device = deviceService.getDevice(factoryId, deviceId);
        IsapiDeviceDTO dto = deviceService.toDTO(device);

        Map<String, Object> result = new HashMap<>();
        result.put("message", "摄像头详情: " + device.getDeviceName());
        result.put("device", dto);

        log.info("摄像头详情查询完成 - 设备ID: {}, 设备名: {}", deviceId, device.getDeviceName());

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "deviceId", "请问您要查看哪个摄像头的详情？请提供设备ID。"
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

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
