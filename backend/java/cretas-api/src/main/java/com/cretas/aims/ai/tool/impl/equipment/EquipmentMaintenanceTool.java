package com.cretas.aims.ai.tool.impl.equipment;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.equipment.EquipmentDTO;
import com.cretas.aims.service.EquipmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 设备维护检查工具
 *
 * 查询工厂中需要维护的设备（状态为 maintenance 或 fault 的设备），
 * 汇总维护清单供运维人员参考。
 *
 * Intent Code: EQUIPMENT_MAINTENANCE
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-08
 */
@Slf4j
@Component
public class EquipmentMaintenanceTool extends AbstractBusinessTool {

    @Autowired
    private EquipmentService equipmentService;

    @Override
    public String getToolName() {
        return "equipment_maintenance";
    }

    @Override
    public String getDescription() {
        return "查询工厂中需要维护的设备列表，包括状态为维护中或故障的设备。" +
                "适用场景：设备维护检查、维护工单生成、设备巡检。";
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
        log.info("执行设备维护检查 - 工厂ID: {}", factoryId);

        List<EquipmentDTO> equipment = equipmentService.getEquipmentList(factoryId, PageRequest.of(1, 200)).getContent();
        List<Map<String, Object>> maintenanceNeeded = new ArrayList<>();
        for (EquipmentDTO eq : equipment) {
            if ("maintenance".equalsIgnoreCase(eq.getStatus()) || "fault".equalsIgnoreCase(eq.getStatus())) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", eq.getId());
                item.put("name", eq.getName());
                item.put("status", eq.getStatus());
                maintenanceNeeded.add(item);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("maintenanceDevices", maintenanceNeeded);
        result.put("count", maintenanceNeeded.size());

        String msg;
        if (maintenanceNeeded.isEmpty()) {
            msg = "设备维护检查完成：当前所有设备运行正常，暂无需要维护的设备。系统将持续监控设备健康状态。";
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("设备维护检查完成：共 ").append(maintenanceNeeded.size()).append(" 台设备需要维护\n");
            for (Map<String, Object> item : maintenanceNeeded) {
                sb.append("  - ").append(item.get("name")).append(" (状态: ").append(translateStatus(String.valueOf(item.get("status")))).append(")\n");
            }
            msg = sb.toString().trim();
        }
        result.put("message", msg);

        log.info("设备维护检查完成 - 需维护设备数: {}", maintenanceNeeded.size());

        return result;
    }

    private String translateStatus(String status) {
        if (status == null) return "未知";
        return switch (status.toLowerCase()) {
            case "running", "active" -> "运行中";
            case "idle", "inactive" -> "空闲";
            case "maintenance" -> "维护中";
            case "fault" -> "故障";
            case "offline", "scrapped" -> "离线";
            default -> status;
        };
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
