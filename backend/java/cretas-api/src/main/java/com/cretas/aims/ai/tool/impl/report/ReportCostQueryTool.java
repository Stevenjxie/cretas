package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.MobileDTO;
import com.cretas.aims.service.AIEnterpriseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 成本查询 Tool
 *
 * 处理用户的成本查询请求，支持时间范围查询。
 * 对应意图: COST_QUERY
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class ReportCostQueryTool extends AbstractBusinessTool {

    @Autowired
    @Lazy
    private AIEnterpriseService aiEnterpriseService;

    @Override
    public String getToolName() {
        return "report_cost_query";
    }

    @Override
    public String getDescription() {
        return "查询成本数据，支持按时间范围和维度查询成本构成和变动趋势。" +
                "适用场景：成本查询、成本构成分析、成本变动趋势。";
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

        Map<String, Object> dimension = new HashMap<>();
        dimension.put("type", "string");
        dimension.put("description", "查询维度: overall(总体), category(分类), trend(趋势)");
        dimension.put("default", "overall");
        properties.put("dimension", dimension);

        Map<String, Object> question = new HashMap<>();
        question.put("type", "string");
        question.put("description", "用户的具体成本查询问题");
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
        log.info("执行成本查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        LocalDate startDate = LocalDate.now().minusDays(7);
        LocalDate endDate = LocalDate.now();
        String dimension = getString(params, "dimension", "overall");
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
            result.put("dimension", dimension);
            result.put("message", "成本查询完成，已获取指定期间的成本构成和变动趋势分析数据。");

        } catch (Exception e) {
            log.warn("成本AI分析暂不可用: {}", e.getMessage());
            result.put("period", startDate + " 至 " + endDate);
            result.put("dimension", dimension);
            result.put("message", "成本查询暂时无法使用AI分析服务。当前可查看基础成本数据: 查询周期 " +
                    startDate + " 至 " + endDate + "，维度: " + dimension + "。您可在财务报表模块查看详细的成本数据和趋势图表。");
        }

        return result;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
