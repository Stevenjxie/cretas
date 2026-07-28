package com.cretas.aims.ai.tool.impl.equipment;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.equipment.EquipmentDTO;
import com.cretas.aims.service.EquipmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 冷链温度查询工具
 *
 * 查询冷链温控设备状态，从设备列表中筛选温控相关设备。
 *
 * Intent Code: COLD_CHAIN_TEMPERATURE
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class EquipmentColdChainTool extends AbstractBusinessTool {

    @Autowired
    private EquipmentService equipmentService;

    @Override
    public String getToolName() {
        return "equipment_cold_chain";
    }

    @Override
    public String getDescription() {
        return "查询冷链温控设备状态，筛选冷链、温控、冷冻相关设备并返回其运行状态。" +
                "适用场景：冷链温度监控、温控设备查看、冷链合规检查。";
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
        log.info("执行冷链温度查询 - 工厂ID: {}", factoryId);

        List<EquipmentDTO> allEquipment = equipmentService.getEquipmentList(factoryId, PageRequest.of(1, 200)).getContent();
        List<Map<String, Object>> coldChainDevices = new ArrayList<>();
        for (EquipmentDTO eq : allEquipment) {
            String name = eq.getName() != null ? eq.getName().toLowerCase() : "";
            String type = eq.getType() != null ? eq.getType().toLowerCase() : "";
            if (name.contains("冷") || name.contains("温") || name.contains("freezer") ||
                name.contains("refriger") || type.contains("冷链") || type.contains("温控")) {
                Map<String, Object> device = new HashMap<>();
                device.put("equipmentId", eq.getId());
                device.put("name", eq.getName());
                device.put("status", eq.getStatus());
                device.put("type", eq.getType());
                coldChainDevices.add(device);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("coldChainDevices", coldChainDevices);
        result.put("totalDevices", coldChainDevices.size());
        result.put("queryTime", LocalDateTime.now().toString());

        StringBuilder sb = new StringBuilder();
        sb.append("冷链温控设备状态\n");
        if (coldChainDevices.isEmpty()) {
            sb.append("当前工厂未配置冷链温控设备。\n");
            sb.append("如需监控冷链温度，请先在设备管理中添加温控设备。");
        } else {
            sb.append("共 ").append(coldChainDevices.size()).append(" 台冷链设备：\n");
            for (Map<String, Object> d : coldChainDevices) {
                sb.append("  - ").append(d.get("name")).append(": ").append(translateStatus(String.valueOf(d.get("status")))).append("\n");
            }
        }
        result.put("message", sb.toString().trim());

        log.info("冷链温度查询完成 - 冷链设备数: {}", coldChainDevices.size());

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
