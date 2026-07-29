package com.cretas.aims.ai.tool.impl.equipment;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.equipment.EquipmentDTO;
import com.cretas.aims.service.EquipmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 设备运行状态查询工具
 *
 * 查询所有设备的状态汇总及各状态设备列表，包括运行中、空闲、维护中、故障设备数量及详情。
 *
 * Intent Code: EQUIPMENT_STATUS_QUERY
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class EquipmentStatusQueryTool extends AbstractBusinessTool {

    @Autowired
    private EquipmentService equipmentService;

    @Override
    public String getToolName() {
        return "equipment_status_query";
    }

    @Override
    public String getDescription() {
        return "查询所有设备的运行状态汇总，包括运行中、空闲、维护中、故障设备数量及详情。" +
                "适用场景：查看设备运行状态、了解设备健康状况、查看故障设备。";
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
        log.info("执行设备运行状态查询 - 工厂ID: {}", factoryId);

        // 获取工厂设备总体统计
        Map<String, Object> overallStats = equipmentService.getOverallEquipmentStatistics(factoryId);

        // 获取各状态设备列表
        List<EquipmentDTO> runningEquipment = equipmentService.getEquipmentByStatus(factoryId, "running");
        List<EquipmentDTO> idleEquipment = equipmentService.getEquipmentByStatus(factoryId, "idle");
        List<EquipmentDTO> maintenanceEquipment = equipmentService.getEquipmentByStatus(factoryId, "maintenance");
        List<EquipmentDTO> faultEquipment = equipmentService.getEquipmentByStatus(factoryId, "fault");

        Map<String, Object> result = new HashMap<>();
        result.put("overallStats", overallStats);
        result.put("runningCount", runningEquipment.size());
        result.put("idleCount", idleEquipment.size());
        result.put("maintenanceCount", maintenanceEquipment.size());
        result.put("faultCount", faultEquipment.size());
        result.put("runningEquipment", runningEquipment);
        result.put("idleEquipment", idleEquipment);
        result.put("maintenanceEquipment", maintenanceEquipment);
        result.put("faultEquipment", faultEquipment);

        int totalCount = runningEquipment.size() + idleEquipment.size()
                + maintenanceEquipment.size() + faultEquipment.size();

        result.put("message", buildStatusMessage(totalCount,
                runningEquipment.size(), idleEquipment.size(),
                maintenanceEquipment.size(), faultEquipment.size(),
                faultEquipment));

        log.info("设备状态查询完成 - 总数: {}, 运行: {}, 空闲: {}, 维护: {}, 故障: {}",
                totalCount, runningEquipment.size(), idleEquipment.size(),
                maintenanceEquipment.size(), faultEquipment.size());

        return result;
    }

    private String buildStatusMessage(int total, int running, int idle,
                                      int maintenance, int fault,
                                      List<EquipmentDTO> faultEquipment) {
        StringBuilder sb = new StringBuilder();
        sb.append("设备运行状态 (共").append(total).append("台)\n");
        sb.append("运行中: ").append(running).append("台");
        sb.append(" | 空闲: ").append(idle).append("台");
        sb.append(" | 维护中: ").append(maintenance).append("台");
        if (fault > 0) {
            sb.append(" | 故障: ").append(fault).append("台");
        }

        if (total > 0) {
            double runningRate = (double) running / total * 100;
            sb.append("\n设备运行率: ").append(String.format("%.1f", runningRate)).append("%");
        }

        if (fault > 0 && faultEquipment != null && !faultEquipment.isEmpty()) {
            sb.append("\n故障设备:");
            int count = 0;
            for (EquipmentDTO eq : faultEquipment) {
                if (count >= 3) {
                    sb.append("\n... 等共").append(fault).append("台故障设备");
                    break;
                }
                sb.append("\n  - ").append(eq.getEquipmentName());
                if (eq.getEquipmentCode() != null) {
                    sb.append(" (").append(eq.getEquipmentCode()).append(")");
                }
                count++;
            }
        }

        if (total > 0) {
            String health;
            if (fault == 0 && maintenance <= 1) {
                health = "良好";
            } else if (fault <= 1) {
                health = "一般";
            } else {
                health = "需关注";
            }
            sb.append("\n健康评估: ").append(health);
        }

        return sb.toString();
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
