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
 * 按级别查询告警工具
 *
 * 查询指定级别的告警记录，支持状态筛选。
 * 用于快速获取特定严重程度的告警。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class AlertByLevelTool extends AbstractBusinessTool {

    @Autowired
    private EquipmentAlertsService equipmentAlertsService;

    @Override
    public String getToolName() {
        return "alert_by_level";
    }

    @Override
    public String getDescription() {
        return "按告警级别查询告警列表。可查询CRITICAL(严重)、WARNING(警告)、INFO(提示)级别的告警。" +
                "适用场景：查看严重告警、筛选警告级别问题、获取提示信息。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // level: 告警级别（推荐提供）
        Map<String, Object> level = new HashMap<>();
        level.put("type", "string");
        level.put("description", "告警级别：CRITICAL(严重)、WARNING(警告)、INFO(提示)");
        level.put("enum", Arrays.asList("CRITICAL", "WARNING", "INFO"));
        properties.put("level", level);

        // status: 告警状态（可选）
        Map<String, Object> status = new HashMap<>();
        status.put("type", "string");
        status.put("description", "告警状态筛选");
        status.put("enum", Arrays.asList("ACTIVE", "ACKNOWLEDGED", "RESOLVED", "IGNORED"));
        properties.put("status", status);

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
        log.info("执行按级别查询告警 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 解析分页参数
        Integer page = getInteger(params, "page", 1);
        Integer size = getInteger(params, "size", 20);

        // 解析筛选参数
        String level = getString(params, "level");
        String status = getString(params, "status");

        // 构建分页请求
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPage(page);
        pageRequest.setSize(size);

        // 调用服务获取数据
        PageResponse<EquipmentAlertDTO> pageResponse = equipmentAlertsService.getAlertList(
                factoryId, pageRequest, null, level, status);

        List<EquipmentAlertDTO> content = pageResponse.getContent();

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
        result.put("queryConditions", queryConditions);

        // 添加级别说明
        result.put("levelDescription", getLevelDescription(level));

        // 如果有结果，添加级别分布分析
        if (content != null && !content.isEmpty()) {
            result.put("levelDistribution", analyzeLevelDistribution(content));
        }

        log.info("按级别查询告警完成 - 级别: {}, 总记录数: {}", level,
                pageResponse.getTotalElements());

        return result;
    }

    /**
     * 获取级别说明
     */
    private String getLevelDescription(String level) {
        if (level == null) {
            return "查询所有级别的告警";
        }
        switch (level) {
            case "CRITICAL":
                return "严重级别：需要立即处理的紧急问题，可能导致生产中断或安全隐患";
            case "WARNING":
                return "警告级别：需要关注的问题，应尽快处理以避免升级为严重问题";
            case "INFO":
                return "提示级别：一般性信息提示，可根据情况选择处理";
            default:
                return "未知级别";
        }
    }

    /**
     * 分析级别分布
     */
    private Map<String, Object> analyzeLevelDistribution(List<EquipmentAlertDTO> alerts) {
        Map<String, Object> distribution = new HashMap<>();

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

        distribution.put("CRITICAL", criticalCount);
        distribution.put("WARNING", warningCount);
        distribution.put("INFO", infoCount);
        distribution.put("total", alerts.size());

        return distribution;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
