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
 * 设备健康诊断工具
 *
 * 对工厂所有设备进行健康诊断，统计各状态设备数量并计算健康评分。
 *
 * Intent Code: EQUIPMENT_HEALTH_DIAGNOSIS
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class EquipmentHealthDiagnosisTool extends AbstractBusinessTool {

    @Autowired
    private EquipmentService equipmentService;

    @Override
    public String getToolName() {
        return "equipment_health_diagnosis";
    }

    @Override
    public String getDescription() {
        return "对工厂所有设备进行健康诊断，统计运行、空闲、故障、维护设备数量，计算健康评分。" +
                "适用场景：设备健康检查、设备巡检、设备状态诊断。";
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
        log.info("执行设备健康诊断 - 工厂ID: {}", factoryId);

        List<EquipmentDTO> equipment = equipmentService.getEquipmentList(factoryId, PageRequest.of(1, 200)).getContent();
        int running = 0, idle = 0, fault = 0, maintenance = 0, total = equipment.size();
        for (EquipmentDTO eq : equipment) {
            String status = eq.getStatus() != null ? eq.getStatus().toLowerCase() : "";
            switch (status) {
                case "running", "active" -> running++;
                case "idle", "inactive" -> idle++;
                case "fault" -> fault++;
                case "maintenance" -> maintenance++;
            }
        }

        int healthScore = total > 0 ? (int) ((double)(running + idle) / total * 100) : 0;

        Map<String, Object> result = new HashMap<>();
        result.put("total", total);
        result.put("running", running);
        result.put("idle", idle);
        result.put("fault", fault);
        result.put("maintenance", maintenance);
        result.put("healthScore", healthScore);

        StringBuilder sb = new StringBuilder();
        sb.append("设备健康诊断\n");
        sb.append("总设备数: ").append(total).append("\n");
        sb.append("  运行中: ").append(running).append(" | 空闲: ").append(idle).append("\n");
        sb.append("  故障: ").append(fault).append(" | 维护中: ").append(maintenance).append("\n");
        sb.append("健康评分: ").append(healthScore).append("/100");
        result.put("message", sb.toString().trim());

        log.info("设备健康诊断完成 - 总数: {}, 健康评分: {}/100", total, healthScore);

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
