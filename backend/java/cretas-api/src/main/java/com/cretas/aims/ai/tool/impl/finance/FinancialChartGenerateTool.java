package com.cretas.aims.ai.tool.impl.finance;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.client.PythonSmartBIClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 财务图表生成 Tool
 *
 * 调用 Python 服务生成各类财务分析图表。
 * 支持类型: budget_achievement, yoy_mom_comparison, pnl_waterfall,
 * expense_yoy_budget, category_yoy_comparison, gross_margin_trend,
 * category_structure_donut, 或 'all' 生成全部。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-09
 */
@Slf4j
@Component
public class FinancialChartGenerateTool extends AbstractBusinessTool {

    @Autowired
    private PythonSmartBIClient pythonClient;

    @Override
    public String getToolName() {
        return "financial_chart_generate";
    }

    @Override
    public String getDescription() {
        return "生成财务分析图表。支持类型: budget_achievement(预算完成), yoy_mom_comparison(同比环比), " +
               "pnl_waterfall(损益表), expense_yoy_budget(费用分析), category_yoy_comparison(品类对比), " +
               "gross_margin_trend(毛利率), category_structure_donut(品类结构)。chart_type='all'生成全部可用图表。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("chart_type", Map.of(
            "type", "string",
            "description", "图表类型: budget_achievement, yoy_mom_comparison, pnl_waterfall, " +
                          "expense_yoy_budget, category_yoy_comparison, gross_margin_trend, " +
                          "category_structure_donut, 或 'all' 生成全部"
        ));
        props.put("upload_id", Map.of(
            "type", "integer",
            "description", "Excel上传数据ID"
        ));
        props.put("year", Map.of(
            "type", "integer",
            "description", "分析年份，默认2026"
        ));
        props.put("period_type", Map.of(
            "type", "string",
            "description", "期间类型: month, quarter, year, month_range"
        ));
        props.put("start_month", Map.of(
            "type", "integer",
            "description", "开始月份 (1-12)"
        ));
        props.put("end_month", Map.of(
            "type", "integer",
            "description", "结束月份 (1-12)"
        ));

        return Map.of(
            "type", "object",
            "properties", props,
            "required", List.of("chart_type")
        );
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("chart_type");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String chartType = getString(params, "chart_type");
        Integer uploadId = getInteger(params, "upload_id");
        Integer year = getInteger(params, "year", 2026);
        String periodType = getString(params, "period_type", "year");
        Integer startMonth = getInteger(params, "start_month", 1);
        Integer endMonth = getInteger(params, "end_month", 12);

        log.info("Generating financial chart: type={}, uploadId={}, year={}, period={} ({}-{})",
                chartType, uploadId, year, periodType, startMonth, endMonth);

        // Call Python service
        Map<String, Object> request = new HashMap<>();
        request.put("chart_type", chartType);
        if (uploadId != null) request.put("upload_id", uploadId);
        request.put("year", year);
        request.put("period_type", periodType);
        request.put("start_month", startMonth);
        request.put("end_month", endMonth);
        request.put("factory_id", factoryId);

        Map<String, Object> result = pythonClient.callFinancialDashboard(
                "/generate", request, factoryId);

        if (result == null) {
            return buildSimpleResult("财务图表生成失败：Python服务不可用", null);
        }

        Boolean success = (Boolean) result.get("success");
        if (Boolean.FALSE.equals(success)) {
            String error = (String) result.getOrDefault("error", "未知错误");
            return buildSimpleResult("图表生成失败: " + error, result);
        }

        return buildSimpleResult("财务图表生成成功", result);
    }
}
