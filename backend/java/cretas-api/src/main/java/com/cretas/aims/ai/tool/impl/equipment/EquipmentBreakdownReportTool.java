package com.cretas.aims.ai.tool.impl.equipment;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.equipment.EquipmentAlertDTO;
import com.cretas.aims.service.EquipmentAlertsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 设备故障报告工具
 *
 * 生成设备故障报告，包括告警列表和统计信息。
 *
 * Intent Code: EQUIPMENT_BREAKDOWN_REPORT
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class EquipmentBreakdownReportTool extends AbstractBusinessTool {

    @Autowired
    private EquipmentAlertsService equipmentAlertsService;

    @Override
    public String getToolName() {
        return "equipment_breakdown_report";
    }

    @Override
    public String getDescription() {
        return "生成设备故障报告，包括告警列表、告警统计、未解决和严重告警数量。" +
                "适用场景：查看故障报告、了解设备告警情况、排查设备问题。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", new HashMap<>());
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行设备故障报告生成 - 工厂ID: {}", factoryId);

        PageRequest pr = new PageRequest();
        pr.setPage(1);
        pr.setSize(50);
        PageResponse<EquipmentAlertDTO> alerts = equipmentAlertsService.getAlertList(factoryId, pr, null, null, null);
        Map<String, Object> stats = equipmentAlertsService.getAlertStatistics(factoryId);

        Map<String, Object> result = new HashMap<>();
        result.put("alerts", alerts.getContent());
        result.put("totalAlerts", alerts.getTotalElements());
        result.put("statistics", stats);

        StringBuilder sb = new StringBuilder();
        sb.append("设备故障报告\n");
        sb.append("告警总数: ").append(alerts.getTotalElements());
        if (stats.containsKey("unresolved")) {
            sb.append(" | 未解决: ").append(stats.get("unresolved"));
        }
        if (stats.containsKey("critical")) {
            sb.append(" | 严重: ").append(stats.get("critical"));
        }
        result.put("message", sb.toString());

        log.info("设备故障报告生成完成 - 告警总数: {}", alerts.getTotalElements());

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
