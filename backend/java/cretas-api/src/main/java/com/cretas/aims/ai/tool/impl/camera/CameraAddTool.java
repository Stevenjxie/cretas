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
 * 添加摄像头设备工具
 *
 * 添加新的海康威视 ISAPI 摄像头设备，需要提供设备名称、IP地址、用户名、密码等信息。
 *
 * Intent Code: CAMERA_ADD
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class CameraAddTool extends AbstractBusinessTool {

    @Autowired
    private IsapiDeviceService deviceService;

    @Override
    public String getToolName() {
        return "camera_add";
    }

    @Override
    public String getDescription() {
        return "添加摄像头设备。需要提供设备名称、IP地址、用户名、密码等信息。" +
                "适用场景：添加新摄像头、注册新设备、接入新的监控摄像头。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> deviceName = new HashMap<>();
        deviceName.put("type", "string");
        deviceName.put("description", "设备名称");
        properties.put("deviceName", deviceName);

        Map<String, Object> ipAddress = new HashMap<>();
        ipAddress.put("type", "string");
        ipAddress.put("description", "设备IP地址");
        properties.put("ipAddress", ipAddress);

        Map<String, Object> port = new HashMap<>();
        port.put("type", "integer");
        port.put("description", "设备端口号，默认80");
        port.put("default", 80);
        properties.put("port", port);

        Map<String, Object> username = new HashMap<>();
        username.put("type", "string");
        username.put("description", "设备登录用户名");
        properties.put("username", username);

        Map<String, Object> password = new HashMap<>();
        password.put("type", "string");
        password.put("description", "设备登录密码");
        properties.put("password", password);

        Map<String, Object> deviceType = new HashMap<>();
        deviceType.put("type", "string");
        deviceType.put("description", "设备类型，如 IPC、NVR、DVR，默认 IPC");
        deviceType.put("default", "IPC");
        properties.put("deviceType", deviceType);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("deviceName", "ipAddress", "username", "password"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("deviceName", "ipAddress", "username", "password");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行添加摄像头设备 - 工厂ID: {}, 参数: {}", factoryId, params);

        String deviceName = getString(params, "deviceName");
        String ipAddress = getString(params, "ipAddress");
        Integer port = getInteger(params, "port", 80);
        String username = getString(params, "username");
        String password = getString(params, "password");
        String deviceType = getString(params, "deviceType", "IPC");

        IsapiDeviceDTO dto = IsapiDeviceDTO.builder()
                .deviceName(deviceName)
                .deviceType(IsapiDevice.DeviceType.valueOf(deviceType.toUpperCase()))
                .ipAddress(ipAddress)
                .port(port)
                .username(username)
                .password(password)
                .build();

        IsapiDevice device = deviceService.addDevice(factoryId, dto);

        Map<String, Object> result = new HashMap<>();
        result.put("message", "摄像头添加成功: " + device.getDeviceName());
        result.put("device", deviceService.toDTO(device));

        log.info("添加摄像头设备完成 - 设备ID: {}, 设备名: {}", device.getId(), device.getDeviceName());

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "deviceName", "请问新摄像头的名称是什么？",
            "ipAddress", "请问摄像头的IP地址是多少？",
            "username", "请问摄像头的登录用户名是什么？",
            "password", "请问摄像头的登录密码是什么？",
            "port", "请问摄像头的端口号是多少？默认为80。",
            "deviceType", "请问设备类型是什么？如 IPC、NVR、DVR，默认为 IPC。"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "deviceName", "设备名称",
            "ipAddress", "IP地址",
            "port", "端口号",
            "username", "用户名",
            "password", "密码",
            "deviceType", "设备类型"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
