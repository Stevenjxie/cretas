package com.cretas.aims.ai.tool.impl.equipment;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.equipment.EquipmentDTO;
import com.cretas.aims.service.EquipmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 设备详情查询工具
 *
 * 查询指定设备的详细信息，包括设备状态、类型、维护记录等。
 *
 * Intent Code: EQUIPMENT_DETAIL
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-08
 */
@Slf4j
@Component
public class EquipmentDetailTool extends AbstractBusinessTool {

    @Autowired
    private EquipmentService equipmentService;

    @Override
    public String getToolName() {
        return "equipment_detail";
    }

    @Override
    public String getDescription() {
        return "查询设备详情。返回指定设备的完整信息，包括设备名称、状态、类型、位置、维护记录等。" +
                "适用场景：查看设备详细信息、了解设备当前状态、获取设备配置。";
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
        log.info("执行设备详情查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        String equipmentId = getString(params, "equipmentId");

        EquipmentDTO equipment = equipmentService.getEquipmentById(factoryId, equipmentId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("equipmentId", equipmentId);
        result.put("equipment", equipment);

        log.info("设备详情查询完成 - 设备ID: {}", equipmentId);

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "equipmentId", "请问您要查询哪个设备的详情？请提供设备ID。"
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

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
