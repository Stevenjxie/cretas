package com.cretas.aims.ai.tool.impl.equipment;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.equipment.EquipmentDTO;
import com.cretas.aims.service.EquipmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 设备停止工具
 *
 * 停止指定设备，记录运行时长并将设备状态设置为停止。
 *
 * Intent Code: EQUIPMENT_STOP
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-08
 */
@Slf4j
@Component
public class EquipmentStopTool extends AbstractBusinessTool {

    @Autowired
    private EquipmentService equipmentService;

    @Override
    public String getToolName() {
        return "equipment_stop";
    }

    @Override
    public String getDescription() {
        return "停止设备。将指定设备的状态设置为停止（INACTIVE），并记录本次运行时长。" +
                "适用场景：停止生产设备、关机操作、记录设备运行时间。";
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

        // runningHours: 运行时长（必需）
        Map<String, Object> runningHours = new HashMap<>();
        runningHours.put("type", "integer");
        runningHours.put("description", "本次运行时长（小时）");
        runningHours.put("minimum", 0);
        properties.put("runningHours", runningHours);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("equipmentId", "runningHours"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("equipmentId", "runningHours");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行设备停止 - 工厂ID: {}, 参数: {}", factoryId, params);

        String equipmentId = getString(params, "equipmentId");
        Integer runningHours = getInteger(params, "runningHours");

        // 验证运行时长
        if (runningHours < 0) {
            throw new IllegalArgumentException("运行时长不能为负数");
        }

        EquipmentDTO equipment = equipmentService.stopEquipment(factoryId, equipmentId, runningHours);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("equipmentId", equipmentId);
        result.put("status", "INACTIVE");
        result.put("runningHours", runningHours);
        result.put("equipment", equipment);
        result.put("message", "设备已成功停止，本次运行时长: " + runningHours + " 小时");

        log.info("设备停止完成 - 设备ID: {}, 运行时长: {} 小时", equipmentId, runningHours);

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "equipmentId", "请问您要停止哪个设备？请提供设备ID。",
            "runningHours", "请问本次设备运行了多少小时？"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "equipmentId", "设备ID",
            "runningHours", "运行时长（小时）"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
