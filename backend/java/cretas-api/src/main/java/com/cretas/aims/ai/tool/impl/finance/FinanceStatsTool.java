package com.cretas.aims.ai.tool.impl.finance;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * 财务统计 Tool
 *
 * 获取财务统计报表（复用财务报表逻辑）。
 * 对应意图: FINANCE_STATS
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class FinanceStatsTool extends AbstractBusinessTool {

    @Autowired
    private ReportService reportService;

    @Override
    public String getToolName() {
        return "finance_stats";
    }

    @Override
    public String getDescription() {
        return "获取财务统计数据，包含收入、成本、利润等财务概况。" +
                "适用场景：财务统计、财务概况、收支分析。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> startDate = new HashMap<>();
        startDate.put("type", "string");
        startDate.put("description", "开始日期，格式: yyyy-MM-dd，默认30天前");
        startDate.put("format", "date");
        properties.put("startDate", startDate);

        Map<String, Object> endDate = new HashMap<>();
        endDate.put("type", "string");
        endDate.put("description", "结束日期，格式: yyyy-MM-dd，默认今天");
        endDate.put("format", "date");
        properties.put("endDate", endDate);

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
        log.info("执行财务统计查询 - 工厂ID: {}", factoryId);

        LocalDate startDate = LocalDate.now().minusDays(30);
        LocalDate endDate = LocalDate.now();

        String startDateStr = getString(params, "startDate");
        String endDateStr = getString(params, "endDate");
        if (startDateStr != null) {
            startDate = LocalDate.parse(startDateStr);
        }
        if (endDateStr != null) {
            endDate = LocalDate.parse(endDateStr);
        }

        Map<String, Object> finance = reportService.getFinanceReport(factoryId, startDate, endDate);

        Map<String, Object> result = new HashMap<>();
        result.put("reportType", "finance_stats");
        result.put("startDate", startDate.toString());
        result.put("endDate", endDate.toString());
        result.put("data", finance);
        result.put("message", "财务报表获取成功，周期: " + startDate + " 至 " + endDate);

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
