package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.finance.report.CashFlowDTO;
import com.cretas.aims.service.finance.CashFlowService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 现金流量表查询 Tool (Sprint 8 P2 — 财务主管 Workdesk).
 *
 * <p>Wrap {@link CashFlowService#generate} — 直接法现金流, 三类活动: 经营 / 投资 / 筹资.
 *
 * <p>默认本月. 防呆 R2 — 返完整 DTO + 三表跳转 actionHint.
 *
 * <p>Intent Code: {@code CASHFLOW_STATEMENT_QUERY}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P2)
 */
@Slf4j
@Component
public class CashflowStatementQueryTool extends AbstractBusinessTool {

    @Autowired
    private CashFlowService cashFlowService;

    @Override
    public String getToolName() {
        return "cashflow_statement_query";
    }

    @Override
    public String getDescription() {
        return "生成 factory 期间内现金流量表 (直接法, 三类活动: 经营 / 投资 / 筹资). "
                + "LLM 触发场景: 用户问 '5 月现金流' / '本月现金流量表' / '经营活动现金流' / "
                + "'净现金增加' / 'cash flow'. 默认本月. read-only.";
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

        log.info("cashflow_statement_query — factory={} start={}-{} end={}-{}",
                factoryId, startYear, startMonth, endYear, endMonth);

        CashFlowDTO dto = cashFlowService.generate(
                factoryId, startYear, startMonth, endYear, endMonth);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("factoryId", dto.getFactoryId());
        data.put("startYear", dto.getStartYear());
        data.put("startMonth", dto.getStartMonth());
        data.put("endYear", dto.getEndYear());
        data.put("endMonth", dto.getEndMonth());
        data.put("operatingActivities", dto.getOperatingActivities());
        data.put("investingActivities", dto.getInvestingActivities());
        data.put("financingActivities", dto.getFinancingActivities());
        data.put("operatingNetCashFlow", dto.getOperatingNetCashFlow());
        data.put("investingNetCashFlow", dto.getInvestingNetCashFlow());
        data.put("financingNetCashFlow", dto.getFinancingNetCashFlow());
        data.put("netIncreaseInCash", dto.getNetIncreaseInCash());
        data.put("beginningCash", dto.getBeginningCash());
        data.put("endingCash", dto.getEndingCash());
        data.put("generatedAt", dto.getGeneratedAt());

        data.put("actionHint", "/finance/three-statements?type=cashflow"
                + "&startYear=" + startYear + "&startMonth=" + startMonth
                + "&endYear=" + endYear + "&endMonth=" + endMonth);

        String periodLabel = (startYear.equals(endYear) && startMonth.equals(endMonth))
                ? String.format("%d-%02d", startYear, startMonth)
                : String.format("%d-%02d 至 %d-%02d", startYear, startMonth, endYear, endMonth);

        String message = String.format(
                "%s 现金流: 经营 ¥%s / 投资 ¥%s / 筹资 ¥%s / 净增加 ¥%s",
                periodLabel,
                dto.getOperatingNetCashFlow(), dto.getInvestingNetCashFlow(),
                dto.getFinancingNetCashFlow(), dto.getNetIncreaseInCash());
        return buildSimpleResult(message, data);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
