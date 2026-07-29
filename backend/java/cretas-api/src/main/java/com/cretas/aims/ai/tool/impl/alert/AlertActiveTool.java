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
 * 活动告警查询工具
 *
 * 专门查询当前活动状态（未处理）的告警。
 * 用于快速获取需要关注的告警列表。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class AlertActiveTool extends AbstractBusinessTool {

    @Autowired
    private EquipmentAlertsService equipmentAlertsService;

    @Override
    public String getToolName() {
        return "alert_active";
    }

    @Override
    public String getDescription() {
        return "查询当前活动的告警列表。仅返回状态为ACTIVE的告警，即未确认、未解决的告警。" +
                "适用场景：查看需要立即处理的告警、监控当前问题、快速了解系统异常状态。";
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

        // equipmentId: 设备ID（可选）
        Map<String, Object> equipmentId = new HashMap<>();
        equipmentId.put("type", "integer");
        equipmentId.put("description", "设备ID，精确匹配");
        properties.put("equipmentId", equipmentId);

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
        log.info("执行活动告警查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 解析分页参数
        Integer page = getInteger(params, "page", 1);
        Integer size = getInteger(params, "size", 20);

        // 解析筛选参数
        String level = getString(params, "level");
        Long equipmentId = getLong(params, "equipmentId");

        // 构建分页请求
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPage(page);
        pageRequest.setSize(size);

        // 固定查询ACTIVE状态
        String status = "ACTIVE";

        // 调用服务获取数据
        PageResponse<EquipmentAlertDTO> pageResponse = equipmentAlertsService.getAlertList(
                factoryId, pageRequest, null, level, status);

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
        queryConditions.put("status", "ACTIVE");
        if (level != null) queryConditions.put("level", level);
        if (equipmentId != null) queryConditions.put("equipmentId", equipmentId);
        result.put("queryConditions", queryConditions);

        // 添加紧急程度分析
        if (content != null && !content.isEmpty()) {
            result.put("urgencyAnalysis", analyzeUrgency(content));
        }

        log.info("活动告警查询完成 - 总记录数: {}, 当前页: {}",
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

    /**
     * 分析告警紧急程度
     */
    private Map<String, Object> analyzeUrgency(List<EquipmentAlertDTO> alerts) {
        Map<String, Object> analysis = new HashMap<>();

        int criticalCount = 0;
        int warningCount = 0;
        int infoCount = 0;

        for (EquipmentAlertDTO alert : alerts) {
            if (alert.getLevel() != null) {
                switch (alert.getLevel()) {
                    case CRITICAL:
                        criticalCount++;
                        break;
                    case WARNING:
                        warningCount++;
                        break;
                    case INFO:
                        infoCount++;
                        break;
                }
            }
        }

        analysis.put("criticalCount", criticalCount);
        analysis.put("warningCount", warningCount);
        analysis.put("infoCount", infoCount);
        analysis.put("totalActive", alerts.size());
        analysis.put("needsImmediateAttention", criticalCount > 0);

        return analysis;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
