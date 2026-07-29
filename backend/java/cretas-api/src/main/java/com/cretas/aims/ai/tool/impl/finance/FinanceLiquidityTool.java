package com.cretas.aims.ai.tool.impl.finance;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * 流动比率分析 Tool
 *
 * 从财务数据计算流动比率和速动比率。
 * 对应意图: QUERY_LIQUIDITY
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class FinanceLiquidityTool extends AbstractBusinessTool {

    @Autowired
    private ReportService reportService;

    @Override
    public String getToolName() {
        return "finance_liquidity";
    }

    @Override
    public String getDescription() {
        return "计算流动比率和速动比率，评估企业短期偿债能力。" +
                "适用场景：流动性分析、流动比率查询、短期偿债能力评估。";
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
        log.info("执行流动比率分析 - 工厂ID: {}", factoryId);

        LocalDate startDate = LocalDate.now().minusDays(30);
        LocalDate endDate = LocalDate.now();

        Map<String, Object> finance = reportService.getFinanceReport(factoryId, startDate, endDate);

        double currentAssets = extractDouble(finance, "currentAssets", 1);
        double currentLiabilities = extractDouble(finance, "currentLiabilities", 1);

        double currentRatio = currentLiabilities > 0 ? currentAssets / currentLiabilities : 0;
        double quickRatio = currentLiabilities > 0 ? (currentAssets * 0.7) / currentLiabilities : 0;

        Map<String, Object> result = new HashMap<>();
        result.put("financeData", finance);
        result.put("ratioType", "LIQUIDITY");
        result.put("period", startDate + " 至 " + endDate);
        result.put("currentRatio", currentRatio);
        result.put("quickRatio", quickRatio);

        StringBuilder sb = new StringBuilder();
        sb.append("流动比率分析\n");
        sb.append("分析周期: ").append(startDate).append(" ~ ").append(endDate).append("\n\n");
        sb.append("流动比率: ").append(String.format("%.2f", currentRatio)).append("\n");
        sb.append("速动比率: ").append(String.format("%.2f", quickRatio)).append("\n");
        sb.append("  流动资产: ").append(String.format("%.0f", currentAssets)).append("元\n");
        sb.append("  流动负债: ").append(String.format("%.0f", currentLiabilities)).append("元\n");
        sb.append("  健康参考: 流动比率 > 2.0, 速动比率 > 1.0");

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
