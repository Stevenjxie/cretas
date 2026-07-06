package com.cretas.aims.service.production;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * B8 生产进度数字打屏看板查询服务.
 * 从 ProductionProgressDashboardController 抽出, 供 Controller 和 AI Tool 复用.
 */
@Slf4j
@Service
public class ProductionProgressDashboardService {

    @PersistenceContext
    private EntityManager entityManager;

    public Map<String, Object> getDashboard(String factoryId, LocalDate date) {
        LocalDate targetDate = date != null ? date : LocalDate.now();
        log.info("[B8 打屏] 查询生产进度 factoryId={} date={}", factoryId, targetDate);

        @SuppressWarnings("unchecked")
        List<Object[]> planRows = entityManager.createNativeQuery(
            "SELECT p.id, p.plan_number, p.planned_quantity, p.actual_quantity, " +
            "       p.status, p.source_customer_name, " +
            "       COALESCE(pt.name, '未命名') as product_name " +
            "FROM production_plans p " +
            "LEFT JOIN product_types pt ON pt.id = p.product_type_id " +
            "WHERE p.factory_id = :factoryId " +
            "  AND (p.batch_date = :date OR CAST(p.created_at AS DATE) = :date) " +
            "  AND p.deleted_at IS NULL " +
            "  AND p.status <> 'CANCELLED' " +
            "ORDER BY p.created_at DESC"
        ).setParameter("factoryId", factoryId)
         .setParameter("date", targetDate)
         .getResultList();

        @SuppressWarnings("unchecked")
        List<Object[]> reportRows = entityManager.createNativeQuery(
            "SELECT COALESCE(product_name, '未分类') as product_name, " +
            "       COALESCE(process_category, '默认工序') as process_category, " +
            "       COALESCE(SUM(CAST(output_quantity AS DECIMAL(12,2))), 0) as output_qty " +
            "FROM production_reports " +
            "WHERE factory_id = :factoryId " +
            "  AND report_date = :date " +
            "  AND report_type = 'PROGRESS' " +
            "  AND deleted_at IS NULL " +
            "GROUP BY product_name, process_category"
        ).setParameter("factoryId", factoryId)
         .setParameter("date", targetDate)
         .getResultList();

        Map<String, Map<String, BigDecimal>> reportsByProduct = new HashMap<>();
        for (Object[] r : reportRows) {
            String productName = (String) r[0];
            String processCategory = (String) r[1];
            BigDecimal qty = toBigDecimal(r[2]);
            reportsByProduct
                .computeIfAbsent(productName, k -> new LinkedHashMap<>())
                .merge(processCategory, qty, BigDecimal::add);
        }

        List<Map<String, Object>> plans = new ArrayList<>();
        int totalWorkOrders = 0;
        int completedWorkOrders = 0;
        int inProgressWorkOrders = 0;
        int pendingWorkOrders = 0;
        BigDecimal totalPlanned = BigDecimal.ZERO;
        BigDecimal totalReported = BigDecimal.ZERO;

        for (Object[] row : planRows) {
            String planId = (String) row[0];
            String planNumber = (String) row[1];
            BigDecimal plannedQty = toBigDecimal(row[2]);
            BigDecimal actualQty = toBigDecimal(row[3]);
            String rawStatus = row[4] != null ? row[4].toString() : "PENDING";
            String customerName = (String) row[5];
            String productName = (String) row[6];

            Map<String, BigDecimal> processes = reportsByProduct.getOrDefault(productName, Collections.emptyMap());
            BigDecimal reportedQty = processes.values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (reportedQty.compareTo(BigDecimal.ZERO) == 0 && actualQty.compareTo(BigDecimal.ZERO) > 0) {
                reportedQty = actualQty;
            }

            // B2-fix (2026-07-06): 计划级"已完成/进行中/未开始"必须以 production_plans.status
            // (真实计划生命周期, 权威来源) 为准 —— 不能靠 production_reports 的产量比例反推。
            // production_reports 当天可能 0 行(报工走别的表/流程), 之前全部误判"未开始 0%"。
            // production_reports 仍保留用于展示"逐工序"明细产量(见下方 processes 分支),
            // 只是不再拿它来决定顶层完成/进行中/未开始的桶。
            String planBucket = mapStatusToBucket(rawStatus);
            boolean isDone = "DONE".equals(planBucket);
            int progressPct = isDone ? 100 : calcPct(reportedQty, plannedQty);
            String planStatus = planBucket;

            List<Map<String, Object>> processList = new ArrayList<>();
            if (processes.isEmpty()) {
                // 无逐工序报工数据 → 用计划真实状态占位, 不再靠 0/0 数量比例臆断"未开始"
                processList.add(Map.of(
                    "processName", "总进度",
                    "plannedQty", plannedQty,
                    "reportedQty", reportedQty,
                    "pct", progressPct,
                    "status", planStatus
                ));
            } else {
                for (Map.Entry<String, BigDecimal> e : processes.entrySet()) {
                    int pPct = isDone ? 100 : calcPct(e.getValue(), plannedQty);
                    String pStatus = isDone ? "DONE"
                            : pPct >= 100 ? "DONE"
                            : pPct > 0 ? "IN_PROGRESS" : planBucket;
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("processName", e.getKey());
                    p.put("plannedQty", plannedQty);
                    p.put("reportedQty", e.getValue());
                    p.put("pct", pPct);
                    p.put("status", pStatus);
                    processList.add(p);
                }
            }

            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("planId", planId);
            plan.put("planNumber", planNumber);
            plan.put("productName", productName);
            plan.put("customerName", customerName != null ? customerName : "-");
            plan.put("plannedQuantity", plannedQty);
            plan.put("reportedQuantity", reportedQty);
            plan.put("progressPct", progressPct);
            plan.put("status", planStatus);
            plan.put("processes", processList);
            plans.add(plan);

            totalPlanned = totalPlanned.add(plannedQty);
            totalReported = totalReported.add(reportedQty);
            totalWorkOrders += processList.size();
            for (Map<String, Object> p : processList) {
                String s = (String) p.get("status");
                if ("DONE".equals(s)) completedWorkOrders++;
                else if ("IN_PROGRESS".equals(s)) inProgressWorkOrders++;
                else pendingWorkOrders++;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalPlans", plans.size());
        summary.put("totalWorkOrders", totalWorkOrders);
        summary.put("completedWorkOrders", completedWorkOrders);
        summary.put("inProgressWorkOrders", inProgressWorkOrders);
        summary.put("pendingWorkOrders", pendingWorkOrders);
        // B2-fix: 整体进度按"已完成工单数/总工单数"计算, 不再单纯按产量比例。
        // 产量(actual_quantity/production_reports)在很多工厂经常缺失填报(见上),
        // 纯数量比例会长期卡在 0%; 已完成工单数是从计划真实状态算出的可靠信号。
        summary.put("overallProgressPct", totalWorkOrders > 0
                ? (int) Math.round(completedWorkOrders * 100.0 / totalWorkOrders)
                : 0);
        summary.put("totalPlannedQuantity", totalPlanned);
        summary.put("totalReportedQuantity", totalReported);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", targetDate.toString());
        data.put("summary", summary);
        data.put("plans", plans);
        return data;
    }

    /**
     * B2-fix: production_plans.status (ProductionPlanStatus 枚举) → 看板 3 态桶。
     * COMPLETED → DONE; IN_PROGRESS/PAUSED(曾开工, 暂停) → IN_PROGRESS;
     * 其余 (PENDING/PLANNED/PREPARED/PENDING_APPROVAL) → PENDING(未开始)。
     * CANCELLED 计划已在 SQL 层过滤掉, 不会流入此处。
     */
    private String mapStatusToBucket(String rawStatus) {
        if (rawStatus == null) return "PENDING";
        return switch (rawStatus) {
            case "COMPLETED" -> "DONE";
            case "IN_PROGRESS", "PAUSED" -> "IN_PROGRESS";
            default -> "PENDING";
        };
    }

    private BigDecimal toBigDecimal(Object val) {
        if (val == null) return BigDecimal.ZERO;
        if (val instanceof BigDecimal bd) return bd;
        if (val instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return new BigDecimal(val.toString());
    }

    private int calcPct(BigDecimal reported, BigDecimal planned) {
        if (planned == null || planned.compareTo(BigDecimal.ZERO) <= 0) return 0;
        int pct = reported.multiply(BigDecimal.valueOf(100))
                .divide(planned, 0, RoundingMode.HALF_UP)
                .intValue();
        return Math.min(100, Math.max(0, pct));
    }
}
