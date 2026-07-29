package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 生产周报对比 Tool
 *
 * 获取生产周报对比数据（复用趋势报表逻辑）。
 * 对应意图: REPORT_PRODUCTION_WEEKLY_COMPARISON
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class ReportProductionWeeklyComparisonTool extends AbstractBusinessTool {

    @Autowired
    private ReportService reportService;

    @Override
    public String getToolName() {
        return "report_production_weekly_comparison";
    }

    @Override
    public String getDescription() {
        return "获取生产周报对比数据，包含周度生产趋势和同比环比信息。" +
                "适用场景：生产周对比、周产量趋势分析。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> type = new HashMap<>();
        type.put("type", "string");
        type.put("description", "趋势类型: production(生产), quality(质量), cost(成本)");
        type.put("default", "production");
        properties.put("type", type);

        Map<String, Object> period = new HashMap<>();
        period.put("type", "integer");
        period.put("description", "统计周期天数，默认30天");
        period.put("default", 30);
        properties.put("period", period);

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
        log.info("执行生产周报对比查询 - 工厂ID: {}", factoryId);

        String type = getString(params, "type", "production");
        Integer period = getInteger(params, "period", 30);

        Map<String, Object> trends = reportService.getTrendAnalysisReport(factoryId, type, period);

        Map<String, Object> result = new HashMap<>();
        result.put("reportType", "production_weekly_comparison");
        result.put("type", type);
        result.put("period", period);
        result.put("data", trends);
        result.put("message", "生产周报对比获取成功，分析类型: " + type + "，统计周期: 近" + period + "天。数据已包含趋势变化和同比环比信息。");

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
