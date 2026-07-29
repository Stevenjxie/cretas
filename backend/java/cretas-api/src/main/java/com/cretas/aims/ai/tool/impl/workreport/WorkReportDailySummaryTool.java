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
 * Production Daily Summary Tool
 *
 * Queries the full daily production summary including total report count,
 * progress summary, and hours summary.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class WorkReportDailySummaryTool extends AbstractBusinessTool {

    @Autowired
    private ProductionReportRepository reportRepository;

    @Override
    public String getToolName() {
        return "workreport_daily_summary";
    }

    @Override
    public String getDescription() {
        return "查询每日生产报工汇总，包含报工总数、生产进度汇总（产量/良品/次品）、工时汇总（工时/人数/产量）。" +
                "适用场景：日报生成、每日生产总结、综合生产数据概览。";
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
        log.info("执行每日报工汇总查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        String dateStr = getString(params, "date");
        LocalDate queryDate = dateStr != null ? LocalDate.parse(dateStr) : LocalDate.now();

        long todayCount = reportRepository.countByFactoryIdAndDate(factoryId, queryDate);
        Map<String, Object> progress = reportRepository.getProgressSummary(factoryId, queryDate, queryDate);
        Map<String, Object> hours = reportRepository.getHoursSummary(factoryId, queryDate, queryDate);

        Map<String, Object> resultData = new HashMap<>();
        resultData.put("date", queryDate.format(DateTimeFormatter.ISO_DATE));
        resultData.put("totalReports", todayCount);
        resultData.put("progressSummary", progress);
        resultData.put("hoursSummary", hours);

        Map<String, Object> result = new HashMap<>();
        result.put("reportType", "PRODUCTION_DAILY_SUMMARY");
        result.put("message", "今日共提交 " + todayCount + " 份报工");
        result.put("data", resultData);

        log.info("每日报工汇总查询完成 - 工厂ID: {}, 日期: {}, 报工数: {}", factoryId, queryDate, todayCount);

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "date", "请问您要查看哪天的报工汇总？（格式：yyyy-MM-dd，默认今日）"
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
