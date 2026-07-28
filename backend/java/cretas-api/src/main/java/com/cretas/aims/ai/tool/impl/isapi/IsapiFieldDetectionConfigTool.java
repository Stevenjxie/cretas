package com.cretas.aims.ai.tool.impl.isapi;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.isapi.SmartAnalysisDTO;
import com.cretas.aims.service.isapi.IsapiSmartAnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * ISAPI 区域入侵检测配置工具
 *
 * 配置海康威视摄像头的区域入侵检测功能。
 * 支持查询当前配置和设置新配置。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-23
 */
@Slf4j
@Component
public class IsapiFieldDetectionConfigTool extends AbstractBusinessTool {

    @Autowired
    private IsapiSmartAnalysisService isapiSmartAnalysisService;

    @Override
    public String getToolName() {
        return "isapi_field_detection_config";
    }

    @Override
    public String getDescription() {
        return "配置摄像头区域入侵检测。支持查询当前配置或设置新配置。" +
                "适用场景：配置入侵检测、设置禁区、启用/禁用区域布防。";
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

        // channelId: 通道ID（可选，默认1）
        Map<String, Object> channelId = new HashMap<>();
        channelId.put("type", "integer");
        channelId.put("description", "摄像头通道ID，默认1");
        channelId.put("default", 1);
        properties.put("channelId", channelId);

        // action: 操作类型（可选，默认query）
        Map<String, Object> action = new HashMap<>();
        action.put("type", "string");
        action.put("description", "操作类型：query=查询配置，enable=启用，disable=禁用");
        action.put("enum", Arrays.asList("query", "enable", "disable"));
        action.put("default", "query");
        properties.put("action", action);

        // sensitivity: 灵敏度（可选）
        Map<String, Object> sensitivity = new HashMap<>();
        sensitivity.put("type", "integer");
        sensitivity.put("description", "检测灵敏度，1-100");
        sensitivity.put("minimum", 1);
        sensitivity.put("maximum", 100);
        properties.put("sensitivity", sensitivity);

        // threshold: 触发阈值（可选）
        Map<String, Object> threshold = new HashMap<>();
        threshold.put("type", "integer");
        threshold.put("description", "触发阈值（秒），在区域内停留多长时间触发告警");
        threshold.put("minimum", 1);
        threshold.put("maximum", 60);
        properties.put("threshold", threshold);

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
        Integer channelId = getInteger(params, "channelId", 1);
        String action = getString(params, "action", "query");

        log.info("执行ISAPI区域入侵检测配置 - 工厂ID: {}, 设备ID: {}, 通道: {}, 操作: {}",
                factoryId, deviceId, channelId, action);

        Map<String, Object> result = new HashMap<>();

        if ("query".equals(action)) {
            // 查询当前配置
            SmartAnalysisDTO config = isapiSmartAnalysisService.getFieldDetectionConfig(factoryId, deviceId, channelId);
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
            // 设置配置
            boolean enabled = "enable".equals(action);
            Integer sensitivity = getInteger(params, "sensitivity", 50);
            Integer threshold = getInteger(params, "threshold", 5);

            SmartAnalysisDTO config = SmartAnalysisDTO.builder()
                    .channelId(channelId)
                    .enabled(enabled)
                    .detectionType(SmartAnalysisDTO.DetectionType.FIELD_DETECTION)
                    .build();

            // 如果启用，添加默认规则
            if (enabled) {
                SmartAnalysisDTO.DetectionRule rule = SmartAnalysisDTO.DetectionRule.builder()
                        .id(1)
                        .name("默认入侵检测区域")
                        .enabled(true)
                        .sensitivityLevel(sensitivity)
                        .detectionTarget("all")
                        .timeThreshold(threshold)
                        .build();
                config.setRules(Arrays.asList(rule));
            }

            isapiSmartAnalysisService.saveFieldDetectionConfig(factoryId, deviceId, channelId, config);
            result.put("success", true);
            result.put("enabled", enabled);
            result.put("message", enabled ? "区域入侵检测已启用" : "区域入侵检测已禁用");
        }

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "deviceId", "请问要配置哪个摄像头的入侵检测？请提供设备ID",
            "channelId", "请问要配置哪个通道？默认为通道1",
            "action", "请问是要查询配置、启用还是禁用入侵检测？",
            "sensitivity", "请问检测灵敏度设置为多少？（1-100）",
            "threshold", "请问触发阈值设置为多少秒？（在区域内停留多长时间触发告警）"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "deviceId", "设备ID",
            "channelId", "通道ID",
            "action", "操作类型",
            "sensitivity", "灵敏度",
            "threshold", "触发阈值"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
