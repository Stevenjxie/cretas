package com.cretas.aims.ai.tool.impl.camera;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.isapi.IsapiCaptureDTO;
import com.cretas.aims.service.isapi.IsapiDeviceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 摄像头抓拍工具
 *
 * 从指定摄像头抓拍当前画面图片。
 *
 * Intent Code: CAMERA_CAPTURE
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class CameraCaptureTool extends AbstractBusinessTool {

    @Autowired
    private IsapiDeviceService deviceService;

    @Override
    public String getToolName() {
        return "camera_capture";
    }

    @Override
    public String getDescription() {
        return "从摄像头抓拍当前画面。返回抓拍的图片信息。" +
                "适用场景：抓拍监控画面、获取实时图片、拍照取证。";
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

        Map<String, Object> channelId = new HashMap<>();
        channelId.put("type", "integer");
        channelId.put("description", "通道号，默认1");
        channelId.put("default", 1);
        channelId.put("minimum", 1);
        properties.put("channelId", channelId);

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
        log.info("执行摄像头抓拍 - 工厂ID: {}, 参数: {}", factoryId, params);

        String deviceId = getString(params, "deviceId");
        Integer channelId = getInteger(params, "channelId", 1);

        IsapiCaptureDTO capture = deviceService.capturePicture(deviceId, channelId);

        if (!capture.getSuccess()) {
            throw new IllegalArgumentException("抓拍失败: " + capture.getError());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("message", "抓拍成功: " + capture.getDeviceName());
        result.put("capture", capture);

        log.info("摄像头抓拍完成 - 设备ID: {}", deviceId);

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "deviceId", "请问要从哪个摄像头抓拍？请提供设备ID或设备名称。",
            "channelId", "请问要抓拍哪个通道？默认为通道1。"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "deviceId", "设备ID",
            "channelId", "通道号"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
