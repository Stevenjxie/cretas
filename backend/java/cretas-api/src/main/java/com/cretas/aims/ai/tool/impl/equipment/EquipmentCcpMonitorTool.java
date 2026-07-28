package com.cretas.aims.ai.tool.impl.equipment;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.equipment.EquipmentDTO;
import com.cretas.aims.service.EquipmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * CCP关键控制点监控工具
 *
 * 监控CCP关键控制点数据，检查设备合规状态。
 *
 * Intent Code: CCP_MONITOR_DATA_DETECTION
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class EquipmentCcpMonitorTool extends AbstractBusinessTool {

    @Autowired
    private EquipmentService equipmentService;

    @Override
    public String getToolName() {
        return "equipment_ccp_monitor";
    }

    @Override
    public String getDescription() {
        return "监控CCP关键控制点数据，检查运行设备和故障设备状态，判断CCP合规状态。" +
                "适用场景：CCP监控、关键控制点检测、食品安全合规检查。";
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
        log.info("执行CCP关键控制点监控 - 工厂ID: {}", factoryId);

        Map<String, Object> overallStats = equipmentService.getOverallEquipmentStatistics(factoryId);
        List<EquipmentDTO> running = equipmentService.getEquipmentByStatus(factoryId, "running");
        List<EquipmentDTO> fault = equipmentService.getEquipmentByStatus(factoryId, "fault");

        Map<String, Object> result = new HashMap<>();
        result.put("overallStats", overallStats);
        result.put("runningEquipment", running);
        result.put("faultEquipment", fault);
        result.put("ccpCompliant", fault.isEmpty());

        StringBuilder sb = new StringBuilder();
        sb.append("CCP关键控制点监控\n");
        sb.append("运行设备: ").append(running.size()).append("台");
        sb.append(" | 故障设备: ").append(fault.size()).append("台\n");
        sb.append("CCP合规状态: ").append(fault.isEmpty() ? "正常" : "异常 — 有故障设备需处理");
        result.put("message", sb.toString());

        log.info("CCP监控完成 - 运行: {}, 故障: {}, 合规: {}",
                running.size(), fault.size(), fault.isEmpty());

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
