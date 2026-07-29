package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.foodsafety.SsopBlockingGate;
import com.cretas.aims.entity.foodsafety.SsopExecutionRecord;
import com.cretas.aims.entity.foodsafety.SsopProcedure;
import com.cretas.aims.repository.foodsafety.SsopBlockingGateRepository;
import com.cretas.aims.repository.foodsafety.SsopExecutionRecordRepository;
import com.cretas.aims.repository.foodsafety.SsopProcedureRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SSOP 月度审计报告 Tool (READ + AGGREGATE) — Sprint 9 P2.E Phase B.
 *
 * <p>第三方 audit (HACCP / FSSC22000 / ISO22000) 必查项 — 证明工厂 SSOP 执行
 * 完成率 + 历史阻产记录 + audit trail.
 *
 * <p>报表内容:
 * <ul>
 *   <li>{@code yearMonth} 报告月份 (e.g. 2026-05)</li>
 *   <li>{@code totalProcedures} 工厂 active 模板数</li>
 *   <li>{@code totalRecords} 月内总 records 数</li>
 *   <li>{@code completionRate} 完成率 (COMPLETED+SKIPPED / total)</li>
 *   <li>{@code statusBreakdown} 按 status 分组数量</li>
 *   <li>{@code blockingGates} 月内阻产事件列表</li>
 *   <li>{@code procedureCompletionRates} 按 procedure 分组完成率</li>
 * </ul>
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"5 月份清洁报告"</li>
 *   <li>"SSOP 月度审计"</li>
 *   <li>"上个月清洁完成率"</li>
 *   <li>"HACCP audit 报告"</li>
 * </ul>
 *
 * <p>read-only.
 *
 * <p>Intent Code: {@code SSOP_MONTHLY_AUDIT_REPORT}
 */
@Slf4j
@Component
public class SsopMonthlyAuditReportTool extends AbstractBusinessTool {

    @Autowired
    private SsopExecutionRecordRepository recordRepository;

    @Autowired
    private SsopProcedureRepository procedureRepository;

    @Autowired
    private SsopBlockingGateRepository blockingGateRepository;

    @Override
    public String getToolName() {
        return "ssop_monthly_audit_report";
    }

    @Override
    public String getDescription() {
        return "SSOP 月度审计报告 (READ + AGGREGATE) — 第三方 audit (HACCP / FSSC22000) 必用. "
                + "返完成率 + status 分组 + 阻产事件 + 按 procedure 完成率. "
                + "LLM 触发: '5 月份清洁报告' / 'SSOP 月度审计' / '上个月清洁完成率' / "
                + "'HACCP audit 报告'. read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> yearMonth = new HashMap<>();
        yearMonth.put("type", "string");
        yearMonth.put("description", "报告月份 ISO YYYY-MM (默认当前月). e.g. 2026-05");
        properties.put("yearMonth", yearMonth);

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
        String yearMonthStr = getString(params, "yearMonth");
        YearMonth ym = yearMonthStr != null && !yearMonthStr.isBlank()
                ? YearMonth.parse(yearMonthStr) : YearMonth.now();
        LocalDate startDate = ym.atDay(1);
        LocalDate endDate = ym.atEndOfMonth();
        log.info("ssop_monthly_audit_report — factory={} yearMonth={} ({} ~ {})",
                factoryId, ym, startDate, endDate);

        // 1. 工厂 active 模板数
        long totalProcedures = procedureRepository.countByFactoryIdAndActiveTrue(factoryId);

        // 2. 月内所有 records (用于 procedure-level 完成率)
        List<SsopExecutionRecord> allRecords = recordRepository
                .findByFactoryIdAndExecutionDateBetween(factoryId, startDate, endDate);

        // 3. status 聚合
        List<Object[]> statusCounts = recordRepository
                .countByStatusInRange(factoryId, startDate, endDate);
        Map<String, Long> statusBreakdown = new LinkedHashMap<>();
        statusBreakdown.put("SCHEDULED", 0L);
        statusBreakdown.put("IN_PROGRESS", 0L);
        statusBreakdown.put("COMPLETED", 0L);
        statusBreakdown.put("FAILED", 0L);
        statusBreakdown.put("SKIPPED", 0L);
        long total = 0;
        for (Object[] row : statusCounts) {
            String status = (String) row[0];
            long count = ((Number) row[1]).longValue();
            statusBreakdown.put(status, count);
            total += count;
        }

        // 4. 完成率 = (COMPLETED + SKIPPED) / total
        long doneCount = statusBreakdown.get("COMPLETED") + statusBreakdown.get("SKIPPED");
        BigDecimal completionRate = total > 0
                ? BigDecimal.valueOf(doneCount * 100.0 / total).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // 5. 月内阻产事件
        LocalDateTime startDt = startDate.atStartOfDay();
        LocalDateTime endDt = endDate.atTime(23, 59, 59);
        List<SsopBlockingGate> gates = blockingGateRepository
                .findByFactoryIdAndBlockedAtBetween(factoryId, startDt, endDt);
        List<Map<String, Object>> gateRows = new ArrayList<>();
        for (SsopBlockingGate g : gates) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", g.getId());
            row.put("productionPlanId", g.getProductionPlanId());
            row.put("blockedAt", g.getBlockedAt().toString());
            row.put("blockReason", g.getBlockReason());
            row.put("failedProcedureIds", g.getFailedProcedureIds());
            row.put("resolvedAt", g.getResolvedAt() != null ? g.getResolvedAt().toString() : null);
            row.put("resolvedBy", g.getResolvedBy());
            gateRows.add(row);
        }

        // 6. Procedure-level 完成率
        Map<Long, long[]> perProcedure = new HashMap<>(); // {total, done}
        for (SsopExecutionRecord r : allRecords) {
            long[] counts = perProcedure.computeIfAbsent(r.getProcedureId(), k -> new long[2]);
            counts[0]++;
            if ("COMPLETED".equals(r.getStatus()) || "SKIPPED".equals(r.getStatus())) {
                counts[1]++;
            }
        }
        List<Map<String, Object>> procedureRates = new ArrayList<>();
        for (Map.Entry<Long, long[]> entry : perProcedure.entrySet()) {
            Long procId = entry.getKey();
            long[] counts = entry.getValue();
            BigDecimal rate = counts[0] > 0
                    ? BigDecimal.valueOf(counts[1] * 100.0 / counts[0]).setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("procedureId", procId);
            Optional<SsopProcedure> procOpt = procedureRepository.findById(procId);
            row.put("procedureName", procOpt.map(SsopProcedure::getName).orElse(null));
            row.put("procedureCode", procOpt.map(SsopProcedure::getProcedureCode).orElse(null));
            row.put("target", procOpt.map(SsopProcedure::getTarget).orElse(null));
            row.put("totalRecords", counts[0]);
            row.put("doneRecords", counts[1]);
            row.put("completionRate", rate);
            procedureRates.add(row);
        }
        // 按完成率升序 (最差的在前 — audit 重点)
        procedureRates.sort((a, b) -> {
            BigDecimal ra = (BigDecimal) a.get("completionRate");
            BigDecimal rb = (BigDecimal) b.get("completionRate");
            return ra.compareTo(rb);
        });

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("factoryId", factoryId);
        data.put("yearMonth", ym.toString());
        data.put("startDate", startDate.toString());
        data.put("endDate", endDate.toString());
        data.put("totalProcedures", totalProcedures);
        data.put("totalRecords", total);
        data.put("completionRate", completionRate);
        data.put("statusBreakdown", statusBreakdown);
        data.put("blockingGatesCount", gateRows.size());
        data.put("blockingGates", gateRows);
        data.put("procedureCompletionRates", procedureRates);
        data.put("actionHint", "/foodsafety/ssop/audit-report?yearMonth=" + ym);

        String message = String.format(
                "%s 工厂 %s SSOP 月度审计: 共 %d 项 records (完成 %d + 跳过 %d = 完成率 %s%%), " +
                "阻产 %d 次, 涉及 %d 个 active 模板. %s",
                factoryId, ym, total, statusBreakdown.get("COMPLETED"), statusBreakdown.get("SKIPPED"),
                completionRate, gateRows.size(), totalProcedures,
                completionRate.compareTo(BigDecimal.valueOf(95)) >= 0
                        ? "✅ 完成率优秀 (>= 95%)"
                        : "⚠️ 完成率不达标 (< 95%), 建议改进");
        return buildSimpleResult(message, data);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
