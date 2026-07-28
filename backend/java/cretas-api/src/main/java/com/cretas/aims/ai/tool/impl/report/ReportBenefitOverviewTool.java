package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * 经营效益概览 Tool
 *
 * 获取近30天的生产效率、KPI达标率等核心经营数据。
 * 对应意图: REPORT_BENEFIT_OVERVIEW
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class ReportBenefitOverviewTool extends AbstractBusinessTool {

    @Autowired
    private ReportService reportService;

    @Override
    public String getToolName() {
        return "report_benefit_overview";
    }

    @Override
    public String getDescription() {
        return "获取经营效益概览，包含近30天的生产效率、KPI达标率等核心经营数据。" +
                "适用场景：经营效益总览、效率与KPI综合分析。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", Collections.emptyMap());
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行经营效益概览查询 - 工厂ID: {}", factoryId);

        LocalDate startDate = LocalDate.now().minusDays(30);
        LocalDate endDate = LocalDate.now();

        Map<String, Object> efficiency = reportService.getEfficiencyAnalysisReport(factoryId, startDate, endDate);
        Map<String, Object> kpi = reportService.getKPIMetrics(factoryId, endDate);

        Map<String, Object> result = new HashMap<>();
        result.put("efficiency", efficiency);
        result.put("kpi", kpi);
        result.put("period", startDate + " 至 " + endDate);
        result.put("message", "经营效益概览获取成功，包含近30天的生产效率、KPI达标率等核心经营数据。");

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
