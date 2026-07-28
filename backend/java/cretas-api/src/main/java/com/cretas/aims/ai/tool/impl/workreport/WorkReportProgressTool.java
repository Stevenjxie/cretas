package com.cretas.aims.ai.tool.impl.workreport;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.repository.ProductionReportRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Production Progress Report Tool
 *
 * Queries today's production progress including total output, good count,
 * defect count, and report count.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class WorkReportProgressTool extends AbstractBusinessTool {

    @Autowired
    private ProductionReportRepository reportRepository;

    @Override
    public String getToolName() {
        return "workreport_progress";
    }

    @Override
    public String getDescription() {
        return "查询生产进度报工数据，包含今日总产量、良品数、次品数、报工单数等。" +
                "适用场景：了解当天生产进度、产量完成情况、良品率统计。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> date = new HashMap<>();
        date.put("type", "string");
        date.put("description", "查询日期，格式: yyyy-MM-dd，默认今日");
        date.put("format", "date");
        properties.put("date", date);

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
        log.info("执行生产进度报工查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        String dateStr = getString(params, "date");
        LocalDate queryDate = dateStr != null ? LocalDate.parse(dateStr) : LocalDate.now();

        Map<String, Object> summary = reportRepository.getProgressSummary(factoryId, queryDate, queryDate);

        Map<String, Object> resultData = new HashMap<>();
        resultData.put("date", queryDate.format(DateTimeFormatter.ISO_DATE));
        resultData.put("totalOutput", summary.get("total_output"));
        resultData.put("totalGood", summary.get("total_good"));
        resultData.put("totalDefect", summary.get("total_defect"));
        resultData.put("reportCount", summary.get("report_count"));

        Map<String, Object> result = new HashMap<>();
        result.put("reportType", "PRODUCTION_PROGRESS_REPORT");
        result.put("message", "生产进度查询完成");
        result.put("data", resultData);

        log.info("生产进度报工查询完成 - 工厂ID: {}, 日期: {}", factoryId, queryDate);

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "date", "请问您要查看哪天的生产进度？（格式：yyyy-MM-dd，默认今日）"
        );
        return questions.get(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "date", "查询日期"
        );
        return displayNames.getOrDefault(paramName, paramName);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
