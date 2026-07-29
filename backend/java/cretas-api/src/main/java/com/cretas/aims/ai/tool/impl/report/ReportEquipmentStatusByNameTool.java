package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 按名称查询设备状态 Tool
 *
 * 根据设备名称查询设备运行状态。
 * 对应意图: QUERY_EQUIPMENT_STATUS_BY_NAME
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class ReportEquipmentStatusByNameTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "report_equipment_status_by_name";
    }

    @Override
    public String getDescription() {
        return "根据设备名称查询设备运行状态。" +
                "适用场景：按名称查询设备状态、指定设备运行情况。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> equipmentName = new HashMap<>();
        equipmentName.put("type", "string");
        equipmentName.put("description", "设备名称");
        properties.put("equipmentName", equipmentName);

        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("equipmentName"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        // equipmentName is optional in the handler - returns NEED_MORE_INFO if missing
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行按名称查询设备状态 - 工厂ID: {}", factoryId);

        String equipmentName = getString(params, "equipmentName");

        Map<String, Object> result = new HashMap<>();
        result.put("queryType", "equipment_status_by_name");
        result.put("factoryId", factoryId);
        if (equipmentName != null) {
            result.put("equipmentName", equipmentName);
        }

        String msg = equipmentName != null ?
            "设备「" + equipmentName + "」状态查询已就绪。请前往设备管理页面查看详情。" :
            "请指定设备名称，我将为您查询该设备的运行状态。";

        result.put("message", msg);
        if (equipmentName == null) {
            result.put("status", "NEED_MORE_INFO");
        }

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("equipmentName".equals(paramName)) {
            return "请提供要查询的设备名称。";
        }
        return null;
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        if ("equipmentName".equals(paramName)) {
            return "设备名称";
        }
        return paramName;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
