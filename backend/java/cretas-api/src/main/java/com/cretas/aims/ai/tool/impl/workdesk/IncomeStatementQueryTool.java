package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.finance.report.IncomeStatementDTO;
import com.cretas.aims.service.finance.IncomeStatementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
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
        Map<String, Object> periodParams = new HashMap<>(params == null ? Map.of() : params);
        if (context != null) {
            for (String key : List.of("userInput", "startDate", "endDate", "month")) {
                if (!periodParams.containsKey(key) && context.get(key) != null) {
                    periodParams.put(key, context.get(key));
                }
            }
            Object requestObject = context.get("request");
            if (!periodParams.containsKey("userInput")
                    && requestObject instanceof com.cretas.aims.dto.ai.IntentExecuteRequest request
                    && request.getUserInput() != null) {
                periodParams.put("userInput", request.getUserInput());
            }
        }
        YearMonth[] period = resolvePeriod(periodParams, LocalDate.now());
        Integer startYear = period[0].getYear();
        Integer startMonth = period[0].getMonthValue();
        Integer endYear = period[1].getYear();
        Integer endMonth = period[1].getMonthValue();

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
        java.math.BigDecimal netMarginPercent = null;
        if (marginStatus == IncomeStatementDTO.GrossMarginStatus.CALCULABLE
                && dto.getTotalRevenue() != null && dto.getTotalRevenue().signum() > 0
                && dto.getNetProfit() != null) {
            netMarginPercent = dto.getNetProfit().multiply(java.math.BigDecimal.valueOf(100))
                    .divide(dto.getTotalRevenue(), 2, java.math.RoundingMode.HALF_UP);
        }
        data.put("netMarginPercent", netMarginPercent);

        data.put("actionHint", "/finance/three-statements?type=income-statement"
                + "&startYear=" + startYear + "&startMonth=" + startMonth
                + "&endYear=" + endYear + "&endMonth=" + endMonth);

        String periodLabel = (startYear.equals(endYear) && startMonth.equals(endMonth))
                ? String.format("%d-%02d", startYear, startMonth)
                : String.format("%d-%02d 至 %d-%02d", startYear, startMonth, endYear, endMonth);

        // Sheet 7/22 空成功类: 全零期间此前返回 "营业收入 ¥0.00 / … ¥0.00" 的
        // 零值 dump — 看似查询成功, 实为无数据。改为定向缺数说明, 不用零值替代。
        boolean revenueEmpty = dto.getTotalRevenue() == null || dto.getTotalRevenue().signum() == 0;
        boolean costEmpty = dto.getTotalCost() == null || dto.getTotalCost().signum() == 0;
        boolean expenseEmpty = dto.getTotalExpense() == null || dto.getTotalExpense().signum() == 0;
        if (revenueEmpty && costEmpty && expenseEmpty) {
            data.put("noPostedData", true);
            String emptyMessage = String.format(
                    "%s 暂无已过账的利润表数据，不能给出营业收入或利润数字，也不会用零值替代。"
                            + "请先确认该期间的财务凭证已录入或同步，或换一个有数据的期间查询",
                    periodLabel);
            return buildSimpleResult(emptyMessage, data);
        }

        String message;
        if (missingCostData) {
            message = String.format(
                    "%s 利润表: 营业收入 ¥%s；%s。请先补录或同步成本凭证后再查询毛利率和利润",
                    periodLabel, dto.getTotalRevenue(), marginStatusMessage);
        } else {
            message = String.format(
                    "%s 利润表: 营业收入 ¥%s / 营业成本 ¥%s / 毛利 ¥%s / 营业利润 ¥%s / 净利润 ¥%s",
                    periodLabel,
                    dto.getTotalRevenue(), dto.getTotalCost(), dto.getGrossProfit(),
                    dto.getOperatingProfit(), dto.getNetProfit());
            message += netMarginPercent != null
                    ? " / 净利率 " + netMarginPercent + "%"
                    : " / 净利率暂不可计算";
        }
        return buildSimpleResult(message, data);
    }

    static YearMonth[] resolvePeriod(Map<String, Object> params, LocalDate today) {
        Map<String, Object> safeParams = params == null ? Map.of() : params;
        YearMonth current = YearMonth.from(today);

        boolean hasExplicitParts = safeParams.containsKey("startYear")
                || safeParams.containsKey("startMonth")
                || safeParams.containsKey("endYear")
                || safeParams.containsKey("endMonth");
        if (hasExplicitParts) {
            int startYear = integerValue(safeParams.get("startYear"), current.getYear());
            int startMonth = integerValue(safeParams.get("startMonth"), current.getMonthValue());
            YearMonth start = YearMonth.of(startYear, startMonth);
            int endYear = integerValue(safeParams.get("endYear"), start.getYear());
            int endMonth = integerValue(safeParams.get("endMonth"), start.getMonthValue());
            return orderedPeriod(start, YearMonth.of(endYear, endMonth));
        }

        YearMonth startDateMonth = parseDateMonth(safeParams.get("startDate"));
        YearMonth endDateMonth = parseDateMonth(safeParams.get("endDate"));
        if (startDateMonth != null || endDateMonth != null) {
            YearMonth start = startDateMonth != null ? startDateMonth : endDateMonth;
            YearMonth end = endDateMonth != null ? endDateMonth : start;
            return orderedPeriod(start, end);
        }

        String userInput = String.valueOf(safeParams.getOrDefault("userInput", ""));
        java.util.regex.Matcher absoluteMonth = java.util.regex.Pattern
                .compile("(20\\d{2})[年/-](1[0-2]|0?[1-9])月?")
                .matcher(userInput);
        YearMonth first = null;
        YearMonth last = null;
        while (absoluteMonth.find()) {
            YearMonth matched = YearMonth.of(
                    Integer.parseInt(absoluteMonth.group(1)),
                    Integer.parseInt(absoluteMonth.group(2)));
            if (first == null) {
                first = matched;
            }
            last = matched;
        }
        if (first != null) {
            return orderedPeriod(first, last);
        }
        if (userInput.contains("上月") || userInput.contains("上个月")) {
            YearMonth previous = current.minusMonths(1);
            return new YearMonth[]{previous, previous};
        }
        if (userInput.contains("本月") || userInput.contains("这个月") || userInput.contains("当月")) {
            return new YearMonth[]{current, current};
        }

        YearMonth monthParam = parseDateMonth(safeParams.get("month"));
        if (monthParam != null) {
            return new YearMonth[]{monthParam, monthParam};
        }
        return new YearMonth[]{current, current};
    }

    private static YearMonth[] orderedPeriod(YearMonth start, YearMonth end) {
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("结束月份不能早于起始月份");
        }
        return new YearMonth[]{start, end};
    }

    private static int integerValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString().trim());
            } catch (NumberFormatException ignored) {
                // Fall through to the established default below.
            }
        }
        return fallback;
    }

    private static YearMonth parseDateMonth(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.toString().trim();
        try {
            return value.length() >= 10
                    ? YearMonth.from(LocalDate.parse(value.substring(0, 10)))
                    : YearMonth.parse(value.substring(0, Math.min(7, value.length())));
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
