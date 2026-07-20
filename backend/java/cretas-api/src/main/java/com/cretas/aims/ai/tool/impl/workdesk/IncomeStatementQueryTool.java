package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.finance.report.IncomeStatementDTO;
import com.cretas.aims.service.finance.IncomeStatementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 利润表查询 Tool (Sprint 8 P2 — 财务主管 Workdesk).
 *
 * <p>Wrap {@link IncomeStatementService#generate} — 期间内 (startYear-startMonth 月初, endYear-endMonth 月底)
 * 利润表 4 层: 营业收入 → 营业成本 → 营业毛利 → 净利润.
 *
 * <p>默认本月 (start=end=current month). 防呆 R2 — 返完整 DTO + 三表跳转 actionHint.
 *
 * <p>Intent Code: {@code INCOME_STATEMENT_QUERY}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P2)
 */
@Slf4j
@Component
public class IncomeStatementQueryTool extends AbstractBusinessTool {

    @Autowired
    private IncomeStatementService incomeStatementService;

    @Override
    public String getToolName() {
        return "income_statement_query";
    }

    @Override
    public String getDescription() {
        return "生成 factory 期间内利润表 (营业收入 → 营业成本 → 毛利 → 营业利润 → 净利润, 中国 GAAP 简化). "
                + "LLM 触发场景: 用户问 '5 月利润表' / '本月营业收入' / '净利润多少' / "
                + "'毛利率' / 'P&L'. 默认本月 single period. read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> startYear = new HashMap<>();
        startYear.put("type", "integer");
        startYear.put("description", "起始年 (可选, 默认本年)");
        properties.put("startYear", startYear);

        Map<String, Object> startMonth = new HashMap<>();
        startMonth.put("type", "integer");
        startMonth.put("description", "起始月 1-12 (可选, 默认本月)");
        startMonth.put("minimum", 1);
        startMonth.put("maximum", 12);
        properties.put("startMonth", startMonth);

        Map<String, Object> endYear = new HashMap<>();
        endYear.put("type", "integer");
        endYear.put("description", "结束年 (可选, 默认 startYear)");
        properties.put("endYear", endYear);

        Map<String, Object> endMonth = new HashMap<>();
        endMonth.put("type", "integer");
        endMonth.put("description", "结束月 1-12 (可选, 默认 startMonth)");
        endMonth.put("minimum", 1);
        endMonth.put("maximum", 12);
        properties.put("endMonth", endMonth);

        schema.put("properties", properties);
        schema.put("required", List.of());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        LocalDate today = LocalDate.now();
        Integer startYear = getInteger(params, "startYear", today.getYear());
        Integer startMonth = getInteger(params, "startMonth", today.getMonthValue());
        Integer endYear = getInteger(params, "endYear", startYear);
        Integer endMonth = getInteger(params, "endMonth", startMonth);

        if (startMonth < 1 || startMonth > 12 || endMonth < 1 || endMonth > 12) {
            throw new IllegalArgumentException("month 范围必须 1-12");
        }

        log.info("income_statement_query — factory={} start={}-{} end={}-{}",
                factoryId, startYear, startMonth, endYear, endMonth);

        IncomeStatementDTO dto = incomeStatementService.generate(
                factoryId, startYear, startMonth, endYear, endMonth);

        IncomeStatementDTO.GrossMarginStatus marginStatus = dto.getGrossMarginStatus();
        if (marginStatus == null) {
            boolean hasRevenue = dto.getTotalRevenue() != null && dto.getTotalRevenue().signum() > 0;
            boolean hasCostLines = dto.getCosts() != null && !dto.getCosts().isEmpty();
            marginStatus = !hasRevenue
                    ? IncomeStatementDTO.GrossMarginStatus.NO_REVENUE
                    : hasCostLines
                        ? IncomeStatementDTO.GrossMarginStatus.CALCULABLE
                        : IncomeStatementDTO.GrossMarginStatus.MISSING_COST_DATA;
        }
        boolean missingCostData = marginStatus == IncomeStatementDTO.GrossMarginStatus.MISSING_COST_DATA;
        String marginStatusMessage = dto.getGrossMarginStatusMessage();
        if (marginStatusMessage == null || marginStatusMessage.isBlank()) {
            marginStatusMessage = switch (marginStatus) {
                case CALCULABLE -> "毛利率可按已过账营业收入和营业成本计算";
                case MISSING_COST_DATA -> "期间没有已过账的营业成本分录，不能把缺失成本当作 0 计算毛利率";
                case NO_REVENUE -> "期间营业收入为零或缺失，毛利率不可计算";
            };
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("factoryId", dto.getFactoryId());
        data.put("startYear", dto.getStartYear());
        data.put("startMonth", dto.getStartMonth());
        data.put("endYear", dto.getEndYear());
        data.put("endMonth", dto.getEndMonth());
        data.put("revenues", dto.getRevenues());
        data.put("costs", dto.getCosts());
        data.put("expenses", dto.getExpenses());
        data.put("totalRevenue", dto.getTotalRevenue());
        // Missing cost postings are unknown, not zero. Withhold every derived
        // profit metric so the LLM cannot reconstruct a false 100% margin.
        data.put("totalCost", missingCostData ? null : dto.getTotalCost());
        data.put("grossProfit", missingCostData ? null : dto.getGrossProfit());
        data.put("totalExpense", dto.getTotalExpense());
        data.put("operatingProfit", missingCostData ? null : dto.getOperatingProfit());
        data.put("incomeTax", dto.getIncomeTax());
        data.put("netProfit", missingCostData ? null : dto.getNetProfit());
        data.put("generatedAt", dto.getGeneratedAt());
        data.put("costDataAvailable", !missingCostData && dto.isCostDataAvailable());
        data.put("grossMarginStatus", marginStatus.name());
        data.put("grossMarginStatusMessage", marginStatusMessage);
        data.put("metricWarnings", marginStatus == IncomeStatementDTO.GrossMarginStatus.CALCULABLE
                ? List.of() : List.of(marginStatusMessage));

        // 计算毛利率 (R2 context, 方便 LLM 输出)
        if (marginStatus == IncomeStatementDTO.GrossMarginStatus.CALCULABLE
                && dto.getTotalRevenue() != null && dto.getTotalRevenue().signum() > 0
                && dto.getGrossProfit() != null) {
            data.put("grossMarginPercent",
                    dto.getGrossProfit().multiply(java.math.BigDecimal.valueOf(100))
                            .divide(dto.getTotalRevenue(), 2, java.math.RoundingMode.HALF_UP));
        } else {
            data.put("grossMarginPercent", null);
        }

        data.put("actionHint", "/finance/three-statements?type=income-statement"
                + "&startYear=" + startYear + "&startMonth=" + startMonth
                + "&endYear=" + endYear + "&endMonth=" + endMonth);

        String periodLabel = (startYear.equals(endYear) && startMonth.equals(endMonth))
                ? String.format("%d-%02d", startYear, startMonth)
                : String.format("%d-%02d 至 %d-%02d", startYear, startMonth, endYear, endMonth);

        String message = missingCostData
                ? String.format("%s 利润表: 营业收入 ¥%s；%s。请先补录或同步成本凭证后再查询毛利率和利润",
                    periodLabel, dto.getTotalRevenue(), marginStatusMessage)
                : String.format(
                    "%s 利润表: 营业收入 ¥%s / 营业成本 ¥%s / 毛利 ¥%s / 营业利润 ¥%s / 净利润 ¥%s",
                    periodLabel,
                    dto.getTotalRevenue(), dto.getTotalCost(), dto.getGrossProfit(),
                    dto.getOperatingProfit(), dto.getNetProfit());
        return buildSimpleResult(message, data);
    }
}
