package com.cretas.aims.ai.tool.impl.isapi;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.isapi.SmartAnalysisDTO;
import com.cretas.aims.service.isapi.IsapiSmartAnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * ISAPI 智能分析能力查询工具
 *
 * 查询海康威视摄像头的智能分析能力和当前配置状态。
 * 支持查看设备支持的智能分析功能。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-23
 */
@Slf4j
@Component
public class IsapiDetectionEventsQueryTool extends AbstractBusinessTool {

    @Autowired
    private IsapiSmartAnalysisService isapiSmartAnalysisService;

    @Override
    public String getToolName() {
        return "isapi_smart_capabilities_query";
    }

    @Override
    public String getDescription() {
        return "查询摄像头智能分析能力。查看设备支持哪些智能分析功能。" +
                "适用场景：检查设备能力、查询智能分析状态、了解设备支持的功能。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // deviceId: 设备ID（必需）
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
        String deviceId = getString(params, "deviceId", null);

        log.info("执行ISAPI智能分析能力查询 - 工厂ID: {}, 设备ID: {}", factoryId, deviceId);

        Map<String, Object> result = new HashMap<>();

        // 查询设备智能分析能力
        SmartAnalysisDTO.SmartCapabilities capabilities = isapiSmartAnalysisService.getSmartCapabilities(factoryId, deviceId);

        if (capabilities != null) {
            result.put("smartSupported", capabilities.getSmartSupported());
            result.put("lineDetectionSupported", capabilities.getLineDetectionSupported());
            result.put("fieldDetectionSupported", capabilities.getFieldDetectionSupported());
            result.put("faceDetectionSupported", capabilities.getFaceDetectionSupported());
            result.put("audioDetectionSupported", capabilities.getAudioDetectionSupported());
            result.put("motionDetectionSupported", capabilities.getMotionDetectionSupported());
            result.put("sceneChangeSupported", capabilities.getSceneChangeSupported());

            // 能力限制
            result.put("maxLineRules", capabilities.getMaxLineRules());
            result.put("maxFieldRules", capabilities.getMaxFieldRules());
            result.put("maxFaceRules", capabilities.getMaxFaceRules());

            // 生成能力说明
            List<String> supportedFeatures = new ArrayList<>();
            if (Boolean.TRUE.equals(capabilities.getLineDetectionSupported())) {
                supportedFeatures.add("越界检测");
            }
            if (Boolean.TRUE.equals(capabilities.getFieldDetectionSupported())) {
                supportedFeatures.add("区域入侵检测");
            }
            if (Boolean.TRUE.equals(capabilities.getFaceDetectionSupported())) {
                supportedFeatures.add("人脸检测");
            }
            if (Boolean.TRUE.equals(capabilities.getMotionDetectionSupported())) {
                supportedFeatures.add("移动侦测");
            }
            if (Boolean.TRUE.equals(capabilities.getAudioDetectionSupported())) {
                supportedFeatures.add("音频检测");
            }

            result.put("supportedFeatures", supportedFeatures);
            result.put("message", String.format("该设备支持 %d 种智能分析功能", supportedFeatures.size()));
        } else {
            result.put("smartSupported", false);
            result.put("message", "无法获取设备智能分析能力，请检查设备连接");
        }

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "deviceId", "请问要查询哪个摄像头的智能分析能力？请提供设备ID"
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
