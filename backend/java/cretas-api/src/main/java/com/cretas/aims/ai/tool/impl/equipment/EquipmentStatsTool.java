package com.cretas.aims.ai.tool.impl.equipment;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.EquipmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 设备统计工具
 *
 * 获取工厂设备的总体统计数据，包括各状态设备数量、设备类型分布等。
 *
 * Intent Code: EQUIPMENT_STATS
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-08
 */
@Slf4j
@Component
public class EquipmentStatsTool extends AbstractBusinessTool {

    @Autowired
    private EquipmentService equipmentService;

    @Override
    public String getToolName() {
        return "equipment_stats";
    }

    @Override
    public String getDescription() {
        return "获取设备统计信息。返回工厂设备的总体统计数据，包括总设备数、各状态设备数量、设备类型分布等。" +
                "适用场景：了解设备整体情况、生成设备报表、监控设备运行状态分布。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        // 无必需参数，统计整个工厂的设备

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行设备统计查询 - 工厂ID: {}", factoryId);

        // 调用服务获取统计数据
        Map<String, Object> statistics = equipmentService.getOverallEquipmentStatistics(factoryId);

        // 构建结果
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("statistics", statistics);

        // 添加健康状态评估
        result.put("healthAssessment", assessEquipmentHealth(statistics));

        // 添加建议
        result.put("recommendations", generateRecommendations(statistics));

        log.info("设备统计查询完成 - 工厂ID: {}", factoryId);

        return result;
    }

    /**
     * 评估设备健康状态
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> assessEquipmentHealth(Map<String, Object> statistics) {
        Map<String, Object> assessment = new HashMap<>();

        int totalEquipment = 0;
        int activeCount = 0;
        int maintenanceCount = 0;
        int offlineCount = 0;

        if (statistics != null) {
            // 尝试从统计数据中提取关键指标
            Object total = statistics.get("totalEquipment");
            if (total instanceof Number) {
                totalEquipment = ((Number) total).intValue();
            }

            Object byStatus = statistics.get("byStatus");
            if (byStatus instanceof Map) {
                Map<String, Object> statusMap = (Map<String, Object>) byStatus;

                Object active = statusMap.get("ACTIVE");
                if (active instanceof Number) {
                    activeCount = ((Number) active).intValue();
                }

                Object maintenance = statusMap.get("MAINTENANCE");
                if (maintenance instanceof Number) {
                    maintenanceCount = ((Number) maintenance).intValue();
                }

                Object offline = statusMap.get("OFFLINE");
                if (offline instanceof Number) {
                    offlineCount = ((Number) offline).intValue();
                }
            }
        }

        // 计算可用率
        double availabilityRate = totalEquipment > 0 ? (double) activeCount / totalEquipment * 100 : 0;

        // 评估健康等级
        String healthLevel;
        String healthDescription;

        if (offlineCount > totalEquipment * 0.2) {
            healthLevel = "CRITICAL";
            healthDescription = "离线设备过多，需要立即检查";
        } else if (maintenanceCount > totalEquipment * 0.3) {
            healthLevel = "WARNING";
            healthDescription = "维护中设备较多，建议优化维护计划";
        } else if (availabilityRate >= 80) {
            healthLevel = "HEALTHY";
            healthDescription = "设备运行正常，可用率良好";
        } else if (availabilityRate >= 60) {
            healthLevel = "FAIR";
            healthDescription = "设备可用率一般，建议关注";
        } else {
            healthLevel = "WARNING";
            healthDescription = "设备可用率偏低，需要改善";
        }

        assessment.put("level", healthLevel);
        assessment.put("description", healthDescription);
        assessment.put("totalEquipment", totalEquipment);
        assessment.put("activeCount", activeCount);
        assessment.put("maintenanceCount", maintenanceCount);
        assessment.put("offlineCount", offlineCount);
        assessment.put("availabilityRate", String.format("%.1f%%", availabilityRate));

        return assessment;
    }

    /**
     * 生成设备管理建议
     */
    @SuppressWarnings("unchecked")
    private List<String> generateRecommendations(Map<String, Object> statistics) {
        List<String> recommendations = new ArrayList<>();

        if (statistics == null) {
            recommendations.add("建议检查设备管理系统是否正常运行");
            return recommendations;
        }

        int totalEquipment = 0;
        int maintenanceCount = 0;
        int offlineCount = 0;
        int inactiveCount = 0;

        Object total = statistics.get("totalEquipment");
        if (total instanceof Number) {
            totalEquipment = ((Number) total).intValue();
        }

        Object byStatus = statistics.get("byStatus");
        if (byStatus instanceof Map) {
            Map<String, Object> statusMap = (Map<String, Object>) byStatus;

            Object maintenance = statusMap.get("MAINTENANCE");
            if (maintenance instanceof Number) {
                maintenanceCount = ((Number) maintenance).intValue();
            }

            Object offline = statusMap.get("OFFLINE");
            if (offline instanceof Number) {
                offlineCount = ((Number) offline).intValue();
            }

            Object inactive = statusMap.get("INACTIVE");
            if (inactive instanceof Number) {
                inactiveCount = ((Number) inactive).intValue();
            }
        }

        // 根据统计数据生成建议
        if (offlineCount > 0) {
            recommendations.add("存在 " + offlineCount + " 台离线设备，建议检查设备连接状态");
        }

        if (maintenanceCount > totalEquipment * 0.2) {
            recommendations.add("维护中设备较多，建议优化维护排程，避免影响生产");
        }

        if (inactiveCount > totalEquipment * 0.3) {
            recommendations.add("停止状态设备较多，建议评估设备利用率");
        }

        // 检查需要维护的设备
        Object needMaintenance = statistics.get("needMaintenance");
        if (needMaintenance instanceof Number && ((Number) needMaintenance).intValue() > 0) {
            recommendations.add("有 " + ((Number) needMaintenance).intValue() + " 台设备即将需要维护，请提前安排");
        }

        if (recommendations.isEmpty()) {
            recommendations.add("设备状态良好，请继续保持日常维护");
        }

        return recommendations;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        // 无必需参数，无需问题
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        // 无必需参数，无需显示名称
        return super.getParameterDisplayName(paramName);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
