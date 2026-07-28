package com.cretas.aims.ai.tool.impl.equipment;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.equipment.EquipmentDTO;
import com.cretas.aims.service.EquipmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 按名称搜索设备工具
 *
 * 根据设备名称关键词搜索设备并返回其状态信息。
 *
 * Intent Code: QUERY_EQUIPMENT_STATUS_BY_NAME
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class EquipmentSearchByNameTool extends AbstractBusinessTool {

    @Autowired
    private EquipmentService equipmentService;

    @Override
    public String getToolName() {
        return "equipment_search_by_name";
    }

    @Override
    public String getDescription() {
        return "按名称关键词搜索设备，返回匹配设备的状态信息。" +
                "适用场景：按名称查找设备、搜索特定设备的状态。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> keyword = new HashMap<>();
        keyword.put("type", "string");
        keyword.put("description", "设备名称关键词");
        properties.put("keyword", keyword);

        schema.put("properties", properties);
        schema.put("required", List.of("keyword"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("keyword");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String keyword = getString(params, "keyword");
        log.info("执行按名称搜索设备 - 工厂ID: {}, 关键词: {}", factoryId, keyword);

        List<EquipmentDTO> results = equipmentService.searchEquipment(factoryId, keyword);

        Map<String, Object> result = new HashMap<>();
        result.put("equipment", results);
        result.put("keyword", keyword);
        result.put("total", results.size());

        String msg = results.isEmpty()
                ? "未找到名称包含\"" + keyword + "\"的设备"
                : "找到 " + results.size() + " 台设备（关键词: " + keyword + "）";
        result.put("message", msg);

        log.info("按名称搜索设备完成 - 关键词: {}, 结果数: {}", keyword, results.size());

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("keyword".equals(paramName)) {
            return "请提供设备名称关键词，用于搜索设备。";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        if ("keyword".equals(paramName)) {
            return "设备名称关键词";
        }
        return super.getParameterDisplayName(paramName);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
