package com.cretas.aims.ai.tool.impl.equipment;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.equipment.EquipmentDTO;
import com.cretas.aims.service.EquipmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 设备启动工具
 *
 * 启动指定设备，将设备状态设置为运行中。
 *
 * Intent Code: EQUIPMENT_START
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-08
 */
@Slf4j
@Component
public class EquipmentStartTool extends AbstractBusinessTool {

    @Autowired
    private EquipmentService equipmentService;

    @Override
    public String getToolName() {
        return "equipment_start";
    }

    @Override
    public String getDescription() {
        return "启动设备。将指定设备的状态设置为运行中（ACTIVE），记录设备启动时间。" +
                "适用场景：启动生产设备、开机操作、恢复设备运行。";
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

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("equipmentId"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("equipmentId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行设备启动 - 工厂ID: {}, 参数: {}", factoryId, params);

        String equipmentId = getString(params, "equipmentId");

        EquipmentDTO equipment = equipmentService.startEquipment(factoryId, equipmentId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("equipmentId", equipmentId);
        result.put("status", "ACTIVE");
        result.put("equipment", equipment);
        result.put("message", "设备已成功启动");

        log.info("设备启动完成 - 设备ID: {}", equipmentId);

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "equipmentId", "请问您要启动哪个设备？请提供设备ID。"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "equipmentId", "设备ID"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
