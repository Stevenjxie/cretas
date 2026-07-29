package com.cretas.aims.ai.tool.impl.equipment;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.equipment.EquipmentDTO;
import com.cretas.aims.service.EquipmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 设备状态更新工具
 *
 * 更新指定设备的状态，支持设置为运行中、停止、维护中、离线等状态。
 *
 * Intent Code: EQUIPMENT_STATUS_UPDATE
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-08
 */
@Slf4j
@Component
public class EquipmentStatusUpdateTool extends AbstractBusinessTool {

    @Autowired
    private EquipmentService equipmentService;

    @Override
    public String getToolName() {
        return "equipment_status_update";
    }

    @Override
    public String getDescription() {
        return "更新设备状态。可将设备状态设置为：ACTIVE（运行中）、INACTIVE（停止）、MAINTENANCE（维护中）、OFFLINE（离线）。" +
                "适用场景：变更设备运行状态、标记设备维护、设备上下线管理。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // equipmentId: 设备ID（必需）
        Map<String, Object> equipmentId = new HashMap<>();
        equipmentId.put("type", "string");
        equipmentId.put("description", "设备ID");
        properties.put("equipmentId", equipmentId);

        // status: 状态（必需）
        Map<String, Object> status = new HashMap<>();
        status.put("type", "string");
        status.put("description", "目标状态");
        status.put("enum", Arrays.asList("ACTIVE", "INACTIVE", "MAINTENANCE", "OFFLINE"));
        properties.put("status", status);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("equipmentId", "status"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("equipmentId", "status");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行设备状态更新 - 工厂ID: {}, 参数: {}", factoryId, params);

        String equipmentId = getString(params, "equipmentId");
        String status = getString(params, "status");

        // 验证状态值
        List<String> validStatuses = Arrays.asList("ACTIVE", "INACTIVE", "MAINTENANCE", "OFFLINE");
        if (!validStatuses.contains(status.toUpperCase())) {
            throw new IllegalArgumentException("无效的状态值: " + status + "。有效值为: " + String.join(", ", validStatuses));
        }

        EquipmentDTO equipment = equipmentService.updateEquipmentStatus(factoryId, equipmentId, status.toUpperCase());

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("equipmentId", equipmentId);
        result.put("newStatus", status.toUpperCase());
        result.put("equipment", equipment);
        result.put("message", "设备状态已更新为: " + getStatusDisplayName(status.toUpperCase()));

        log.info("设备状态更新完成 - 设备ID: {}, 新状态: {}", equipmentId, status);

        return result;
    }

    /**
     * 获取状态显示名称
     */
    private String getStatusDisplayName(String status) {
        Map<String, String> statusNames = Map.of(
            "ACTIVE", "运行中",
            "INACTIVE", "停止",
            "MAINTENANCE", "维护中",
            "OFFLINE", "离线"
        );
        return statusNames.getOrDefault(status, status);
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "equipmentId", "请问您要更新哪个设备的状态？请提供设备ID。",
            "status", "请问要将设备状态设置为什么？可选值：ACTIVE（运行中）、INACTIVE（停止）、MAINTENANCE（维护中）、OFFLINE（离线）。"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "equipmentId", "设备ID",
            "status", "目标状态"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
