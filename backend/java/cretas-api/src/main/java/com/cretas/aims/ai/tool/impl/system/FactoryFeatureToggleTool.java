package com.cretas.aims.ai.tool.impl.system;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.FactorySettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 工厂功能开关工具
 *
 * 用于启用或禁用工厂的特定功能模块。可以控制如追溯、
 * 质检、预警等功能的开启状态。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class FactoryFeatureToggleTool extends AbstractBusinessTool {

    @Autowired
    private FactorySettingsService factorySettingsService;

    @Override
    public String getToolName() {
        return "factory_feature_toggle";
    }

    @Override
    public String getDescription() {
        return "启用或禁用工厂的特定功能模块。需要提供功能代码和启用/禁用状态。" +
                "可控制的功能包括：追溯功能、质检模块、预警系统、报表功能等。" +
                "适用场景：按需开启功能、临时禁用某功能、功能配置管理。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // featureCode: 功能代码（必需）
        Map<String, Object> featureCode = new HashMap<>();
        featureCode.put("type", "string");
        featureCode.put("description", "功能代码，唯一标识要操作的功能");
        featureCode.put("enum", Arrays.asList(
                "TRACEABILITY",         // 追溯功能
                "QUALITY_CHECK",        // 质检模块
                "ALERT_SYSTEM",         // 预警系统
                "REPORT_MODULE",        // 报表功能
                "AI_ASSISTANT",         // AI助手
                "AUTO_SCHEDULING",      // 自动调度
                "INVENTORY_ALERT",      // 库存预警
                "EXPIRY_ALERT",         // 保质期预警
                "SCALE_INTEGRATION",    // 电子秤集成
                "CAMERA_INTEGRATION"    // 摄像头集成
        ));
        properties.put("featureCode", featureCode);

        // enabled: 是否启用（必需）
        Map<String, Object> enabled = new HashMap<>();
        enabled.put("type", "boolean");
        enabled.put("description", "true启用，false禁用");
        properties.put("enabled", enabled);

        // reason: 操作原因（可选）
        Map<String, Object> reason = new HashMap<>();
        reason.put("type", "string");
        reason.put("description", "操作原因或备注");
        properties.put("reason", reason);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("featureCode", "enabled"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("featureCode", "enabled");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("featureCode".equals(paramName)) {
            return "请问您要操作哪个功能？可选：TRACEABILITY(追溯)、QUALITY_CHECK(质检)、ALERT_SYSTEM(预警)、REPORT_MODULE(报表)、AI_ASSISTANT(AI助手)等。";
        }
        if ("enabled".equals(paramName)) {
            return "请问要启用还是禁用该功能？";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        if ("featureCode".equals(paramName)) {
            return "功能代码";
        }
        if ("enabled".equals(paramName)) {
            return "启用/禁用";
        }
        if ("reason".equals(paramName)) {
            return "原因";
        }
        return super.getParameterDisplayName(paramName);
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String featureCode = getString(params, "featureCode");
        Boolean enabled = getBoolean(params, "enabled");
        String reason = getString(params, "reason", null);

        try {
            log.info("切换功能开关 - 工厂ID: {}, 功能: {}, 启用: {}", factoryId, featureCode, enabled);
            factorySettingsService.updateFeatureToggle(factoryId, featureCode, enabled);

            Map<String, ?> featureInfo = getFeatureInfo(featureCode);
            Map<String, Object> result = new HashMap<>();
            result.put("factoryId", factoryId);
            result.put("featureCode", featureCode);
            result.put("featureName", featureInfo.get("name"));
            result.put("enabled", enabled);
            if (reason != null) {
                result.put("reason", reason);
            }
            String action = enabled ? "启用" : "禁用";
            return buildSimpleResult(featureInfo.get("name") + "已" + action, result);
        } catch (Exception e) {
            log.error("功能开关切换失败 - 工厂ID: {}, 功能: {}", factoryId, featureCode, e);
            throw e;
        }
    }

    /**
     * 验证功能代码是否有效
     */
    private boolean isValidFeatureCode(String featureCode) {
        Set<String> validCodes = new HashSet<>(Arrays.asList(
                "TRACEABILITY", "QUALITY_CHECK", "ALERT_SYSTEM", "REPORT_MODULE",
                "AI_ASSISTANT", "AUTO_SCHEDULING", "INVENTORY_ALERT", "EXPIRY_ALERT",
                "SCALE_INTEGRATION", "CAMERA_INTEGRATION"
        ));
        return validCodes.contains(featureCode);
    }

    /**
     * 获取功能基本信息
     */
    private Map<String, String> getFeatureInfo(String featureCode) {
        Map<String, Map<String, String>> features = new HashMap<>();

        features.put("TRACEABILITY", createFeatureMap("追溯功能", "产品全链路追溯，从原料到成品的完整追踪"));
        features.put("QUALITY_CHECK", createFeatureMap("质检模块", "质量检验管理，包括来料检、过程检、出厂检"));
        features.put("ALERT_SYSTEM", createFeatureMap("预警系统", "异常告警和通知，包括设备告警、质量告警等"));
        features.put("REPORT_MODULE", createFeatureMap("报表功能", "数据报表和分析，支持多维度统计分析"));
        features.put("AI_ASSISTANT", createFeatureMap("AI助手", "智能语音助手，支持自然语言交互"));
        features.put("AUTO_SCHEDULING", createFeatureMap("自动调度", "智能生产调度，自动优化排程"));
        features.put("INVENTORY_ALERT", createFeatureMap("库存预警", "库存量预警，低库存和高库存提醒"));
        features.put("EXPIRY_ALERT", createFeatureMap("保质期预警", "原料和产品保质期预警"));
        features.put("SCALE_INTEGRATION", createFeatureMap("电子秤集成", "电子秤设备连接和数据采集"));
        features.put("CAMERA_INTEGRATION", createFeatureMap("摄像头集成", "摄像头设备连接和视频监控"));

        return features.getOrDefault(featureCode, createFeatureMap("未知功能", ""));
    }

    private Map<String, String> createFeatureMap(String name, String description) {
        Map<String, String> map = new HashMap<>();
        map.put("name", name);
        map.put("description", description);
        return map;
    }

    /**
     * 获取功能启用后的能力
     */
    private List<String> getFeatureCapabilities(String featureCode) {
        Map<String, List<String>> capabilities = new HashMap<>();

        capabilities.put("TRACEABILITY", Arrays.asList(
                "扫码追溯产品来源",
                "查看完整生产流程",
                "追踪原料批次信息"
        ));
        capabilities.put("QUALITY_CHECK", Arrays.asList(
                "创建质检任务",
                "记录检验结果",
                "生成质检报告"
        ));
        capabilities.put("ALERT_SYSTEM", Arrays.asList(
                "接收实时告警",
                "查看告警历史",
                "配置告警规则"
        ));
        capabilities.put("AI_ASSISTANT", Arrays.asList(
                "语音交互操作",
                "智能数据查询",
                "自然语言指令"
        ));

        return capabilities.getOrDefault(featureCode, Arrays.asList("功能相关操作"));
    }

    /**
     * 获取功能禁用后的影响
     */
    private List<String> getFeatureDisableImpact(String featureCode) {
        Map<String, List<String>> impacts = new HashMap<>();

        impacts.put("TRACEABILITY", Arrays.asList(
                "无法进行产品追溯",
                "扫码功能不可用"
        ));
        impacts.put("QUALITY_CHECK", Arrays.asList(
                "无法创建质检任务",
                "质检记录功能暂停"
        ));
        impacts.put("ALERT_SYSTEM", Arrays.asList(
                "不再接收告警通知",
                "异常情况需人工监控"
        ));
        impacts.put("AI_ASSISTANT", Arrays.asList(
                "语音助手不可用",
                "需使用传统界面操作"
        ));

        return impacts.getOrDefault(featureCode, Arrays.asList("相关功能暂停"));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
