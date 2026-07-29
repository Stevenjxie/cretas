package com.cretas.aims.ai.tool.impl.dahua;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.isapi.SmartAnalysisDTO;
import com.cretas.aims.service.dahua.DahuaDeviceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 大华摄像头智能分析配置工具
 *
 * 配置大华摄像头的智能分析功能，包括：
 * - 越界检测（CrossLine）：虚拟警戒线触发
 * - 区域入侵（CrossRegion）：禁区入侵检测
 * - 人脸检测（FaceDetection）：人脸识别与抓拍
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-23
 */
@Slf4j
@Component
public class DahuaSmartConfigTool extends AbstractBusinessTool {

    @Autowired
    private DahuaDeviceService dahuaDeviceService;

    @Override
    public String getToolName() {
        return "dahua_smart_config";
    }

    @Override
    public String getDescription() {
        return "配置大华摄像头智能分析功能（越界检测、区域入侵、人脸检测）。" +
                "支持查询当前配置状态或修改配置。" +
                "适用场景：配置行为检测、设置警戒线、启用/禁用智能分析功能。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // deviceId: 设备ID（必需）
        Map<String, Object> deviceId = new HashMap<>();
        deviceId.put("type", "string");
        deviceId.put("description", "大华摄像头设备ID");
        properties.put("deviceId", deviceId);

        // channelId: 通道ID（可选，默认0）
        Map<String, Object> channelId = new HashMap<>();
        channelId.put("type", "integer");
        channelId.put("description", "摄像头通道ID（从0开始），默认0");
        channelId.put("default", 0);
        channelId.put("minimum", 0);
        properties.put("channelId", channelId);

        // action: 操作类型
        Map<String, Object> action = new HashMap<>();
        action.put("type", "string");
        action.put("description", "操作类型：query=查询配置，enable=启用，disable=禁用");
        action.put("enum", Arrays.asList("query", "enable", "disable"));
        action.put("default", "query");
        properties.put("action", action);

        // feature: 智能功能类型（必需）
        Map<String, Object> feature = new HashMap<>();
        feature.put("type", "string");
        feature.put("description", "智能分析功能：line=越界检测，intrusion=区域入侵，face=人脸检测");
        feature.put("enum", Arrays.asList("line", "intrusion", "face"));
        properties.put("feature", feature);

        // sensitivity: 灵敏度（可选）
        Map<String, Object> sensitivity = new HashMap<>();
        sensitivity.put("type", "integer");
        sensitivity.put("description", "检测灵敏度，1-100，默认50");
        sensitivity.put("minimum", 1);
        sensitivity.put("maximum", 100);
        sensitivity.put("default", 50);
        properties.put("sensitivity", sensitivity);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("deviceId", "feature"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("deviceId", "feature");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String deviceId = getString(params, "deviceId", null);
        Integer channelId = getInteger(params, "channelId", 0);
        String action = getString(params, "action", "query");
        String feature = getString(params, "feature", null);
        Integer sensitivity = getInteger(params, "sensitivity", 50);

        log.info("执行大华智能分析配置 - 工厂ID: {}, 设备ID: {}, 通道: {}, 操作: {}, 功能: {}",
                factoryId, deviceId, channelId, action, feature);

        Map<String, Object> result = new HashMap<>();

        switch (feature.toLowerCase()) {
            case "line":
                result = handleLineDetection(deviceId, channelId, action, sensitivity);
                break;
            case "intrusion":
                result = handleIntrusionDetection(deviceId, channelId, action, sensitivity);
                break;
            case "face":
                result = handleFaceDetection(deviceId, channelId, action);
                break;
            default:
                throw new IllegalArgumentException("不支持的智能分析功能: " + feature);
        }

        return result;
    }

    /**
     * 处理越界检测配置
     */
    private Map<String, Object> handleLineDetection(String deviceId, int channelId, String action, int sensitivity) throws Exception {
        Map<String, Object> result = new HashMap<>();

        if ("query".equals(action)) {
            SmartAnalysisDTO config = dahuaDeviceService.getLineDetectionConfig(deviceId, channelId);
            if (config != null) {
                result.put("enabled", config.getEnabled());
                result.put("channelId", config.getChannelId());
                result.put("rules", config.getRules());
                result.put("message", "越界检测配置查询成功");
            } else {
                result.put("enabled", false);
                result.put("message", "该设备暂无越界检测配置");
            }
        } else {
            boolean enabled = "enable".equals(action);

            SmartAnalysisDTO config = SmartAnalysisDTO.builder()
                    .channelId(channelId)
                    .enabled(enabled)
                    .detectionType(SmartAnalysisDTO.DetectionType.LINE_DETECTION)
                    .build();

            if (enabled) {
                SmartAnalysisDTO.DetectionRule rule = SmartAnalysisDTO.DetectionRule.builder()
                        .id(1)
                        .name("默认越界规则")
                        .enabled(true)
                        .sensitivityLevel(sensitivity)
                        .detectionTarget("all")
                        .direction("both")
                        .build();
                config.setRules(Arrays.asList(rule));
            }

            dahuaDeviceService.setLineDetectionConfig(deviceId, channelId, config);
            result.put("success", true);
            result.put("enabled", enabled);
            result.put("sensitivity", sensitivity);
            result.put("message", enabled ? "越界检测已启用" : "越界检测已禁用");
        }

        result.put("feature", "line");
        result.put("featureName", "越界检测");
        return result;
    }

    /**
     * 处理区域入侵检测配置
     */
    private Map<String, Object> handleIntrusionDetection(String deviceId, int channelId, String action, int sensitivity) throws Exception {
        Map<String, Object> result = new HashMap<>();

        if ("query".equals(action)) {
            SmartAnalysisDTO config = dahuaDeviceService.getFieldDetectionConfig(deviceId, channelId);
            if (config != null) {
                result.put("enabled", config.getEnabled());
                result.put("channelId", config.getChannelId());
                result.put("rules", config.getRules());
                result.put("message", "区域入侵检测配置查询成功");
            } else {
                result.put("enabled", false);
                result.put("message", "该设备暂无区域入侵检测配置");
            }
        } else {
            boolean enabled = "enable".equals(action);

            SmartAnalysisDTO config = SmartAnalysisDTO.builder()
                    .channelId(channelId)
                    .enabled(enabled)
                    .detectionType(SmartAnalysisDTO.DetectionType.FIELD_DETECTION)
                    .build();

            if (enabled) {
                SmartAnalysisDTO.DetectionRule rule = SmartAnalysisDTO.DetectionRule.builder()
                        .id(1)
                        .name("默认入侵检测区域")
                        .enabled(true)
                        .sensitivityLevel(sensitivity)
                        .detectionTarget("all")
                        .timeThreshold(5) // 默认停留5秒触发
                        .build();
                config.setRules(Arrays.asList(rule));
            }

            dahuaDeviceService.setFieldDetectionConfig(deviceId, channelId, config);
            result.put("success", true);
            result.put("enabled", enabled);
            result.put("sensitivity", sensitivity);
            result.put("message", enabled ? "区域入侵检测已启用" : "区域入侵检测已禁用");
        }

        result.put("feature", "intrusion");
        result.put("featureName", "区域入侵检测");
        return result;
    }

    /**
     * 处理人脸检测配置
     */
    private Map<String, Object> handleFaceDetection(String deviceId, int channelId, String action) throws Exception {
        Map<String, Object> result = new HashMap<>();

        if ("query".equals(action)) {
            SmartAnalysisDTO config = dahuaDeviceService.getFaceDetectionConfig(deviceId, channelId);
            if (config != null) {
                result.put("enabled", config.getEnabled());
                result.put("channelId", config.getChannelId());
                result.put("message", "人脸检测配置查询成功");
            } else {
                result.put("enabled", false);
                result.put("message", "该设备暂无人脸检测配置或不支持人脸检测");
            }
        } else {
            boolean enabled = "enable".equals(action);

            SmartAnalysisDTO config = SmartAnalysisDTO.builder()
                    .channelId(channelId)
                    .enabled(enabled)
                    .detectionType(SmartAnalysisDTO.DetectionType.FACE_DETECTION)
                    .build();

            dahuaDeviceService.setFaceDetectionConfig(deviceId, channelId, config);
            result.put("success", true);
            result.put("enabled", enabled);
            result.put("message", enabled ? "人脸检测已启用" : "人脸检测已禁用");
        }

        result.put("feature", "face");
        result.put("featureName", "人脸检测");
        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "deviceId", "请问要配置哪个大华摄像头的智能分析功能？请提供设备ID",
                "channelId", "请问要配置哪个通道？（从0开始，默认为通道0）",
                "action", "请问是要查询配置、启用还是禁用该功能？",
                "feature", "请问要配置哪种智能分析功能？（line=越界检测，intrusion=区域入侵，face=人脸检测）",
                "sensitivity", "请问检测灵敏度设置为多少？（1-100，默认50）"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
                "deviceId", "设备ID",
                "channelId", "通道ID",
                "action", "操作类型",
                "feature", "智能功能",
                "sensitivity", "灵敏度"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
