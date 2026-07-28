package com.cretas.aims.ai.tool.impl.finance;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * 偿债能力分析 Tool
 *
 * 从财务数据计算资产负债率和权益比率。
 * 对应意图: QUERY_SOLVENCY
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class FinanceSolvencyTool extends AbstractBusinessTool {

    @Autowired
    private ReportService reportService;

    @Override
    public String getToolName() {
        return "finance_solvency";
    }

    @Override
    public String getDescription() {
        return "计算资产负债率和权益比率，评估企业长期偿债能力。" +
                "适用场景：偿债能力分析、资产负债率查询、财务稳健性评估。";
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
        log.info("执行偿债能力分析 - 工厂ID: {}", factoryId);

        LocalDate startDate = LocalDate.now().minusDays(30);
        LocalDate endDate = LocalDate.now();

        Map<String, Object> finance = reportService.getFinanceReport(factoryId, startDate, endDate);

        double totalAssets = extractDouble(finance, "totalAssets", 1);
        double totalLiabilities = extractDouble(finance, "totalLiabilities", 1);
        double totalEquity = extractDouble(finance, "totalEquity", 1);

        double debtRatio = totalAssets > 0 ? (totalLiabilities / totalAssets) * 100 : 0;
        double equityRatio = totalAssets > 0 ? (totalEquity / totalAssets) * 100 : 0;

        Map<String, Object> result = new HashMap<>();
        result.put("financeData", finance);
        result.put("ratioType", "SOLVENCY");
        result.put("period", startDate + " 至 " + endDate);
        result.put("debtRatio", debtRatio);
        result.put("equityRatio", equityRatio);

        StringBuilder sb = new StringBuilder();
        sb.append("偿债能力分析\n");
        sb.append("分析周期: ").append(startDate).append(" ~ ").append(endDate).append("\n\n");
        sb.append("资产负债率: ").append(String.format("%.2f%%", debtRatio)).append("\n");
        sb.append("权益比率: ").append(String.format("%.2f%%", equityRatio)).append("\n");
        sb.append("  总负债: ").append(String.format("%.0f", totalLiabilities)).append("元\n");
        sb.append("  总资产: ").append(String.format("%.0f", totalAssets)).append("元\n");
        sb.append("  健康参考: 资产负债率 < 60%");

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
