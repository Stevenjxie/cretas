package com.cretas.aims.ai.tool.impl.alert;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.equipment.EquipmentAlertDTO;
import com.cretas.aims.service.EquipmentAlertsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 告警列表查询工具
 *
 * 提供设备告警的分页查询功能，支持按级别、状态、设备等条件筛选。
 * 作为查询类Tool，无必需参数，所有参数均为可选。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class AlertListTool extends AbstractBusinessTool {

    @Autowired
    private EquipmentAlertsService equipmentAlertsService;

    @Override
    public String getToolName() {
        return "alert_list";
    }

    @Override
    public String getDescription() {
        return "查询设备告警列表。支持按级别(CRITICAL/WARNING/INFO)、状态(ACTIVE/ACKNOWLEDGED/RESOLVED/IGNORED)、" +
                "设备ID进行筛选，支持分页。适用场景：查看所有告警、查找特定告警、监控告警状态。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // level: 告警级别（可选）
        Map<String, Object> level = new HashMap<>();
        level.put("type", "string");
        level.put("description", "告警级别筛选");
        level.put("enum", Arrays.asList("CRITICAL", "WARNING", "INFO"));
        properties.put("level", level);

        // status: 告警状态（可选）
        Map<String, Object> status = new HashMap<>();
        status.put("type", "string");
        status.put("description", "告警状态筛选");
        status.put("enum", Arrays.asList("ACTIVE", "ACKNOWLEDGED", "RESOLVED", "IGNORED"));
        properties.put("status", status);

        // equipmentId: 设备ID（可选）
        Map<String, Object> equipmentId = new HashMap<>();
        equipmentId.put("type", "integer");
        equipmentId.put("description", "设备ID，精确匹配");
        properties.put("equipmentId", equipmentId);

        // keyword: 关键词（可选）
        Map<String, Object> keyword = new HashMap<>();
        keyword.put("type", "string");
        keyword.put("description", "关键词，模糊搜索告警消息");
        properties.put("keyword", keyword);

        // page: 页码（可选，默认1）
        Map<String, Object> page = new HashMap<>();
        page.put("type", "integer");
        page.put("description", "页码，从1开始");
        page.put("default", 1);
        page.put("minimum", 1);
        properties.put("page", page);

        // size: 每页数量（可选，默认10）
        Map<String, Object> size = new HashMap<>();
        size.put("type", "integer");
        size.put("description", "每页记录数");
        size.put("default", 10);
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
        log.info("执行告警列表查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 解析分页参数
        Integer page = getInteger(params, "page", 1);
        Integer size = getInteger(params, "size", 10);

        // 解析筛选参数
        String level = getString(params, "level");
        String status = getString(params, "status");
        String keyword = getString(params, "keyword");
        Long equipmentId = getLong(params, "equipmentId");

        // 构建分页请求
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPage(page);
        pageRequest.setSize(size);

        // 调用服务获取数据
        PageResponse<EquipmentAlertDTO> pageResponse = equipmentAlertsService.getAlertList(
                factoryId, pageRequest, keyword, level, status);

        // 如果指定了equipmentId，需要在结果中过滤
        List<EquipmentAlertDTO> content = pageResponse.getContent();
        if (equipmentId != null && content != null) {
            content = filterByEquipmentId(content, equipmentId);
        }

        // 构建返回结果
        Map<String, Object> result = buildPageResult(
                content != null ? content : Collections.emptyList(),
                pageResponse.getTotalElements() != null ? pageResponse.getTotalElements() : 0L,
                pageResponse.getTotalPages() != null ? pageResponse.getTotalPages() : 0,
                page
        );

        // 添加查询条件摘要
        Map<String, Object> queryConditions = new HashMap<>();
        if (level != null) queryConditions.put("level", level);
        if (status != null) queryConditions.put("status", status);
        if (keyword != null) queryConditions.put("keyword", keyword);
        if (equipmentId != null) queryConditions.put("equipmentId", equipmentId);
        result.put("queryConditions", queryConditions);

        log.info("告警列表查询完成 - 总记录数: {}, 当前页: {}",
                pageResponse.getTotalElements(), page);

        return result;
    }

    /**
     * 按设备ID过滤告警列表
     */
    private List<EquipmentAlertDTO> filterByEquipmentId(List<EquipmentAlertDTO> alerts, Long equipmentId) {
        List<EquipmentAlertDTO> filtered = new ArrayList<>();
        for (EquipmentAlertDTO alert : alerts) {
            if (alert.getEquipmentId() != null && alert.getEquipmentId().equals(equipmentId)) {
                filtered.add(alert);
            }
        }
        return filtered;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
