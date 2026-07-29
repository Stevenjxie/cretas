package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.MobileDTO;
import com.cretas.aims.service.AIEnterpriseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * 成本趋势分析 / 利润趋势分析 Tool
 *
 * 处理成本趋势分析请求，提供更深入的成本变化分析。
 * 对应意图: COST_TREND_ANALYSIS, PROFIT_TREND_ANALYSIS
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class ReportCostTrendAnalysisTool extends AbstractBusinessTool {

    @Autowired
    @Lazy
    private AIEnterpriseService aiEnterpriseService;

    @Override
    public String getToolName() {
        return "report_cost_trend_analysis";
    }

    @Override
    public String getDescription() {
        return "成本趋势分析，提供各成本类别的变动趋势和环比分析。" +
                "适用场景：成本趋势、利润趋势、成本变化原因分析。";
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

        Map<String, Object> dimension = new HashMap<>();
        dimension.put("type", "string");
        dimension.put("description", "分析维度，默认为 trend");
        dimension.put("default", "trend");
        properties.put("dimension", dimension);

        Map<String, Object> question = new HashMap<>();
        question.put("type", "string");
        question.put("description", "用户的具体分析问题");
        properties.put("question", question);

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
        log.info("执行成本趋势分析 - 工厂ID: {}, 参数: {}", factoryId, params);

        LocalDate startDate = LocalDate.now().minusDays(30);
        LocalDate endDate = LocalDate.now();
        String dimension = getString(params, "dimension", "trend");
        String question = getString(params, "question");

        String startDateStr = getString(params, "startDate");
        String endDateStr = getString(params, "endDate");
        if (startDateStr != null) {
            startDate = LocalDate.parse(startDateStr);
        }
        if (endDateStr != null) {
            endDate = LocalDate.parse(endDateStr);
        }

        if (question == null) {
            question = getString(params, "userQuery");
        }
        if (question == null) {
            question = "请分析成本变化趋势，包括上升或下降的原因";
        }

        Map<String, Object> result = new HashMap<>();

        try {
            MobileDTO.AICostAnalysisResponse costResponse = aiEnterpriseService.analyzeTimeRangeCost(
                    factoryId,
                    null,
                    startDate.atStartOfDay(),
                    endDate.plusDays(1).atStartOfDay(),
                    dimension,
                    question,
                    null
            );

            result.put("analysis", costResponse.getAnalysis());
            result.put("reportId", costResponse.getReportId());
            result.put("cacheHit", costResponse.getCacheHit());
            result.put("processingTimeMs", costResponse.getProcessingTimeMs());
            result.put("period", startDate + " 至 " + endDate);
            result.put("analysisType", "趋势分析");
            result.put("message", "成本趋势分析完成，已生成各成本类别的变动趋势和环比分析。");

        } catch (Exception e) {
            log.warn("成本趋势分析AI服务暂不可用: {}", e.getMessage());
            result.put("period", startDate + " 至 " + endDate);
            result.put("analysisType", "趋势分析");
            result.put("message", "成本趋势分析暂时无法使用AI分析服务。您可以在报表模块查看详细的成本数据和趋势图表。");
        }

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
