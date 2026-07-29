package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.WageCalculation;
import com.cretas.aims.entity.enums.WageMode;
import com.cretas.aims.repository.WageCalculationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工资成本汇总 Tool (Sprint 8 P2 — 财务主管 Workdesk).
 *
 * <p>聚合 {@link WageCalculation} 按 month 汇总: 按件 / 按时 / 混合 三类拆分, 总工资成本.
 *
 * <p>读路径无副作用. 防呆 R2 — 返 employeeCount / mode 拆分 / totalAmount.
 *
 * <p>Intent Code: {@code WAGE_COST_SUMMARY}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P2)
 */
@Slf4j
@Component
public class WageCostSummaryTool extends AbstractBusinessTool {

    @Autowired
    private WageCalculationRepository wageCalculationRepository;

    @Override
    public String getToolName() {
        return "wage_cost_summary";
    }

    @Override
    public String getDescription() {
        return "汇总 factory 指定月份工资成本, 按 PIECE_RATE (按件) / HOURLY (按时) / MIXED (混合) "
                + "三类拆分总额 + 员工人数. LLM 触发场景: 用户问 '5 月工资成本' / '本月工资总额' / "
                + "'按件工资 vs 按时工资' / '工资明细汇总'. 默认本月. read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> year = new HashMap<>();
        year.put("type", "integer");
        year.put("description", "年份 (可选, 默认本年)");
        properties.put("year", year);

        Map<String, Object> month = new HashMap<>();
        month.put("type", "integer");
        month.put("description", "月份 1-12 (可选, 默认本月)");
        month.put("minimum", 1);
        month.put("maximum", 12);
        properties.put("month", month);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        LocalDate today = LocalDate.now();
        Integer year = getInteger(params, "year", today.getYear());
        Integer month = getInteger(params, "month", today.getMonthValue());

        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("month 范围必须 1-12, 当前: " + month);
        }
        LocalDate periodMonth = LocalDate.of(year, month, 1);

        log.info("wage_cost_summary — factory={} year={} month={}", factoryId, year, month);

        List<WageCalculation> all = wageCalculationRepository.findByFactoryIdAndPeriodMonth(
                factoryId, periodMonth);

        // 按 mode 分桶
        Map<WageMode, List<WageCalculation>> byMode = new LinkedHashMap<>();
        byMode.put(WageMode.PIECE_RATE, new ArrayList<>());
        byMode.put(WageMode.HOURLY, new ArrayList<>());
        byMode.put(WageMode.MIXED, new ArrayList<>());
        for (WageCalculation w : all) {
            if (w.getMode() == null) continue;
            byMode.computeIfAbsent(w.getMode(), k -> new ArrayList<>()).add(w);
        }

        // 各 mode 汇总
        List<Map<String, Object>> modeSummary = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        BigDecimal totalHourly = BigDecimal.ZERO;
        BigDecimal totalOvertime = BigDecimal.ZERO;
        BigDecimal totalPieceRate = BigDecimal.ZERO;
        for (Map.Entry<WageMode, List<WageCalculation>> entry : byMode.entrySet()) {
            BigDecimal modeTotal = BigDecimal.ZERO;
            BigDecimal modeHourly = BigDecimal.ZERO;
            BigDecimal modeOvertime = BigDecimal.ZERO;
            BigDecimal modePiece = BigDecimal.ZERO;
            for (WageCalculation w : entry.getValue()) {
                modeTotal = modeTotal.add(nullSafe(w.getTotalAmount()));
                modeHourly = modeHourly.add(nullSafe(w.getHourlyAmount()));
                modeOvertime = modeOvertime.add(nullSafe(w.getOvertimeAmount()));
                modePiece = modePiece.add(nullSafe(w.getPieceRateAmount()));
            }
            grandTotal = grandTotal.add(modeTotal);
            totalHourly = totalHourly.add(modeHourly);
            totalOvertime = totalOvertime.add(modeOvertime);
            totalPieceRate = totalPieceRate.add(modePiece);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mode", entry.getKey().name());
            row.put("modeDisplay", displayMode(entry.getKey()));
            row.put("employeeCount", entry.getValue().size());
            row.put("totalAmount", modeTotal.setScale(2, RoundingMode.HALF_UP));
            row.put("hourlyAmount", modeHourly.setScale(2, RoundingMode.HALF_UP));
            row.put("overtimeAmount", modeOvertime.setScale(2, RoundingMode.HALF_UP));
            row.put("pieceRateAmount", modePiece.setScale(2, RoundingMode.HALF_UP));
            modeSummary.add(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("factoryId", factoryId);
        data.put("year", year);
        data.put("month", month);
        data.put("employeeCount", all.size());
        data.put("totalAmount", grandTotal.setScale(2, RoundingMode.HALF_UP));
        data.put("totalHourly", totalHourly.setScale(2, RoundingMode.HALF_UP));
        data.put("totalOvertime", totalOvertime.setScale(2, RoundingMode.HALF_UP));
        data.put("totalPieceRate", totalPieceRate.setScale(2, RoundingMode.HALF_UP));
        data.put("modeSummary", modeSummary);
        data.put("actionHint", "/hr/salaries?year=" + year + "&month=" + month);

        String message = String.format(
                "%d-%02d 工资成本汇总: 共 %d 员工, 总金额 ¥%s (按件 ¥%s + 按时 ¥%s + 加班 ¥%s)",
                year, month, all.size(),
                grandTotal.setScale(2, RoundingMode.HALF_UP),
                totalPieceRate.setScale(2, RoundingMode.HALF_UP),
                totalHourly.setScale(2, RoundingMode.HALF_UP),
                totalOvertime.setScale(2, RoundingMode.HALF_UP));
        return buildSimpleResult(message, data);
    }

    private String displayMode(WageMode mode) {
        return switch (mode) {
            case PIECE_RATE -> "按件";
            case HOURLY -> "按时";
            case MIXED -> "混合 (按时+按件)";
        };
    }

    private BigDecimal nullSafe(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
