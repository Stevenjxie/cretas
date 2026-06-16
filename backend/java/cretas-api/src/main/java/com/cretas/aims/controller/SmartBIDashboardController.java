package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.smartbi.*;
import com.cretas.aims.dto.smartbi.chart.AdaptiveChartRequest;
import com.cretas.aims.dto.smartbi.chart.AdaptiveChartResponse;
import com.cretas.aims.service.smartbi.*;
import com.cretas.aims.service.smartbi.chart.AdaptiveChartGenerator;
import com.cretas.aims.util.DateRangeUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import jakarta.validation.Valid;
import java.time.LocalDate;
import com.cretas.aims.util.ErrorSanitizer;

/**
 * SmartBI Dashboard Controller
 *
 * Handles executive dashboard, unified dashboard, data date range,
 * and adaptive chart generation endpoints.
 * AUDIT-085: Extracted from SmartBIController to reduce file size.
 *
 * @author Cretas Team
 * @since 2026-02-11
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/smart-bi")
@Tag(name = "SmartBI Dashboard", description = "SmartBI Dashboard and chart generation API")
public class SmartBIDashboardController {

    private final SalesAnalysisService salesAnalysisService;
    private final DepartmentAnalysisService departmentAnalysisService;
    private final RegionAnalysisService regionAnalysisService;
    private final FinanceAnalysisService financeAnalysisService;
    private final InventoryHealthAnalysisService inventoryHealthAnalysisService;
    private final ProcurementAnalysisService procurementAnalysisService;
    private final RecommendationService recommendationService;
    private final SmartBIService smartBIService;
    private final AdaptiveChartGenerator adaptiveChartGenerator;
    private final DynamicAnalysisService dynamicAnalysisService;
    // FIX-13 (Apr 16 2026): cache dashboard payload in smart_bi_pg_analysis_results
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.smartbi.postgres.SmartBiPgAnalysisResultRepository analysisResultRepository;

    // Phase 9 Apr 24: SSE relay to Python /insights/custom/stream
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.client.AgentInsightsClient agentInsightsClient;
    @org.springframework.beans.factory.annotation.Autowired
    private com.fasterxml.jackson.databind.ObjectMapper cacheObjectMapper;

    @Autowired
    public SmartBIDashboardController(
            SalesAnalysisService salesAnalysisService,
            DepartmentAnalysisService departmentAnalysisService,
            RegionAnalysisService regionAnalysisService,
            FinanceAnalysisService financeAnalysisService,
            InventoryHealthAnalysisService inventoryHealthAnalysisService,
            ProcurementAnalysisService procurementAnalysisService,
            RecommendationService recommendationService,
            @Autowired(required = false) SmartBIService smartBIService,
            @Autowired(required = false) AdaptiveChartGenerator adaptiveChartGenerator,
            @Autowired(required = false) DynamicAnalysisService dynamicAnalysisService) {
        this.salesAnalysisService = salesAnalysisService;
        this.departmentAnalysisService = departmentAnalysisService;
        this.regionAnalysisService = regionAnalysisService;
        this.financeAnalysisService = financeAnalysisService;
        this.inventoryHealthAnalysisService = inventoryHealthAnalysisService;
        this.procurementAnalysisService = procurementAnalysisService;
        this.recommendationService = recommendationService;
        this.smartBIService = smartBIService;
        this.adaptiveChartGenerator = adaptiveChartGenerator;
        this.dynamicAnalysisService = dynamicAnalysisService;
    }

    // ==================== Adaptive Chart Generation ====================

    @RequirePermission({"analytics:read_write"})
    @PostMapping("/generate-adaptive-charts")
    @Operation(summary = "Generate adaptive charts", description = "Auto-evaluate data and generate optimal chart configs")
    public ResponseEntity<ApiResponse<AdaptiveChartResponse>> generateAdaptiveCharts(
            @Parameter(description = "Factory ID") @PathVariable String factoryId,
            @RequestBody @Valid AdaptiveChartRequest request) {

        log.info("Generate adaptive charts: factoryId={}, uploadId={}", factoryId, request.getUploadId());

        if (adaptiveChartGenerator == null) {
            return ResponseEntity.ok(ApiResponse.error("Adaptive chart service not configured"));
        }

        try {
            AdaptiveChartResponse response = adaptiveChartGenerator.generateAdaptive(request.getUploadId(), request);
            String message = response.getCharts() != null && !response.getCharts().isEmpty()
                    ? String.format("Generated %d charts", response.getCharts().size())
                    : "Chart generation complete";
            return ResponseEntity.ok(ApiResponse.success(message, response));
        } catch (Exception e) {
            log.error("Generate adaptive charts failed: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("Chart generation failed: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @PostMapping("/generate-chart")
    @Operation(summary = "Quick generate single chart", description = "Generate single chart by specified type")
    public ResponseEntity<ApiResponse<AdaptiveChartResponse.GeneratedChart>> generateSingleChart(
            @Parameter(description = "Factory ID") @PathVariable String factoryId,
            @Parameter(description = "Upload ID") @RequestParam Long uploadId,
            @Parameter(description = "Chart type: bar/line/pie/radar/treemap/waterfall/combo") @RequestParam String chartType,
            @Parameter(description = "Chart purpose description") @RequestParam(required = false) String purpose) {

        log.info("Quick generate chart: factoryId={}, uploadId={}, chartType={}", factoryId, uploadId, chartType);

        if (adaptiveChartGenerator == null) {
            return ResponseEntity.ok(ApiResponse.error("Adaptive chart service not configured"));
        }

        try {
            AdaptiveChartRequest request = AdaptiveChartRequest.builder()
                    .uploadId(uploadId)
                    .evaluateFirst(false)
                    .maxCharts(1)
                    .fusionEnabled(false)
                    .preferredChartType(chartType)
                    .build();

            AdaptiveChartResponse response = adaptiveChartGenerator.generateAdaptive(uploadId, request);

            if (response.getCharts() != null && !response.getCharts().isEmpty()) {
                return ResponseEntity.ok(ApiResponse.success("Chart generated", response.getCharts().get(0)));
            } else {
                return ResponseEntity.ok(ApiResponse.error("Failed to generate chart"));
            }
        } catch (Exception e) {
            log.error("Quick generate chart failed: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("Chart generation failed: " + ErrorSanitizer.sanitize(e)));
        }
    }

    // ==================== Executive Dashboard ====================

    @RequirePermission({"analytics:read"})
    @GetMapping("/dashboard/executive")
    @Operation(summary = "Get executive dashboard", description = "Get comprehensive business analysis dashboard data")
    public ResponseEntity<ApiResponse<DashboardResponse>> getExecutiveDashboard(
            @Parameter(description = "Factory ID") @PathVariable String factoryId,
            @Parameter(description = "Period: today/week/month/quarter/year")
            @RequestParam(defaultValue = "month") String period) {

        log.info("Get executive dashboard: factoryId={}, period={}", factoryId, period);

        try {
            if (smartBIService != null) {
                DashboardResponse dashboard = smartBIService.getExecutiveDashboard(factoryId, period);
                return ResponseEntity.ok(ApiResponse.success(dashboard));
            }

            LocalDate[] dateRange = DateRangeUtils.getDateRangeByPeriod(period);
            DashboardResponse dashboard = salesAnalysisService.getSalesOverview(factoryId, dateRange[0], dateRange[1]);
            return ResponseEntity.ok(ApiResponse.success(dashboard));
        } catch (Exception e) {
            log.error("Get executive dashboard failed: {}", e.getMessage(), e);
            DashboardResponse emptyDashboard = DashboardResponse.builder()
                    .kpiCards(java.util.Collections.emptyList())
                    .charts(java.util.Collections.emptyMap())
                    .rankings(java.util.Collections.emptyMap())
                    .aiInsights(java.util.Collections.emptyList())
                    .suggestions(java.util.Collections.emptyList())
                    .lastUpdated(java.time.LocalDateTime.now())
                    .build();
            return ResponseEntity.ok(ApiResponse.success(emptyDashboard));
        }
    }

    @RequirePermission({"analytics:read"})
    @GetMapping("/dashboard/executive/insights")
    @Operation(summary = "Get LLM insights for dashboard", description = "Async-loaded LLM insights, called after main dashboard renders")
    public ResponseEntity<ApiResponse<java.util.List<AIInsight>>> getDashboardLLMInsights(
            @Parameter(description = "Factory ID") @PathVariable String factoryId,
            @Parameter(description = "Period: today/week/month/quarter/year")
            @RequestParam(defaultValue = "month") String period) {

        log.info("Get dashboard LLM insights: factoryId={}, period={}", factoryId, period);

        try {
            if (smartBIService != null) {
                java.util.List<AIInsight> insights = smartBIService.getDashboardLLMInsights(factoryId, period);
                return ResponseEntity.ok(ApiResponse.success(insights));
            }
            return ResponseEntity.ok(ApiResponse.success(java.util.Collections.emptyList()));
        } catch (Exception e) {
            log.error("Get dashboard LLM insights failed: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.success(java.util.Collections.emptyList()));
        }
    }

    @RequirePermission({"analytics:read"})
    @GetMapping("/dashboard/executive/insights/custom")
    @Operation(summary = "Agent layer LLM insights (custom range)",
               description = "Week 5: AgentOrchestrator returns Gold-backed insights for an arbitrary date range. Falls back to empty list if Python Agent layer is disabled or unreachable.")
    public ResponseEntity<ApiResponse<java.util.List<AIInsight>>> getDashboardLLMInsightsCustomRange(
            @Parameter(description = "Factory ID") @PathVariable String factoryId,
            @Parameter(description = "Start date (yyyy-MM-dd)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "End date (yyyy-MM-dd)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        log.info("Get agent insights custom: factoryId={}, startDate={}, endDate={}",
                factoryId, startDate, endDate);

        try {
            if (smartBIService != null) {
                java.util.List<AIInsight> insights =
                        smartBIService.getDashboardLLMInsightsCustomRange(factoryId, startDate, endDate);
                return ResponseEntity.ok(ApiResponse.success(insights));
            }
            return ResponseEntity.ok(ApiResponse.success(java.util.Collections.emptyList()));
        } catch (Exception e) {
            log.error("Agent insights custom failed: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.success(java.util.Collections.emptyList()));
        }
    }

    /**
     * Phase 9 Apr 24: SSE streaming variant of /insights/custom.
     * Relays Python SSE stream to client. Client uses EventSource to
     * consume tokens as they arrive (~2-3s first byte vs 8-10s full).
     *
     * Response content-type: text/event-stream. Events are JSON per spec
     * in AgentOrchestrator.stream_insight (meta / delta / done / error).
     */
    @RequirePermission({"analytics:read"})
    @GetMapping(value = "/dashboard/executive/insights/custom/stream",
                produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Streaming LLM insights (SSE)",
               description = "Server-sent-events relay to Python AgentOrchestrator. Each data: line is a JSON event.")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter streamInsightsCustom(
            @Parameter(description = "Factory ID") @PathVariable String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        // 120s timeout — longer than Python's 90s read timeout on LLM
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
                new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(120_000L);

        if (agentInsightsClient == null) {
            try {
                emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                        .data("{\"type\":\"error\",\"message\":\"Agent layer not wired\"}"));
            } catch (java.io.IOException ignored) {}
            emitter.complete();
            return emitter;
        }

        // I1 fix (reviewer Apr 24): shared ref to Response + callbacks so
        // timeout/client-disconnect closes OkHttp resource (was leaking).
        final okhttp3.Response[] respRef = new okhttp3.Response[1];
        emitter.onTimeout(() -> { if (respRef[0] != null) respRef[0].close(); emitter.complete(); });
        emitter.onError((t) -> { if (respRef[0] != null) respRef[0].close(); });
        emitter.onCompletion(() -> { if (respRef[0] != null) respRef[0].close(); });

        // Async pump from Python SSE → client SSE
        new Thread(() -> {
            try {
                respRef[0] = agentInsightsClient.streamInsightsCustom(factoryId, startDate, endDate, null);
                okhttp3.Response resp = respRef[0];
                if (resp == null || resp.body() == null) {
                    emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                            .data("{\"type\":\"error\",\"message\":\"upstream unreachable\"}"));
                    emitter.complete();
                    return;
                }
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(resp.body().byteStream(), java.nio.charset.StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    try {
                        emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().data(data));
                    } catch (java.io.IOException ioe) {
                        // Client disconnected — break loop so OkHttp Response gets closed in finally
                        log.debug("SSE client disconnected for factory={}: {}", factoryId, ioe.getMessage());
                        break;
                    }
                }
                emitter.complete();
            } catch (Exception e) {
                log.warn("SSE relay failed for factory={}: {}", factoryId, e.getMessage());
                try {
                    emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                            .data("{\"type\":\"error\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}"));
                } catch (java.io.IOException ignored) {}
                emitter.completeWithError(e);
            } finally {
                if (respRef[0] != null) respRef[0].close();
            }
        }, "sse-relay-" + factoryId).start();

        return emitter;
    }

    @RequirePermission({"analytics:read"})
    @GetMapping("/dashboard/executive/custom")
    @Operation(summary = "Get custom date range dashboard", description = "Executive dashboard with specified date range")
    public ResponseEntity<ApiResponse<DashboardResponse>> getExecutiveDashboardCustomRange(
            @Parameter(description = "Factory ID") @PathVariable String factoryId,
            @Parameter(description = "Start date (yyyy-MM-dd)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "End date (yyyy-MM-dd)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        log.info("Get custom range dashboard: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);

        try {
            DashboardResponse dashboard = salesAnalysisService.getSalesOverview(factoryId, startDate, endDate);
            return ResponseEntity.ok(ApiResponse.success(dashboard));
        } catch (Exception e) {
            log.error("Get custom range dashboard failed: {}", e.getMessage(), e);
            DashboardResponse emptyDashboard = DashboardResponse.builder()
                    .kpiCards(java.util.Collections.emptyList())
                    .charts(java.util.Collections.emptyMap())
                    .rankings(java.util.Collections.emptyMap())
                    .aiInsights(java.util.Collections.emptyList())
                    .suggestions(java.util.Collections.emptyList())
                    .lastUpdated(java.time.LocalDateTime.now())
                    .build();
            return ResponseEntity.ok(ApiResponse.success(emptyDashboard));
        }
    }

    @RequirePermission({"analytics:read"})
    @GetMapping("/dashboard")
    @Operation(summary = "Get unified dashboard", description = "Aggregate all analysis dimensions into one-stop overview")
    public ResponseEntity<ApiResponse<UnifiedDashboardResponse>> getUnifiedDashboard(
            @Parameter(description = "Factory ID") @PathVariable String factoryId,
            @Parameter(description = "Period: today/week/month/quarter/year")
            @RequestParam(defaultValue = "month") String period) {

        log.info("Get unified dashboard: factoryId={}, period={}", factoryId, period);
        long startTime = System.currentTimeMillis();

        try {
            if (smartBIService != null) {
                DashboardResponse dashboard = smartBIService.getExecutiveDashboard(factoryId, period);

                LocalDate[] dateRange = DateRangeUtils.getDateRangeByPeriod(period);
                LocalDate startDate = dateRange[0];
                LocalDate endDate = dateRange[1];

                UnifiedDashboardResponse response = UnifiedDashboardResponse.builder()
                        .period(period)
                        .startDate(startDate)
                        .endDate(endDate)
                        .sales(dashboard)
                        .generatedAt(java.time.LocalDateTime.now())
                        .dataVersion(String.valueOf(System.currentTimeMillis()))
                        .build();

                enrichUnifiedDashboard(response, factoryId, startDate, endDate, period);

                long elapsed = System.currentTimeMillis() - startTime;
                log.info("Unified dashboard generated (via SmartBIService): factoryId={}, period={}, elapsed={}ms", factoryId, period, elapsed);
                return ResponseEntity.ok(ApiResponse.success(response));
            }

            // Fallback
            log.info("SmartBIService not available, using fallback aggregation");
            LocalDate[] dateRange = DateRangeUtils.getDateRangeByPeriod(period);
            LocalDate startDate = dateRange[0];
            LocalDate endDate = dateRange[1];

            UnifiedDashboardResponse response = UnifiedDashboardResponse.builder()
                    .period(period)
                    .startDate(startDate)
                    .endDate(endDate)
                    .build();

            try { response.setSales(salesAnalysisService.getSalesOverview(factoryId, startDate, endDate)); }
            catch (Exception e) { log.warn("Get sales data failed: {}", ErrorSanitizer.sanitize(e)); }

            enrichUnifiedDashboard(response, factoryId, startDate, endDate, period);

            response.setGeneratedAt(java.time.LocalDateTime.now());
            response.setDataVersion(String.valueOf(System.currentTimeMillis()));

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("Unified dashboard generated (fallback): factoryId={}, period={}, elapsed={}ms", factoryId, period, elapsed);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            log.error("Get unified dashboard failed: {}", e.getMessage(), e);
            LocalDate[] fallbackRange = DateRangeUtils.getDateRangeByPeriod(period);
            UnifiedDashboardResponse emptyResponse = UnifiedDashboardResponse.builder()
                    .period(period)
                    .startDate(fallbackRange[0])
                    .endDate(fallbackRange[1])
                    .sales(DashboardResponse.builder()
                            .kpiCards(java.util.Collections.emptyList())
                            .charts(java.util.Collections.emptyMap())
                            .rankings(java.util.Collections.emptyMap())
                            .aiInsights(java.util.Collections.emptyList())
                            .suggestions(java.util.Collections.emptyList())
                            .lastUpdated(java.time.LocalDateTime.now())
                            .build())
                    .generatedAt(java.time.LocalDateTime.now())
                    .dataVersion(String.valueOf(System.currentTimeMillis()))
                    .build();
            return ResponseEntity.ok(ApiResponse.success(emptyResponse));
        }
    }

    // ==================== Lightweight KPI Endpoint ====================

    /**
     * AUDIT-052: Lightweight KPI-only query.
     * Returns only KPI cards without charts or AI insights.
     * Ideal for dashboard loading where only headline numbers are needed.
     */
    @RequirePermission({"analytics:read"})
    @GetMapping("/analysis/dynamic/kpis")
    @Operation(summary = "Get KPIs only", description = "Lightweight endpoint returning only KPI cards, no charts or AI insights")
    public ResponseEntity<ApiResponse<java.util.List<java.util.Map<String, Object>>>> getKPIsOnly(
            @Parameter(description = "Factory ID") @PathVariable String factoryId,
            @Parameter(description = "Upload ID") @RequestParam Long uploadId) {

        log.info("Get KPIs only: factoryId={}, uploadId={}", factoryId, uploadId);

        if (dynamicAnalysisService == null) {
            return ResponseEntity.ok(ApiResponse.error("Dynamic analysis service not enabled"));
        }

        try {
            java.util.List<java.util.Map<String, Object>> kpis =
                    dynamicAnalysisService.getKPIsOnly(factoryId, uploadId);
            return ResponseEntity.ok(ApiResponse.success(kpis));
        } catch (Exception e) {
            log.error("Get KPIs failed: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("Get KPIs failed: " + ErrorSanitizer.sanitize(e)));
        }
    }

    // ==================== Dynamic Data Analysis ====================

    @RequirePermission({"analytics:read"})
    @GetMapping("/analysis/dynamic")
    @Operation(summary = "Dynamic data analysis", description = "Analyze uploaded Excel data, returns KPI cards, charts, and AI insights")
    public ResponseEntity<ApiResponse<DynamicAnalysisService.DashboardResponse>> analyzeDynamicData(
            @Parameter(description = "Factory ID") @PathVariable String factoryId,
            @Parameter(description = "Upload ID") @RequestParam Long uploadId,
            @Parameter(description = "Analysis type: auto/finance/sales/inventory") @RequestParam(defaultValue = "auto") String analysisType,
            @Parameter(description = "Bypass cache (for refresh button)") @RequestParam(defaultValue = "false") boolean forceRefresh) {

        log.info("Dynamic data analysis: factoryId={}, uploadId={}, type={}, forceRefresh={}",
                factoryId, uploadId, analysisType, forceRefresh);

        if (dynamicAnalysisService == null) {
            return ResponseEntity.ok(ApiResponse.error("Dynamic analysis service not enabled, set smartbi.postgres.enabled=true"));
        }

        String cacheType = "dashboard_" + analysisType;  // 'dashboard_auto'

        // Cache lookup — serve from DB cache indefinitely; upload data is
        // immutable (each upload_id is an append-only snapshot) so the
        // analysis result for a given (uploadId, analysisType) never goes
        // stale without a new upload. Force-refresh still triggers recompute.
        // 7-day guardrail in case schema evolves between server upgrades.
        final long CACHE_TTL_SECONDS = 7L * 24 * 60 * 60;
        if (!forceRefresh && analysisResultRepository != null) {
            try {
                var cached = analysisResultRepository.findByUploadIdAndAnalysisType(uploadId, cacheType);
                if (cached.isPresent()) {
                    var row = cached.get();
                    long ageSec = java.time.Duration.between(row.getCreatedAt(), java.time.LocalDateTime.now()).getSeconds();
                    if (ageSec < CACHE_TTL_SECONDS) {
                        DynamicAnalysisService.DashboardResponse cachedResp = cacheObjectMapper.convertValue(
                                row.getAnalysisResult(), DynamicAnalysisService.DashboardResponse.class);
                        log.info("Dashboard cache HIT: uploadId={}, age={}s", uploadId, ageSec);
                        return ResponseEntity.ok(ApiResponse.success(cachedResp));
                    } else {
                        log.info("Dashboard cache EXPIRED: uploadId={}, age={}s (>7d)", uploadId, ageSec);
                    }
                }
            } catch (Exception e) {
                log.warn("Cache lookup failed, falling back to live compute: {}", e.getMessage());
            }
        }

        try {
            DynamicAnalysisService.DashboardResponse result =
                    dynamicAnalysisService.analyzeDynamic(factoryId, uploadId, analysisType);
            if (result == null) {
                return ResponseEntity.ok(ApiResponse.error("Analysis result empty, please verify data was uploaded"));
            }

            // FIX-13 cache write — persist for next refresh (upsert)
            if (analysisResultRepository != null) {
                try {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> resultMap = cacheObjectMapper.convertValue(result, java.util.Map.class);
                    var existing = analysisResultRepository.findByUploadIdAndAnalysisType(uploadId, cacheType);
                    var entity = existing.orElseGet(() ->
                            com.cretas.aims.entity.smartbi.postgres.SmartBiPgAnalysisResult.builder()
                                    .uploadId(uploadId).factoryId(factoryId).analysisType(cacheType).build());
                    entity.setAnalysisResult(resultMap);
                    entity.setCreatedAt(java.time.LocalDateTime.now());  // reset TTL on refresh
                    analysisResultRepository.save(entity);
                    log.info("Dashboard cache WRITE: uploadId={}", uploadId);
                } catch (Exception e) {
                    log.warn("Cache write failed (non-blocking): {}", e.getMessage());
                }
            }

            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            log.error("Dynamic data analysis failed: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("Analysis failed: " + ErrorSanitizer.sanitize(e)));
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Enrich unified dashboard with additional dimension data.
     *
     * RBAC FIX (Jun 2026): Capture RequestAttributes on the request thread before
     * handing off to background threads. GoldFinanceClient.currentUserRole() reads
     * RequestContextHolder (thread-local); child threads spawned by the ForkJoinPool
     * have no request context, so role resolves to null → Python strips revenue → ¥0.
     * We bind the captured attributes into each child thread and remove them on exit
     * to prevent context leaks between pooled-thread reuses.
     */
    private void enrichUnifiedDashboard(UnifiedDashboardResponse response, String factoryId,
                                         LocalDate startDate, LocalDate endDate, String period) {
        final RequestAttributes capturedAttrs = RequestContextHolder.getRequestAttributes();

        // Parallel enrichment — all queries are independent, each with its own try-catch
        java.util.concurrent.CompletableFuture<?>[] futures = {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                if (capturedAttrs != null) RequestContextHolder.setRequestAttributes(capturedAttrs, true);
                try { response.setFinance(financeAnalysisService.getFinanceOverview(factoryId, startDate, endDate)); }
                catch (Exception e) { log.warn("Get finance data failed: {}", ErrorSanitizer.sanitize(e)); }
                finally { if (capturedAttrs != null) RequestContextHolder.resetRequestAttributes(); }
            }),
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                if (capturedAttrs != null) RequestContextHolder.setRequestAttributes(capturedAttrs, true);
                try { response.setInventory(inventoryHealthAnalysisService.getInventoryHealth(factoryId, startDate, endDate)); }
                catch (Exception e) { log.warn("Get inventory data failed: {}", ErrorSanitizer.sanitize(e)); }
                finally { if (capturedAttrs != null) RequestContextHolder.resetRequestAttributes(); }
            }),
            // production / quality intentionally omitted here. The Java
            // Production/QualityAnalysisService were random-mock placeholders
            // (new Random(factoryId.hashCode())) that fabricated fake numbers on
            // the customer dashboard. Removed so these fields stay honestly empty
            // (null) until enrich is migrated to read the Python analysis endpoints
            // backed by real gold (agg_factory_batch_daily). See SmartBI consolidation plan.
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                if (capturedAttrs != null) RequestContextHolder.setRequestAttributes(capturedAttrs, true);
                try { response.setProcurement(procurementAnalysisService.getProcurementOverview(factoryId, startDate, endDate)); }
                catch (Exception e) { log.warn("Get procurement data failed: {}", ErrorSanitizer.sanitize(e)); }
                finally { if (capturedAttrs != null) RequestContextHolder.resetRequestAttributes(); }
            }),
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                if (capturedAttrs != null) RequestContextHolder.setRequestAttributes(capturedAttrs, true);
                try { response.setDepartmentRanking(departmentAnalysisService.getDepartmentRanking(factoryId, startDate, endDate)); }
                catch (Exception e) { log.warn("Get department ranking failed: {}", ErrorSanitizer.sanitize(e)); }
                finally { if (capturedAttrs != null) RequestContextHolder.resetRequestAttributes(); }
            }),
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                if (capturedAttrs != null) RequestContextHolder.setRequestAttributes(capturedAttrs, true);
                try { response.setRegionRanking(regionAnalysisService.getRegionRanking(factoryId, startDate, endDate)); }
                catch (Exception e) { log.warn("Get region ranking failed: {}", ErrorSanitizer.sanitize(e)); }
                finally { if (capturedAttrs != null) RequestContextHolder.resetRequestAttributes(); }
            }),
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                if (capturedAttrs != null) RequestContextHolder.setRequestAttributes(capturedAttrs, true);
                try {
                    DateRangeUtils.DateRange range = DateRangeUtils.rangeByPeriod(period);
                    response.setAlerts(recommendationService.generateAllAlerts(factoryId, range));
                } catch (Exception e) { log.warn("Get alerts failed: {}", ErrorSanitizer.sanitize(e)); }
                finally { if (capturedAttrs != null) RequestContextHolder.resetRequestAttributes(); }
            }),
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                if (capturedAttrs != null) RequestContextHolder.setRequestAttributes(capturedAttrs, true);
                try { response.setRecommendations(recommendationService.generateRecommendations(factoryId, "all")); }
                catch (Exception e) { log.warn("Get recommendations failed: {}", ErrorSanitizer.sanitize(e)); }
                finally { if (capturedAttrs != null) RequestContextHolder.resetRequestAttributes(); }
            })
        };
        try {
            java.util.concurrent.CompletableFuture.allOf(futures).join();
        } catch (Exception e) {
            log.warn("Some enrichment tasks failed: {}", e.getMessage());
        }
    }
}
