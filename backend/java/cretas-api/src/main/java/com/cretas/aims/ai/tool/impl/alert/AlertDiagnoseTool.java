package com.cretas.aims.ai.tool.impl.alert;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.equipment.EquipmentAlertDTO;
import com.cretas.aims.service.EquipmentAlertsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 告警诊断工具
 *
 * 对指定告警进行诊断分析，提供可能的原因和建议的解决方案。
 * 用于帮助用户理解告警并找到解决方法。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class AlertDiagnoseTool extends AbstractBusinessTool {

    @Autowired
    private EquipmentAlertsService equipmentAlertsService;

    @Override
    public String getToolName() {
        return "alert_diagnose";
    }

    @Override
    public String getDescription() {
        return "诊断分析指定的告警。需要提供告警ID。返回告警的详细信息、可能的原因分析和建议的解决方案。" +
                "适用场景：分析告警原因、获取解决建议、了解告警详情。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // alertId: 告警ID（必需）
        Map<String, Object> alertId = new HashMap<>();
        alertId.put("type", "integer");
        alertId.put("description", "告警ID，要诊断的告警的唯一标识");
        properties.put("alertId", alertId);

        // includeHistory: 是否包含历史（可选）
        Map<String, Object> includeHistory = new HashMap<>();
        includeHistory.put("type", "boolean");
        includeHistory.put("description", "是否包含该设备的历史告警信息");
        includeHistory.put("default", false);
        properties.put("includeHistory", includeHistory);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("alertId"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("alertId");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("alertId".equals(paramName)) {
            return "请问您要诊断哪个告警？请提供告警ID。";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        if ("alertId".equals(paramName)) {
            return "告警ID";
        }
        if ("includeHistory".equals(paramName)) {
            return "包含历史记录";
        }
        return super.getParameterDisplayName(paramName);
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行告警诊断 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 解析参数
        Integer alertId = getInteger(params, "alertId");
        Boolean includeHistory = getBoolean(params, "includeHistory", false);

        // 获取告警详情
        EquipmentAlertDTO alert = equipmentAlertsService.getAlertById(factoryId, alertId);

        if (alert == null) {
            throw new IllegalArgumentException("告警不存在: " + alertId);
        }

        // 构建诊断结果
        Map<String, Object> result = new HashMap<>();
        result.put("alertId", alertId);
        result.put("alertDetails", buildAlertDetails(alert));
        result.put("diagnosis", performDiagnosis(alert));
        result.put("possibleCauses", analyzePossibleCauses(alert));
        result.put("suggestedActions", suggestActions(alert));
        result.put("priority", assessPriority(alert));

        // 如果需要历史记录
        if (includeHistory && alert.getEquipmentId() != null) {
            result.put("historyNote", "历史告警信息需要单独查询，请使用alert_by_equipment工具");
        }

        log.info("告警诊断完成 - 告警ID: {}", alertId);

        return result;
    }

    /**
     * 构建告警详情
     */
    private Map<String, Object> buildAlertDetails(EquipmentAlertDTO alert) {
        Map<String, Object> details = new HashMap<>();
        details.put("id", alert.getId());
        details.put("equipmentId", alert.getEquipmentId());
        details.put("equipmentName", alert.getEquipmentName());
        details.put("alertType", alert.getAlertType());
        details.put("level", alert.getLevel() != null ? alert.getLevel().name() : null);
        details.put("status", alert.getStatus() != null ? alert.getStatus().name() : null);
        details.put("message", alert.getMessage());
        details.put("details", alert.getDetails());
        details.put("triggeredAt", alert.getTriggeredAt() != null ? alert.getTriggeredAt().toString() : null);

        // 计算告警持续时间
        if (alert.getTriggeredAt() != null) {
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            long durationMinutes = java.time.Duration.between(alert.getTriggeredAt(), now).toMinutes();
            details.put("durationMinutes", durationMinutes);

            if (durationMinutes < 60) {
                details.put("durationDisplay", durationMinutes + " 分钟");
            } else if (durationMinutes < 1440) {
                details.put("durationDisplay", (durationMinutes / 60) + " 小时");
            } else {
                details.put("durationDisplay", (durationMinutes / 1440) + " 天");
            }
        }

        return details;
    }

    /**
     * 执行诊断分析
     */
    private Map<String, Object> performDiagnosis(EquipmentAlertDTO alert) {
        Map<String, Object> diagnosis = new HashMap<>();

        String alertType = alert.getAlertType();
        String message = alert.getMessage();

        // 基于告警类型进行诊断
        if (alertType != null) {
            switch (alertType.toLowerCase()) {
                case "maintenance":
                case "维护提醒":
                    diagnosis.put("category", "设备维护");
                    diagnosis.put("summary", "设备需要进行定期维护");
                    diagnosis.put("urgency", alert.getLevel() != null && alert.getLevel().name().equals("CRITICAL") ? "高" : "中");
                    break;
                case "warranty":
                case "保修即将到期":
                    diagnosis.put("category", "保修管理");
                    diagnosis.put("summary", "设备保修期即将到期或已过期");
                    diagnosis.put("urgency", "中");
                    break;
                case "temperature":
                case "温度异常":
                    diagnosis.put("category", "环境监控");
                    diagnosis.put("summary", "设备温度超出正常范围");
                    diagnosis.put("urgency", "高");
                    break;
                case "performance":
                case "性能下降":
                    diagnosis.put("category", "性能问题");
                    diagnosis.put("summary", "设备性能指标低于预期");
                    diagnosis.put("urgency", "中");
                    break;
                default:
                    diagnosis.put("category", "其他");
                    diagnosis.put("summary", "需要人工分析判断");
                    diagnosis.put("urgency", "待评估");
            }
        } else {
            diagnosis.put("category", "未分类");
            diagnosis.put("summary", "告警类型未知，需要人工分析");
            diagnosis.put("urgency", "待评估");
        }

        return diagnosis;
    }

    /**
     * 分析可能原因
     */
    private List<String> analyzePossibleCauses(EquipmentAlertDTO alert) {
        List<String> causes = new ArrayList<>();

        String alertType = alert.getAlertType();

        if (alertType != null) {
            switch (alertType.toLowerCase()) {
                case "maintenance":
                case "维护提醒":
                    causes.add("设备已达到维护周期");
                    causes.add("运行时间累计超过阈值");
                    causes.add("部件磨损需要更换");
                    break;
                case "warranty":
                case "保修即将到期":
                    causes.add("设备购买时间较长");
                    causes.add("保修期限即将结束");
                    break;
                case "temperature":
                case "温度异常":
                    causes.add("冷却系统故障");
                    causes.add("环境温度过高");
                    causes.add("负载过重导致发热");
                    causes.add("散热通风不良");
                    break;
                case "performance":
                case "性能下降":
                    causes.add("设备老化");
                    causes.add("需要清洁维护");
                    causes.add("软件/固件需要更新");
                    causes.add("配置参数不当");
                    break;
                default:
                    causes.add("需要现场检查确认");
                    causes.add("查看设备日志获取更多信息");
            }
        } else {
            causes.add("需要查看告警详情确定原因");
        }

        return causes;
    }

    /**
     * 建议操作
     */
    private List<Map<String, Object>> suggestActions(EquipmentAlertDTO alert) {
        List<Map<String, Object>> actions = new ArrayList<>();

        String alertType = alert.getAlertType();

        if (alertType != null) {
            switch (alertType.toLowerCase()) {
                case "maintenance":
                case "维护提醒":
                    actions.add(createAction("安排维护计划", "高", "联系维护人员安排设备维护"));
                    actions.add(createAction("检查维护手册", "中", "查阅设备维护手册了解维护项目"));
                    actions.add(createAction("准备备件", "中", "确认所需备件是否齐全"));
                    break;
                case "warranty":
                case "保修即将到期":
                    actions.add(createAction("联系供应商", "高", "咨询延保方案或续保事宜"));
                    actions.add(createAction("设备检查", "中", "在保修期内进行全面检查"));
                    actions.add(createAction("评估替换需求", "低", "评估是否需要更新设备"));
                    break;
                case "temperature":
                case "温度异常":
                    actions.add(createAction("立即降温", "紧急", "启动应急降温措施"));
                    actions.add(createAction("检查冷却系统", "高", "检查风扇、空调等冷却设备"));
                    actions.add(createAction("减少负载", "中", "适当减少设备负载"));
                    break;
                default:
                    actions.add(createAction("现场检查", "高", "派人到现场检查实际情况"));
                    actions.add(createAction("确认告警", "中", "使用alert_acknowledge确认已知晓"));
            }
        } else {
            actions.add(createAction("查看详情", "高", "获取更多告警信息"));
        }

        return actions;
    }

    /**
     * 创建操作建议
     */
    private Map<String, Object> createAction(String action, String priority, String description) {
        Map<String, Object> actionMap = new HashMap<>();
        actionMap.put("action", action);
        actionMap.put("priority", priority);
        actionMap.put("description", description);
        return actionMap;
    }

    /**
     * 评估处理优先级
     */
    private Map<String, Object> assessPriority(EquipmentAlertDTO alert) {
        Map<String, Object> priority = new HashMap<>();

        int score = 0;

        // 基于级别评分
        if (alert.getLevel() != null) {
            switch (alert.getLevel()) {
                case CRITICAL:
                    score += 30;
                    break;
                case WARNING:
                    score += 20;
                    break;
                case INFO:
                    score += 10;
                    break;
            }
        }

        // 基于持续时间评分
        if (alert.getTriggeredAt() != null) {
            long hoursAgo = java.time.Duration.between(alert.getTriggeredAt(), java.time.LocalDateTime.now()).toHours();
            if (hoursAgo > 24) {
                score += 20;
            } else if (hoursAgo > 8) {
                score += 10;
            } else if (hoursAgo > 2) {
                score += 5;
            }
        }

        // 基于状态评分
        if (alert.getStatus() != null && "ACTIVE".equals(alert.getStatus().name())) {
            score += 10;
        }

        // 确定优先级
        String priorityLevel;
        String recommendation;

        if (score >= 50) {
            priorityLevel = "紧急";
            recommendation = "需要立即处理，可能影响生产或安全";
        } else if (score >= 35) {
            priorityLevel = "高";
            recommendation = "建议尽快处理，避免问题升级";
        } else if (score >= 20) {
            priorityLevel = "中";
            recommendation = "需要关注，安排时间处理";
        } else {
            priorityLevel = "低";
            recommendation = "可以根据情况选择处理时机";
        }

        priority.put("level", priorityLevel);
        priority.put("score", score);
        priority.put("recommendation", recommendation);

        return priority;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
