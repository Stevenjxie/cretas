package com.cretas.aims.ai.tool.impl.finance;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * 净资产收益率 (ROE) 分析 Tool
 *
 * 从财务数据计算净资产收益率。
 * 对应意图: QUERY_FINANCE_ROE
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class FinanceRoeTool extends AbstractBusinessTool {

    @Autowired
    private ReportService reportService;

    @Override
    public String getToolName() {
        return "finance_roe";
    }

    @Override
    public String getDescription() {
        return "计算净资产收益率(ROE)，分析净利润与股东权益的比率。" +
                "适用场景：ROE分析、净资产回报率查询、股东权益收益评估。";
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
        log.info("执行ROE分析 - 工厂ID: {}", factoryId);

        LocalDate startDate = LocalDate.now().minusDays(30);
        LocalDate endDate = LocalDate.now();

        Map<String, Object> finance = reportService.getFinanceReport(factoryId, startDate, endDate);

        double totalRevenue = extractDouble(finance, "totalRevenue", 0);
        double totalCost = extractDouble(finance, "totalCost", 0);
        double totalEquity = extractDouble(finance, "totalEquity", 1);
        double netProfit = totalRevenue - totalCost;

        double roe = totalEquity > 0 ? (netProfit / totalEquity) * 100 : 0;

        Map<String, Object> result = new HashMap<>();
        result.put("financeData", finance);
        result.put("ratioType", "ROE");
        result.put("period", startDate + " 至 " + endDate);
        result.put("roe", roe);

        StringBuilder sb = new StringBuilder();
        sb.append("净资产收益率分析\n");
        sb.append("分析周期: ").append(startDate).append(" ~ ").append(endDate).append("\n\n");
        sb.append("净资产收益率(ROE): ").append(String.format("%.2f%%", roe)).append("\n");
        sb.append("  净利润: ").append(String.format("%.0f", netProfit)).append("元\n");
        sb.append("  股东权益: ").append(String.format("%.0f", totalEquity)).append("元\n");
        sb.append("  行业参考: 食品加工 8%-15%");

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
