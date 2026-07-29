package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * AI质检分析报告 Tool
 *
 * 获取质检分析报告数据。
 * 对应意图: REPORT_AI_QUALITY, REPORT_INTELLIGENT_QUALITY, REPORT_CHECK
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class ReportAiQualityTool extends AbstractBusinessTool {

    @Autowired
    private ReportService reportService;

    @Override
    public String getToolName() {
        return "report_ai_quality";
    }

    @Override
    public String getDescription() {
        return "获取AI质检分析报告，包含智能质检数据和分析结果。" +
                "适用场景：AI质检分析、智能质检报告、质检报告查询。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> startDate = new HashMap<>();
        startDate.put("type", "string");
        startDate.put("description", "开始日期，格式: yyyy-MM-dd，默认7天前");
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
        log.info("执行AI质检报告查询 - 工厂ID: {}", factoryId);

        LocalDate startDate = LocalDate.now().minusDays(7);
        LocalDate endDate = LocalDate.now();

        String startDateStr = getString(params, "startDate");
        String endDateStr = getString(params, "endDate");
        if (startDateStr != null) {
            startDate = LocalDate.parse(startDateStr);
        }
        if (endDateStr != null) {
            endDate = LocalDate.parse(endDateStr);
        }

        Map<String, Object> quality = reportService.getQualityReport(factoryId, startDate, endDate);

        Map<String, Object> result = new HashMap<>();
        result.put("quality", quality);
        result.put("reportType", "ai_quality");
        result.put("period", startDate + " 至 " + endDate);
        result.put("message", "质检分析报告获取成功，周期: " + startDate + " ~ " + endDate);

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
