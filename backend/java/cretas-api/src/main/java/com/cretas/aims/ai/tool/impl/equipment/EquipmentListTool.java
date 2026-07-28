package com.cretas.aims.ai.tool.impl.equipment;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.equipment.EquipmentDTO;
import com.cretas.aims.service.EquipmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 设备列表查询工具
 *
 * 提供设备的分页查询功能，支持按状态等条件筛选。
 * 作为查询类Tool，无必需参数，所有参数均为可选。
 *
 * Intent Code: EQUIPMENT_LIST
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-08
 */
@Slf4j
@Component
public class EquipmentListTool extends AbstractBusinessTool {

    @Autowired
    private EquipmentService equipmentService;

    @Override
    public String getToolName() {
        return "equipment_list";
    }

    @Override
    public String getDescription() {
        return "查询设备列表。支持分页查询，返回设备基本信息、状态、类型等。" +
                "适用场景：查看所有设备、浏览设备清单、获取设备概览。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // page: 页码（可选，默认1）
        Map<String, Object> page = new HashMap<>();
        page.put("type", "integer");
        page.put("description", "页码，从1开始");
        page.put("default", 1);
        page.put("minimum", 1);
        properties.put("page", page);

        // size: 每页数量（可选，默认20）
        Map<String, Object> size = new HashMap<>();
        size.put("type", "integer");
        size.put("description", "每页记录数");
        size.put("default", 20);
        size.put("minimum", 1);
        size.put("maximum", 100);
        properties.put("size", size);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行设备列表查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        Integer page = getInteger(params, "page", 1);
        Integer size = getInteger(params, "size", 20);

        PageRequest pageRequest = new PageRequest();
        pageRequest.setPage(page);
        pageRequest.setSize(size);

        PageResponse<EquipmentDTO> pageResponse = equipmentService.getEquipmentList(factoryId, pageRequest);

        Map<String, Object> result = buildPageResult(
                pageResponse.getContent() != null ? pageResponse.getContent() : Collections.emptyList(),
                pageResponse.getTotalElements() != null ? pageResponse.getTotalElements() : 0L,
                pageResponse.getTotalPages() != null ? pageResponse.getTotalPages() : 0,
                page
        );

        log.info("设备列表查询完成 - 总记录数: {}, 当前页: {}",
                pageResponse.getTotalElements(), page);

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "page", "请问要查看第几页的设备列表？",
            "size", "请问每页显示多少条记录？"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "page", "页码",
            "size", "每页数量"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
