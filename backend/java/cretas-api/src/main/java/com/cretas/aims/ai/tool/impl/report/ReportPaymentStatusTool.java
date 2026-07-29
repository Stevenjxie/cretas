package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * 收付款状态查询 Tool
 *
 * 查询收付款状态，包含应收账款、应付账款及账龄分析。
 * 对应意图: PAYMENT_STATUS_QUERY
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-08
 */
@Slf4j
@Component
public class ReportPaymentStatusTool extends AbstractBusinessTool {

    @Autowired
    private ReportService reportService;

    @Override
    public String getToolName() {
        return "report_payment_status";
    }

    @Override
    public String getDescription() {
        return "查询收付款状态，包含应收账款、应付账款及账龄分析数据。" +
                "适用场景：收付款查询、应收账款管理、应付账款跟踪、账龄分析。";
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
        log.info("执行收付款状态查询 - 工厂ID: {}", factoryId);

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
        result.put("financeData", finance);
        result.put("queryType", "payment_status");
        result.put("period", startDate + " 至 " + endDate);
        result.put("message", "收付款状态查询完成，包含应收账款、应付账款及账龄分析数据。");

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "startDate", "请问查询的开始日期是？（格式：yyyy-MM-dd）",
            "endDate", "请问查询的结束日期是？（格式：yyyy-MM-dd）"
        );
        return questions.get(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "startDate", "开始日期",
            "endDate", "结束日期"
        );
        return displayNames.getOrDefault(paramName, paramName);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
