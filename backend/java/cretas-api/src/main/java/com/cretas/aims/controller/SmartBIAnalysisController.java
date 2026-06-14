package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.smartbi.*;
import com.cretas.aims.service.smartbi.*;
import com.cretas.aims.util.DateRangeUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.cretas.aims.util.ErrorSanitizer;

/**
 * SmartBI Analysis Controller
 *
 * Hosts the SmartBI endpoints that remain on Java after the Phase 2A/2D
 * Java→Python migration: natural-language query and drill-down. Production /
 * quality analysis were random-mock placeholders and have been removed —
 * /analysis/production and /analysis/quality are served by Python (real gold
 * from agg_factory_batch_daily). The other migrated endpoints (sales,
 * department, region, finance, inventory, procurement, alerts, recommendations,
 * incentive plans, schema management, query templates) are routed to Python by
 * the nginx gateway and were removed in T6.5 Phase C.
 *
 * @author Cretas Team
 * @since 2026-02-11
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/smart-bi")
@Tag(name = "SmartBI Analysis", description = "SmartBI Analysis, Query and Drill-down API")
public class SmartBIAnalysisController {

    private final SalesAnalysisService salesAnalysisService;
    private final DepartmentAnalysisService departmentAnalysisService;
    private final RegionAnalysisService regionAnalysisService;
    private final FinanceAnalysisService financeAnalysisService;
    private final SmartBIIntentService intentService;
    private final InventoryHealthAnalysisService inventoryHealthAnalysisService;
    private final ProcurementAnalysisService procurementAnalysisService;
    private final SmartBIService smartBIService;

    @Autowired
    public SmartBIAnalysisController(
            SalesAnalysisService salesAnalysisService,
            DepartmentAnalysisService departmentAnalysisService,
            RegionAnalysisService regionAnalysisService,
            FinanceAnalysisService financeAnalysisService,
            SmartBIIntentService intentService,
            InventoryHealthAnalysisService inventoryHealthAnalysisService,
            ProcurementAnalysisService procurementAnalysisService,
            @Autowired(required = false) SmartBIService smartBIService) {
        this.salesAnalysisService = salesAnalysisService;
        this.departmentAnalysisService = departmentAnalysisService;
        this.regionAnalysisService = regionAnalysisService;
        this.financeAnalysisService = financeAnalysisService;
        this.intentService = intentService;
        this.inventoryHealthAnalysisService = inventoryHealthAnalysisService;
        this.procurementAnalysisService = procurementAnalysisService;
        this.smartBIService = smartBIService;
    }

    // Production / Quality analysis endpoints removed: nginx routes
    // /analysis/production and /analysis/quality to Python (real gold from
    // agg_factory_batch_daily); the former Java random-mock implementations
    // were deleted in the SmartBI Python consolidation.

    // ==================== Natural Language Query ====================

    @RequirePermission({"analytics:read_write"})
    @PostMapping("/query")
    @Operation(summary = "Natural language query", description = "Query and analyze data through natural language")
    public ResponseEntity<ApiResponse<NLQueryResponse>> query(
            @Parameter(description = "Factory ID") @PathVariable String factoryId,
            @RequestBody NLQueryRequest request) {

        log.info("NL query: factoryId={}, query={}", factoryId,
                request.getEffectiveQuery() != null && request.getEffectiveQuery().length() > 50
                        ? request.getEffectiveQuery().substring(0, 50) + "..."
                        : request.getEffectiveQuery());

        try {
            request.setFactoryId(factoryId);

            if (smartBIService != null) {
                NLQueryResponse response = smartBIService.processQuery(factoryId, null, request);
                return ResponseEntity.ok(ApiResponse.success(response));
            }

            IntentResult intentResult = intentService.recognizeIntent(request.getEffectiveQuery(), request.getContext());

            NLQueryResponse response = NLQueryResponse.builder()
                    .intent(intentResult.getIntent() != null ? intentResult.getIntent().name() : "UNKNOWN")
                    .parameters(intentResult.getParameters())
                    .build();

            String responseText = executeQueryByIntent(factoryId, intentResult, request);
            response.setResponseText(responseText);
            response.setFollowUpQuestions(generateFollowUpQuestions(intentResult));

            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            log.error("NL query failed: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("Query failed: " + ErrorSanitizer.sanitize(e)));
        }
    }

    // ==================== Drill-Down ====================

    @RequirePermission({"analytics:read_write"})
    @PostMapping("/drill-down")
    @Operation(summary = "Data drill-down", description = "Multi-dimensional data drill-down analysis")
    public ResponseEntity<ApiResponse<Map<String, Object>>> drillDown(
            @Parameter(description = "Factory ID") @PathVariable String factoryId,
            @RequestBody DrillDownRequestDTO request) {

        log.info("Drill-down: factoryId={}, dimension={}, value={}", factoryId, request.getDimension(), request.getValue());

        try {
            if (smartBIService != null) {
                com.cretas.aims.dto.smartbi.DrillDownRequest dtoRequest =
                        com.cretas.aims.dto.smartbi.DrillDownRequest.builder()
                                .dimension(request.getDimension())
                                .filterValue(request.getValue())
                                .parentDimension(request.getParentDimension())
                                .parentValue(request.getParentValue())
                                .startDate(request.getStartDate())
                                .endDate(request.getEndDate())
                                .additionalFilters(request.getFilters() != null ? request.getFilters() : new HashMap<>())
                                .build();

                Map<String, Object> result = smartBIService.processDrillDown(factoryId, dtoRequest);
                return ResponseEntity.ok(ApiResponse.success(result));
            }

            Map<String, Object> result = new HashMap<>();
            result.put("dimension", request.getDimension());
            result.put("value", request.getValue());
            result.put("parentDimension", request.getParentDimension());

            LocalDate startDate = request.getStartDate() != null ? request.getStartDate() : DateRangeUtils.getStartDateOrDefault(null);
            LocalDate endDate = request.getEndDate() != null ? request.getEndDate() : DateRangeUtils.getEndDateOrDefault(null);

            switch (request.getDimension()) {
                case "region":
                    result.put("data", regionAnalysisService.getProvinceRanking(factoryId, request.getValue(), startDate, endDate));
                    result.put("nextDimension", "province");
                    break;
                case "province":
                    result.put("data", regionAnalysisService.getCityRanking(factoryId, request.getValue(), startDate, endDate));
                    result.put("nextDimension", "city");
                    break;
                case "department":
                    result.put("data", departmentAnalysisService.getDepartmentDetail(factoryId, request.getValue(), startDate, endDate));
                    result.put("nextDimension", "salesperson");
                    break;
                default:
                    result.put("message", "Unsupported drill-down dimension: " + request.getDimension());
            }

            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            log.error("Drill-down failed", e);
            return ResponseEntity.ok(ApiResponse.error("Drill-down failed: " + ErrorSanitizer.sanitize(e)));
        }
    }

    // ==================== Internal DTOs ====================

    /**
     * Drill-down request DTO (controller-specific, maps to dto.smartbi.DrillDownRequest)
     */
    @Data
    public static class DrillDownRequestDTO {
        private String dimension;
        private String value;
        private String parentDimension;
        private String parentValue;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate startDate;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate endDate;
        private Map<String, Object> filters;
    }

    // ==================== Helper Methods ====================

    private String executeQueryByIntent(String factoryId, IntentResult intentResult, NLQueryRequest request) {
        if (intentResult.getIntent() == null) {
            return "Sorry, I cannot understand your question. Please try rephrasing.";
        }

        try {
            com.cretas.aims.dto.smartbi.DateRange dateRange = intentService.parseTimeRange(request.getEffectiveQuery());
            LocalDate startDate = dateRange != null ? dateRange.getStartDate() : DateRangeUtils.getStartDateOrDefault(null);
            LocalDate endDate = dateRange != null ? dateRange.getEndDate() : DateRangeUtils.getEndDateOrDefault(null);

            switch (intentResult.getIntent()) {
                case QUERY_SALES_OVERVIEW:
                case QUERY_SALES_RANKING:
                case QUERY_SALES_TREND:
                    return generateSalesQueryResponse(factoryId, startDate, endDate);
                case QUERY_DEPARTMENT_PERFORMANCE:
                case COMPARE_DEPARTMENT:
                    return generateDepartmentQueryResponse(factoryId, startDate, endDate);
                case QUERY_REGION_ANALYSIS:
                case COMPARE_REGION:
                    return generateRegionQueryResponse(factoryId, startDate, endDate);
                case QUERY_FINANCE_OVERVIEW:
                case QUERY_PROFIT_ANALYSIS:
                case QUERY_COST_ANALYSIS:
                case QUERY_RECEIVABLE:
                    return generateFinanceQueryResponse(factoryId, startDate, endDate);
                case QUERY_OEE_OVERVIEW:
                case QUERY_PRODUCTION_EFFICIENCY:
                case QUERY_EQUIPMENT_UTILIZATION:
                    return generateProductionQueryResponse(factoryId, startDate, endDate);
                case QUERY_QUALITY_SUMMARY:
                case QUERY_DEFECT_ANALYSIS:
                case QUERY_REWORK_COST:
                    return generateQualityQueryResponse(factoryId, startDate, endDate);
                case QUERY_INVENTORY_HEALTH:
                case QUERY_EXPIRY_RISK:
                case QUERY_LOSS_ANALYSIS:
                    return generateInventoryQueryResponse(factoryId, startDate, endDate);
                case QUERY_PROCUREMENT_OVERVIEW:
                case QUERY_SUPPLIER_EVALUATION:
                case QUERY_PURCHASE_COST:
                    return generateProcurementQueryResponse(factoryId, startDate, endDate);
                default:
                    return "Query complete, please check the returned data.";
            }
        } catch (Exception e) {
            log.warn("Generate response text failed: {}", e.getMessage());
            return "Query complete, data returned.";
        }
    }

    private String generateSalesQueryResponse(String factoryId, LocalDate startDate, LocalDate endDate) {
        DashboardResponse dashboard = salesAnalysisService.getSalesOverview(factoryId, startDate, endDate);
        if (dashboard != null && dashboard.getKpiCards() != null && !dashboard.getKpiCards().isEmpty()) {
            KPICard salesKpi = dashboard.getKpiCards().stream()
                    .filter(k -> "TOTAL_SALES".equals(k.getKey())).findFirst().orElse(null);
            if (salesKpi != null && salesKpi.getRawValue() != null) {
                return String.format("During %s to %s, total sales were %s yuan.",
                        startDate, endDate, salesKpi.getRawValue().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
            }
        }
        return "Sales data retrieved, please check details.";
    }

    private String generateDepartmentQueryResponse(String factoryId, LocalDate startDate, LocalDate endDate) {
        List<RankingItem> ranking = departmentAnalysisService.getDepartmentRanking(factoryId, startDate, endDate);
        if (ranking != null && !ranking.isEmpty()) {
            RankingItem top = ranking.get(0);
            String valueStr = top.getValue() != null
                    ? top.getValue().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() : "0.00";
            return String.format("During %s to %s, %s ranked first with sales of %s yuan.",
                    startDate, endDate, top.getName(), valueStr);
        }
        return "Department data retrieved, please check details.";
    }

    private String generateRegionQueryResponse(String factoryId, LocalDate startDate, LocalDate endDate) {
        List<RankingItem> ranking = regionAnalysisService.getRegionRanking(factoryId, startDate, endDate);
        if (ranking != null && !ranking.isEmpty()) {
            RankingItem top = ranking.get(0);
            String valueStr = top.getValue() != null
                    ? top.getValue().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() : "0.00";
            return String.format("During %s to %s, %s region ranked first with sales of %s yuan.",
                    startDate, endDate, top.getName(), valueStr);
        }
        return "Region data retrieved, please check details.";
    }

    private String generateFinanceQueryResponse(String factoryId, LocalDate startDate, LocalDate endDate) {
        List<MetricResult> metrics = financeAnalysisService.getProfitMetrics(factoryId, startDate, endDate);
        if (metrics != null && !metrics.isEmpty()) {
            MetricResult grossProfit = metrics.stream()
                    .filter(m -> "GROSS_PROFIT".equals(m.getMetricCode())).findFirst().orElse(null);
            if (grossProfit != null && grossProfit.getValue() != null) {
                return String.format("During %s to %s, gross profit was %s yuan.",
                        startDate, endDate, grossProfit.getValue().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
            }
        }
        return "Finance data retrieved, please check details.";
    }

    private String generateProductionQueryResponse(String factoryId, LocalDate startDate, LocalDate endDate) {
        // Production OEE is now served by Python (analysis/production, real gold). The
        // former Java OEE summary used a random-mock service (deleted); this NL helper
        // returns a generic pointer until the path is migrated to call Python.
        return "Production data retrieved, please check details.";
    }

    private String generateQualityQueryResponse(String factoryId, LocalDate startDate, LocalDate endDate) {
        // Quality FPY is now served by Python (analysis/quality, real gold). The former
        // Java FPY summary used a random-mock service (deleted); this NL helper returns a
        // generic pointer until the path is migrated to call Python.
        return "Quality data retrieved, please check details.";
    }

    private String generateInventoryQueryResponse(String factoryId, LocalDate startDate, LocalDate endDate) {
        DashboardResponse dashboard = inventoryHealthAnalysisService.getInventoryHealth(factoryId, startDate, endDate);
        if (dashboard != null && dashboard.getKpiCards() != null && !dashboard.getKpiCards().isEmpty()) {
            KPICard valueKpi = dashboard.getKpiCards().stream()
                    .filter(k -> "INVENTORY_VALUE".equals(k.getKey())).findFirst().orElse(null);
            if (valueKpi != null && valueKpi.getRawValue() != null) {
                return String.format("As of %s, inventory value is %.2f yuan.", endDate, valueKpi.getRawValue().doubleValue());
            }
        }
        return "Inventory data retrieved, please check details.";
    }

    private String generateProcurementQueryResponse(String factoryId, LocalDate startDate, LocalDate endDate) {
        DashboardResponse dashboard = procurementAnalysisService.getProcurementOverview(factoryId, startDate, endDate);
        if (dashboard != null && dashboard.getKpiCards() != null && !dashboard.getKpiCards().isEmpty()) {
            KPICard amountKpi = dashboard.getKpiCards().stream()
                    .filter(k -> "PROCUREMENT_AMOUNT".equals(k.getKey())).findFirst().orElse(null);
            if (amountKpi != null && amountKpi.getRawValue() != null) {
                return String.format("During %s to %s, total procurement was %.2f yuan.",
                        startDate, endDate, amountKpi.getRawValue().doubleValue());
            }
        }
        return "Procurement data retrieved, please check details.";
    }

    private List<String> generateFollowUpQuestions(IntentResult intentResult) {
        if (intentResult.getIntent() == null) {
            return List.of("What are this month's sales?", "Which department performs best?", "What's the regional sales ranking?");
        }

        if (intentResult.getIntent().isQueryIntent() && intentResult.getIntent().getCode().contains("sales")) {
            return List.of("Who is the top salesperson?", "What's the sales trend?", "Product sales ranking?");
        } else if (intentResult.getIntent().getCode().contains("dept") || intentResult.getIntent().getCode().contains("department")) {
            return List.of("Department completion rate?", "Per-capita output?", "Department sales trend?");
        } else if (intentResult.getIntent().getCode().contains("region")) {
            return List.of("Provincial sales?", "Region opportunity scores?", "Fastest growing region?");
        } else if (intentResult.getIntent().getCode().contains("finance") || intentResult.getIntent().getCode().contains("profit")
                || intentResult.getIntent().getCode().contains("cost") || intentResult.getIntent().getCode().contains("receivable")) {
            return List.of("Receivable aging distribution?", "Cost structure?", "Budget execution?");
        } else {
            return List.of("View more analysis", "Export report", "Set alerts");
        }
    }
}
