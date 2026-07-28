package com.cretas.aims.ai.tool.impl.alert;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.equipment.EquipmentAlertDTO;
import com.cretas.aims.service.EquipmentAlertsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 告警分诊工具
 *
 * 对当前活动告警进行分诊排序，帮助确定处理优先级。
 * 综合考虑告警级别、持续时间、设备重要性等因素。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class AlertTriageTool extends AbstractBusinessTool {

    @Autowired
    private EquipmentAlertsService equipmentAlertsService;

    @Override
    public String getToolName() {
        return "alert_triage";
    }

    @Override
    public String getDescription() {
        return "对告警进行分诊排序。分析当前所有活动告警，综合告警级别、持续时间等因素，" +
                "返回按优先级排序的处理建议。适用场景：确定处理顺序、资源分配决策、批量告警管理。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // alertId: 单个告警分诊（可选）
        Map<String, Object> alertId = new HashMap<>();
        alertId.put("type", "integer");
        alertId.put("description", "告警ID，如果提供则只分诊该告警，否则分诊所有活动告警");
        properties.put("alertId", alertId);

        // maxResults: 最大返回数量（可选）
        Map<String, Object> maxResults = new HashMap<>();
        maxResults.put("type", "integer");
        maxResults.put("description", "最大返回数量");
        maxResults.put("default", 10);
        maxResults.put("minimum", 1);
        maxResults.put("maximum", 50);
        properties.put("maxResults", maxResults);

        // includeRecommendations: 是否包含处理建议（可选）
        Map<String, Object> includeRecommendations = new HashMap<>();
        includeRecommendations.put("type", "boolean");
        includeRecommendations.put("description", "是否包含每个告警的处理建议");
        includeRecommendations.put("default", true);
        properties.put("includeRecommendations", includeRecommendations);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("alertId".equals(paramName)) {
            return "请问您要分诊特定告警还是所有活动告警？如果是特定告警，请提供告警ID。";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行告警分诊 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 解析参数
        Integer alertId = getInteger(params, "alertId");
        Integer maxResults = getInteger(params, "maxResults", 10);
        Boolean includeRecommendations = getBoolean(params, "includeRecommendations", true);

        Map<String, Object> result = new HashMap<>();

        if (alertId != null) {
            // 单个告警分诊
            EquipmentAlertDTO alert = equipmentAlertsService.getAlertById(factoryId, alertId);
            if (alert == null) {
                throw new IllegalArgumentException("告警不存在: " + alertId);
            }

            Map<String, Object> triageResult = triageSingleAlert(alert, includeRecommendations);
            result.put("mode", "single");
            result.put("triageResult", triageResult);

        } else {
            // 批量分诊所有活动告警
            PageRequest pageRequest = new PageRequest();
            pageRequest.setPage(1);
            pageRequest.setSize(100); // 获取足够多的活动告警

            PageResponse<EquipmentAlertDTO> pageResponse = equipmentAlertsService.getAlertList(
                    factoryId, pageRequest, null, null, "ACTIVE");

            List<EquipmentAlertDTO> activeAlerts = pageResponse.getContent();

            if (activeAlerts == null || activeAlerts.isEmpty()) {
                result.put("mode", "batch");
                result.put("message", "当前没有活动告警需要处理");
                result.put("triageResults", Collections.emptyList());
                result.put("summary", buildEmptySummary());
                return result;
            }

            // 对所有活动告警进行分诊和排序
            List<Map<String, Object>> triageResults = triageAndSort(activeAlerts, includeRecommendations);

            // 限制返回数量
            if (triageResults.size() > maxResults) {
                triageResults = triageResults.subList(0, maxResults);
            }

            result.put("mode", "batch");
            result.put("totalActiveAlerts", activeAlerts.size());
            result.put("returnedCount", triageResults.size());
            result.put("triageResults", triageResults);
            result.put("summary", buildTriageSummary(activeAlerts));
            result.put("processingOrder", buildProcessingOrder(triageResults));
        }

        log.info("告警分诊完成 - 工厂ID: {}", factoryId);

        return result;
    }

    /**
     * 单个告警分诊
     */
    private Map<String, Object> triageSingleAlert(EquipmentAlertDTO alert, boolean includeRecommendations) {
        Map<String, Object> triageResult = new HashMap<>();

        int priorityScore = calculatePriorityScore(alert);
        String priorityLevel = getPriorityLevel(priorityScore);

        triageResult.put("alertId", alert.getId());
        triageResult.put("equipmentName", alert.getEquipmentName());
        triageResult.put("alertType", alert.getAlertType());
        triageResult.put("level", alert.getLevel() != null ? alert.getLevel().name() : null);
        triageResult.put("message", alert.getMessage());
        triageResult.put("triggeredAt", alert.getTriggeredAt() != null ? alert.getTriggeredAt().toString() : null);
        triageResult.put("priorityScore", priorityScore);
        triageResult.put("priorityLevel", priorityLevel);
        triageResult.put("durationInfo", calculateDuration(alert));

        if (includeRecommendations) {
            triageResult.put("recommendations", generateRecommendations(alert, priorityLevel));
        }

        return triageResult;
    }

    /**
     * 批量分诊并排序
     */
    private List<Map<String, Object>> triageAndSort(List<EquipmentAlertDTO> alerts, boolean includeRecommendations) {
        List<Map<String, Object>> results = new ArrayList<>();

        for (EquipmentAlertDTO alert : alerts) {
            Map<String, Object> triageResult = triageSingleAlert(alert, includeRecommendations);
            results.add(triageResult);
        }

        // 按优先级分数降序排序
        results.sort((a, b) -> {
            Integer scoreA = (Integer) a.get("priorityScore");
            Integer scoreB = (Integer) b.get("priorityScore");
            return scoreB.compareTo(scoreA);
        });

        // 添加排序序号
        for (int i = 0; i < results.size(); i++) {
            results.get(i).put("rank", i + 1);
        }

        return results;
    }

    /**
     * 计算优先级分数
     */
    private int calculatePriorityScore(EquipmentAlertDTO alert) {
        int score = 0;

        // 基于告警级别（40%权重）
        if (alert.getLevel() != null) {
            switch (alert.getLevel()) {
                case CRITICAL:
                    score += 40;
                    break;
                case WARNING:
                    score += 25;
                    break;
                case INFO:
                    score += 10;
                    break;
            }
        }

        // 基于持续时间（30%权重）
        if (alert.getTriggeredAt() != null) {
            long hoursAgo = Duration.between(alert.getTriggeredAt(), LocalDateTime.now()).toHours();
            if (hoursAgo >= 48) {
                score += 30;
            } else if (hoursAgo >= 24) {
                score += 25;
            } else if (hoursAgo >= 12) {
                score += 20;
            } else if (hoursAgo >= 4) {
                score += 15;
            } else if (hoursAgo >= 1) {
                score += 10;
            } else {
                score += 5;
            }
        }

        // 基于告警类型（20%权重）
        String alertType = alert.getAlertType();
        if (alertType != null) {
            String typeLower = alertType.toLowerCase();
            if (typeLower.contains("temperature") || typeLower.contains("温度") ||
                typeLower.contains("safety") || typeLower.contains("安全")) {
                score += 20;
            } else if (typeLower.contains("maintenance") || typeLower.contains("维护")) {
                score += 15;
            } else if (typeLower.contains("performance") || typeLower.contains("性能")) {
                score += 12;
            } else {
                score += 10;
            }
        }

        // 基于状态（10%权重）- 未确认的优先级更高
        if (alert.getStatus() != null && "ACTIVE".equals(alert.getStatus().name())) {
            score += 10;
        } else if (alert.getStatus() != null && "ACKNOWLEDGED".equals(alert.getStatus().name())) {
            score += 5;
        }

        return score;
    }

    /**
     * 获取优先级等级
     */
    private String getPriorityLevel(int score) {
        if (score >= 80) {
            return "紧急";
        } else if (score >= 60) {
            return "高";
        } else if (score >= 40) {
            return "中";
        } else {
            return "低";
        }
    }

    /**
     * 计算持续时间信息
     */
    private Map<String, Object> calculateDuration(EquipmentAlertDTO alert) {
        Map<String, Object> durationInfo = new HashMap<>();

        if (alert.getTriggeredAt() != null) {
            Duration duration = Duration.between(alert.getTriggeredAt(), LocalDateTime.now());
            long minutes = duration.toMinutes();

            durationInfo.put("minutes", minutes);

            if (minutes < 60) {
                durationInfo.put("display", minutes + " 分钟");
            } else if (minutes < 1440) {
                long hours = minutes / 60;
                durationInfo.put("display", hours + " 小时");
            } else {
                long days = minutes / 1440;
                long remainingHours = (minutes % 1440) / 60;
                durationInfo.put("display", days + " 天 " + remainingHours + " 小时");
            }

            // 评估持续时间
            if (minutes > 1440) {
                durationInfo.put("assessment", "严重超时");
            } else if (minutes > 480) {
                durationInfo.put("assessment", "超时");
            } else if (minutes > 120) {
                durationInfo.put("assessment", "需要关注");
            } else {
                durationInfo.put("assessment", "正常");
            }
        } else {
            durationInfo.put("display", "未知");
            durationInfo.put("assessment", "未知");
        }

        return durationInfo;
    }

    /**
     * 生成处理建议
     */
    private List<String> generateRecommendations(EquipmentAlertDTO alert, String priorityLevel) {
        List<String> recommendations = new ArrayList<>();

        switch (priorityLevel) {
            case "紧急":
                recommendations.add("立即安排处理，优先分配资源");
                recommendations.add("通知相关负责人");
                recommendations.add("考虑启动应急预案");
                break;
            case "高":
                recommendations.add("尽快安排处理，不要拖延");
                recommendations.add("确认并开始制定解决方案");
                break;
            case "中":
                recommendations.add("安排合适的时间处理");
                recommendations.add("先确认告警，记录处理计划");
                break;
            case "低":
                recommendations.add("可在日常维护时一并处理");
                recommendations.add("持续监控，防止升级");
                break;
        }

        return recommendations;
    }

    /**
     * 构建空摘要
     */
    private Map<String, Object> buildEmptySummary() {
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalActive", 0);
        summary.put("byPriority", new HashMap<>());
        summary.put("healthStatus", "良好");
        summary.put("message", "当前没有活动告警");
        return summary;
    }

    /**
     * 构建分诊摘要
     */
    private Map<String, Object> buildTriageSummary(List<EquipmentAlertDTO> alerts) {
        Map<String, Object> summary = new HashMap<>();

        // 按级别统计
        Map<String, Integer> byLevel = new HashMap<>();
        byLevel.put("CRITICAL", 0);
        byLevel.put("WARNING", 0);
        byLevel.put("INFO", 0);

        for (EquipmentAlertDTO alert : alerts) {
            if (alert.getLevel() != null) {
                String levelKey = alert.getLevel().name();
                byLevel.put(levelKey, byLevel.getOrDefault(levelKey, 0) + 1);
            }
        }

        summary.put("totalActive", alerts.size());
        summary.put("byLevel", byLevel);

        // 计算平均持续时间
        long totalMinutes = 0;
        int validCount = 0;
        for (EquipmentAlertDTO alert : alerts) {
            if (alert.getTriggeredAt() != null) {
                totalMinutes += Duration.between(alert.getTriggeredAt(), LocalDateTime.now()).toMinutes();
                validCount++;
            }
        }
        if (validCount > 0) {
            long avgMinutes = totalMinutes / validCount;
            if (avgMinutes < 60) {
                summary.put("avgDuration", avgMinutes + " 分钟");
            } else {
                summary.put("avgDuration", (avgMinutes / 60) + " 小时");
            }
        }

        // 健康状态评估
        int criticalCount = byLevel.get("CRITICAL");
        if (criticalCount > 0) {
            summary.put("healthStatus", "需要立即关注");
        } else if (alerts.size() > 10) {
            summary.put("healthStatus", "告警较多，建议集中处理");
        } else if (alerts.size() > 5) {
            summary.put("healthStatus", "需要关注");
        } else {
            summary.put("healthStatus", "基本正常");
        }

        return summary;
    }

    /**
     * 构建处理顺序建议
     */
    private List<Map<String, Object>> buildProcessingOrder(List<Map<String, Object>> triageResults) {
        List<Map<String, Object>> order = new ArrayList<>();

        for (int i = 0; i < Math.min(5, triageResults.size()); i++) {
            Map<String, Object> item = triageResults.get(i);
            Map<String, Object> orderItem = new HashMap<>();
            orderItem.put("order", i + 1);
            orderItem.put("alertId", item.get("alertId"));
            orderItem.put("equipmentName", item.get("equipmentName"));
            orderItem.put("priorityLevel", item.get("priorityLevel"));
            orderItem.put("reason", generateOrderReason(item));
            order.add(orderItem);
        }

        return order;
    }

    /**
     * 生成排序原因
     */
    private String generateOrderReason(Map<String, Object> triageResult) {
        String level = (String) triageResult.get("level");
        String priorityLevel = (String) triageResult.get("priorityLevel");

        if ("CRITICAL".equals(level)) {
            return "严重级别告警，需要优先处理";
        } else if ("紧急".equals(priorityLevel)) {
            return "综合评分最高，建议优先处理";
        } else if ("高".equals(priorityLevel)) {
            return "优先级较高，应尽快处理";
        } else {
            return "按优先级排序推荐处理";
        }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
