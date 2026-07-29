package com.cretas.aims.ai.tool.impl.camera;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.isapi.IsapiStreamDTO;
import com.cretas.aims.entity.isapi.IsapiDevice;
import com.cretas.aims.service.isapi.IsapiDeviceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 摄像头流地址获取工具
 *
 * 获取指定摄像头的流媒体播放地址。
 *
 * Intent Code: CAMERA_STREAMS
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class CameraStreamsTool extends AbstractBusinessTool {

    @Autowired
    private IsapiDeviceService deviceService;

    @Override
    public String getToolName() {
        return "camera_streams";
    }

    @Override
    public String getDescription() {
        return "获取摄像头的流媒体播放地址。返回指定摄像头的所有通道流地址。" +
                "适用场景：获取视频流地址、查看实时监控、获取RTSP地址。";
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
        log.info("执行获取摄像头流地址 - 工厂ID: {}, 参数: {}", factoryId, params);

        String deviceId = getString(params, "deviceId");

        List<IsapiStreamDTO> streams = deviceService.getStreamUrls(factoryId, deviceId);
        IsapiDevice device = deviceService.getDevice(factoryId, deviceId);

        Map<String, Object> result = new HashMap<>();
        result.put("message", "流地址获取成功: " + device.getDeviceName() + " (" + streams.size() + " 个通道)");
        result.put("deviceId", deviceId);
        result.put("deviceName", device.getDeviceName());
        result.put("streams", streams);

        log.info("获取摄像头流地址完成 - 设备ID: {}, 通道数: {}", deviceId, streams.size());

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "deviceId", "请问要获取哪个摄像头的流地址？请提供设备ID。"
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
