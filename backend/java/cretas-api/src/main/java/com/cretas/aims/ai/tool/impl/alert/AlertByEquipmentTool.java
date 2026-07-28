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
 * 按设备查询告警工具
 *
 * 查询指定设备的所有告警记录，支持状态筛选。
 * 用于分析特定设备的告警历史和当前状态。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class AlertByEquipmentTool extends AbstractBusinessTool {

    @Autowired
    private EquipmentAlertsService equipmentAlertsService;

    @Override
    public String getToolName() {
        return "alert_by_equipment";
    }

    @Override
    public String getDescription() {
        return "查询指定设备的告警列表。根据设备ID获取该设备的所有告警记录，" +
                "支持按状态和级别筛选。适用场景：分析设备故障历史、查看设备当前问题、设备健康评估。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // equipmentId: 设备ID（可选但推荐）
        Map<String, Object> equipmentId = new HashMap<>();
        equipmentId.put("type", "integer");
        equipmentId.put("description", "设备ID，用于查询特定设备的告警");
        properties.put("equipmentId", equipmentId);

        // equipmentName: 设备名称（可选，模糊查询）
        Map<String, Object> equipmentName = new HashMap<>();
        equipmentName.put("type", "string");
        equipmentName.put("description", "设备名称，模糊匹配");
        properties.put("equipmentName", equipmentName);

        // status: 告警状态（可选）
        Map<String, Object> status = new HashMap<>();
        status.put("type", "string");
        status.put("description", "告警状态筛选");
        status.put("enum", Arrays.asList("ACTIVE", "ACKNOWLEDGED", "RESOLVED", "IGNORED"));
        properties.put("status", status);

        // level: 告警级别（可选）
        Map<String, Object> level = new HashMap<>();
        level.put("type", "string");
        level.put("description", "告警级别筛选");
        level.put("enum", Arrays.asList("CRITICAL", "WARNING", "INFO"));
        properties.put("level", level);

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
        // 设备相关参数至少需要一个，但这里作为可选处理
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行按设备查询告警 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 解析分页参数
        Integer page = getInteger(params, "page", 1);
        Integer size = getInteger(params, "size", 20);

        // 解析筛选参数
        Long equipmentId = getLong(params, "equipmentId");
        String equipmentName = getString(params, "equipmentName");
        String status = getString(params, "status");
        String level = getString(params, "level");

        // 构建分页请求
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPage(page);
        pageRequest.setSize(size);

        // 使用设备名称作为关键词搜索
        String keyword = equipmentName;

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
                content != null ? content.size() : 0L,
                pageResponse.getTotalPages() != null ? pageResponse.getTotalPages() : 0,
                page
        );

        // 添加查询条件摘要
        Map<String, Object> queryConditions = new HashMap<>();
        if (equipmentId != null) queryConditions.put("equipmentId", equipmentId);
        if (equipmentName != null) queryConditions.put("equipmentName", equipmentName);
        if (status != null) queryConditions.put("status", status);
        if (level != null) queryConditions.put("level", level);
        result.put("queryConditions", queryConditions);

        // 添加设备告警概况
        if (content != null && !content.isEmpty()) {
            result.put("equipmentAlertSummary", buildEquipmentSummary(content));
        }

        log.info("按设备查询告警完成 - 设备ID: {}, 结果数: {}", equipmentId,
                content != null ? content.size() : 0);

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

    /**
     * 构建设备告警概况
     */
    private Map<String, Object> buildEquipmentSummary(List<EquipmentAlertDTO> alerts) {
        Map<String, Object> summary = new HashMap<>();

        // 按状态统计
        Map<String, Integer> statusCount = new HashMap<>();
        statusCount.put("ACTIVE", 0);
        statusCount.put("ACKNOWLEDGED", 0);
        statusCount.put("RESOLVED", 0);
        statusCount.put("IGNORED", 0);

        // 按级别统计
        Map<String, Integer> levelCount = new HashMap<>();
        levelCount.put("CRITICAL", 0);
        levelCount.put("WARNING", 0);
        levelCount.put("INFO", 0);

        for (EquipmentAlertDTO alert : alerts) {
            if (alert.getStatus() != null) {
                String statusKey = alert.getStatus().name();
                statusCount.put(statusKey, statusCount.getOrDefault(statusKey, 0) + 1);
            }
            if (alert.getLevel() != null) {
                String levelKey = alert.getLevel().name();
                levelCount.put(levelKey, levelCount.getOrDefault(levelKey, 0) + 1);
            }
        }

        summary.put("byStatus", statusCount);
        summary.put("byLevel", levelCount);
        summary.put("totalAlerts", alerts.size());

        return summary;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
