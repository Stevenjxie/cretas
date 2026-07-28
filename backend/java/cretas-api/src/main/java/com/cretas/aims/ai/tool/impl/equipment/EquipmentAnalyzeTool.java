package com.cretas.aims.ai.tool.impl.equipment;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.EquipmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 设备运行分析工具
 *
 * 分析指定设备的运行统计和使用历史记录。
 *
 * Intent Code: ANALYZE_EQUIPMENT
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class EquipmentAnalyzeTool extends AbstractBusinessTool {

    @Autowired
    private EquipmentService equipmentService;

    @Override
    public String getToolName() {
        return "equipment_analyze";
    }

    @Override
    public String getDescription() {
        return "分析指定设备的运行数据，包括累计运行时长、维护次数和近期使用记录。" +
                "适用场景：分析设备使用情况、查看设备运行历史、评估设备效率。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> equipmentId = new HashMap<>();
        equipmentId.put("type", "string");
        equipmentId.put("description", "要分析的设备ID");
        properties.put("equipmentId", equipmentId);

        schema.put("properties", properties);
        schema.put("required", List.of("equipmentId"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("equipmentId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String equipmentId = getString(params, "equipmentId");
        log.info("执行设备运行分析 - 工厂ID: {}, 设备ID: {}", factoryId, equipmentId);

        Map<String, Object> stats = equipmentService.getEquipmentStatistics(factoryId, equipmentId);
        List<Map<String, Object>> usage = equipmentService.getEquipmentUsageHistory(factoryId, equipmentId);

        Map<String, Object> result = new HashMap<>();
        result.put("statistics", stats);
        result.put("usageHistory", usage);
        result.put("equipmentId", equipmentId);

        StringBuilder sb = new StringBuilder();
        sb.append("设备运行分析 (ID: ").append(equipmentId).append(")\n");
        if (stats.containsKey("totalRunningHours")) {
            sb.append("累计运行: ").append(stats.get("totalRunningHours")).append("小时");
        }
        if (stats.containsKey("maintenanceCount")) {
            sb.append(" | 维护次数: ").append(stats.get("maintenanceCount"));
        }
        sb.append("\n近期使用记录: ").append(usage.size()).append("条");
        result.put("message", sb.toString());

        log.info("设备运行分析完成 - 设备ID: {}, 使用记录数: {}", equipmentId, usage.size());

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("equipmentId".equals(paramName)) {
            return "请问您要分析哪台设备？请提供设备ID。";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        if ("equipmentId".equals(paramName)) {
            return "设备ID";
        }
        return super.getParameterDisplayName(paramName);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
