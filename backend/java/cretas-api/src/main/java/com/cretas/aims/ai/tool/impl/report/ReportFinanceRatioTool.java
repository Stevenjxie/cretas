package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * 财务分析指标 Tool
 *
 * 计算财务比率指标：ROA、ROE、流动比率、偿债能力、杜邦分析。
 * 对应意图: QUERY_FINANCE_ROA, QUERY_FINANCE_ROE, QUERY_LIQUIDITY,
 *          QUERY_SOLVENCY, QUERY_DUPONT_ANALYSIS
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-08
 */
@Slf4j
@Component
public class ReportFinanceRatioTool extends AbstractBusinessTool {

    @Autowired
    private ReportService reportService;

    @Override
    public String getToolName() {
        return "report_finance_ratio";
    }

    @Override
    public String getDescription() {
        return "计算财务分析指标，支持资产收益率(ROA)、净资产收益率(ROE)、流动比率、偿债能力分析、杜邦分析。" +
                "适用场景：财务指标查询、盈利能力分析、偿债能力评估、杜邦三因素分解。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> ratioType = new HashMap<>();
        ratioType.put("type", "string");
        ratioType.put("description", "财务指标类型: ROA(资产收益率), ROE(净资产收益率), LIQUIDITY(流动比率), SOLVENCY(偿债能力), DUPONT(杜邦分析)");
        ratioType.put("enum", Arrays.asList("ROA", "ROE", "LIQUIDITY", "SOLVENCY", "DUPONT"));
        properties.put("ratioType", ratioType);

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
        schema.put("required", Arrays.asList("ratioType"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("ratioType");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行财务指标分析 - 工厂ID: {}, 参数: {}", factoryId, params);

        String ratioType = getString(params, "ratioType", "ROA");

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
        result.put("ratioType", ratioType);
        result.put("period", startDate + " 至 " + endDate);

        double totalRevenue = extractDouble(finance, "totalRevenue", 0);
        double totalCost = extractDouble(finance, "totalCost", 0);
        double totalAssets = extractDouble(finance, "totalAssets", 1);
        double totalEquity = extractDouble(finance, "totalEquity", 1);
        double currentAssets = extractDouble(finance, "currentAssets", 1);
        double currentLiabilities = extractDouble(finance, "currentLiabilities", 1);
        double totalLiabilities = extractDouble(finance, "totalLiabilities", 1);
        double netProfit = totalRevenue - totalCost;

        String ratioName;

        switch (ratioType) {
            case "ROA":
                ratioName = "资产收益率";
                double roa = totalAssets > 0 ? (netProfit / totalAssets) * 100 : 0;
                result.put("roa", roa);
                result.put("netProfit", netProfit);
                result.put("totalAssets", totalAssets);
                result.put("industryReference", "食品加工 3%-8%");
                break;
            case "ROE":
                ratioName = "净资产收益率";
                double roe = totalEquity > 0 ? (netProfit / totalEquity) * 100 : 0;
                result.put("roe", roe);
                result.put("netProfit", netProfit);
                result.put("totalEquity", totalEquity);
                result.put("industryReference", "食品加工 8%-15%");
                break;
            case "LIQUIDITY":
                ratioName = "流动比率";
                double currentRatio = currentLiabilities > 0 ? currentAssets / currentLiabilities : 0;
                double quickRatio = currentLiabilities > 0 ? (currentAssets * 0.7) / currentLiabilities : 0;
                result.put("currentRatio", currentRatio);
                result.put("quickRatio", quickRatio);
                result.put("currentAssets", currentAssets);
                result.put("currentLiabilities", currentLiabilities);
                result.put("healthReference", "流动比率 > 2.0, 速动比率 > 1.0");
                break;
            case "SOLVENCY":
                ratioName = "偿债能力";
                double debtRatio = totalAssets > 0 ? (totalLiabilities / totalAssets) * 100 : 0;
                double equityRatio = totalAssets > 0 ? (totalEquity / totalAssets) * 100 : 0;
                result.put("debtRatio", debtRatio);
                result.put("equityRatio", equityRatio);
                result.put("totalLiabilities", totalLiabilities);
                result.put("totalAssets", totalAssets);
                result.put("healthReference", "资产负债率 < 60%");
                break;
            case "DUPONT":
                ratioName = "杜邦分析";
                double profitMargin = totalRevenue > 0 ? (netProfit / totalRevenue) * 100 : 0;
                double assetTurnover = totalAssets > 0 ? totalRevenue / totalAssets : 0;
                double equityMultiplier = totalEquity > 0 ? totalAssets / totalEquity : 1;
                double dupontRoe = (profitMargin / 100) * assetTurnover * equityMultiplier * 100;
                result.put("profitMargin", profitMargin);
                result.put("assetTurnover", assetTurnover);
                result.put("equityMultiplier", equityMultiplier);
                result.put("dupontROE", dupontRoe);
                break;
            default:
                ratioName = ratioType;
                break;
        }

        result.put("ratioName", ratioName);
        result.put("message", ratioName + "分析完成，分析周期: " + startDate + " ~ " + endDate);

        return result;
    }

    private double extractDouble(Map<String, Object> map, String key, double defaultVal) {
        if (map == null || !map.containsKey(key)) return defaultVal;
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).doubleValue();
        try { return Double.parseDouble(String.valueOf(val)); } catch (Exception e) { return defaultVal; }
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "ratioType", "请问您要查看哪个财务指标？可选：ROA(资产收益率)、ROE(净资产收益率)、LIQUIDITY(流动比率)、SOLVENCY(偿债能力)、DUPONT(杜邦分析)。",
            "startDate", "请问分析的开始日期是？（格式：yyyy-MM-dd）",
            "endDate", "请问分析的结束日期是？（格式：yyyy-MM-dd）"
        );
        return questions.get(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "ratioType", "财务指标类型",
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
