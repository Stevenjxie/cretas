package com.cretas.aims.ai.tool.impl.finance;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * 杜邦分析 Tool
 *
 * 杜邦三因素分解：销售净利率 x 资产周转率 x 权益乘数。
 * 对应意图: QUERY_DUPONT_ANALYSIS
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class FinanceDupontTool extends AbstractBusinessTool {

    @Autowired
    private ReportService reportService;

    @Override
    public String getToolName() {
        return "finance_dupont";
    }

    @Override
    public String getDescription() {
        return "杜邦分析三因素分解，计算销售净利率、资产周转率、权益乘数及综合ROE。" +
                "适用场景：杜邦分析、综合财务分析、ROE分解。";
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
        log.info("执行杜邦分析 - 工厂ID: {}", factoryId);

        LocalDate startDate = LocalDate.now().minusDays(30);
        LocalDate endDate = LocalDate.now();

        Map<String, Object> finance = reportService.getFinanceReport(factoryId, startDate, endDate);

        double totalRevenue = extractDouble(finance, "totalRevenue", 0);
        double totalCost = extractDouble(finance, "totalCost", 0);
        double totalAssets = extractDouble(finance, "totalAssets", 1);
        double totalEquity = extractDouble(finance, "totalEquity", 1);
        double netProfit = totalRevenue - totalCost;

        double profitMargin = totalRevenue > 0 ? (netProfit / totalRevenue) * 100 : 0;
        double assetTurnover = totalAssets > 0 ? totalRevenue / totalAssets : 0;
        double equityMultiplier = totalEquity > 0 ? totalAssets / totalEquity : 1;
        double dupontRoe = (profitMargin / 100) * assetTurnover * equityMultiplier * 100;

        Map<String, Object> result = new HashMap<>();
        result.put("financeData", finance);
        result.put("ratioType", "DUPONT");
        result.put("period", startDate + " 至 " + endDate);
        result.put("profitMargin", profitMargin);
        result.put("assetTurnover", assetTurnover);
        result.put("equityMultiplier", equityMultiplier);
        result.put("dupontROE", dupontRoe);

        StringBuilder sb = new StringBuilder();
        sb.append("杜邦分析\n");
        sb.append("分析周期: ").append(startDate).append(" ~ ").append(endDate).append("\n\n");
        sb.append("杜邦分析三因素分解:\n");
        sb.append("  销售净利率: ").append(String.format("%.2f%%", profitMargin)).append("\n");
        sb.append("  资产周转率: ").append(String.format("%.2f", assetTurnover)).append("次\n");
        sb.append("  权益乘数: ").append(String.format("%.2f", equityMultiplier)).append("\n");
        sb.append("  ROE = ").append(String.format("%.2f%%", dupontRoe)).append("\n");
        sb.append("  (净利率 × 周转率 × 权益乘数)");

        result.put("message", sb.toString());

        return result;
    }

    private double extractDouble(Map<String, Object> map, String key, double defaultVal) {
        if (map == null || !map.containsKey(key)) return defaultVal;
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).doubleValue();
        try { return Double.parseDouble(String.valueOf(val)); } catch (Exception e) { return defaultVal; }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
