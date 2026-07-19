package com.cretas.aims.client;

import com.cretas.aims.config.smartbi.PythonSmartBIConfig;
import com.cretas.aims.dev.faultinjection.PythonClientFaultInjector;
import com.cretas.aims.dto.python.*;
import com.cretas.aims.dto.python.ClassifierRequest;
import com.cretas.aims.dto.python.ClassifierResponse;
import com.cretas.aims.dto.python.ClassifierBatchResponse;
import com.cretas.aims.dto.python.PythonAnalysisRequest;
import com.cretas.aims.dto.python.PythonAnalysisResponse;
import com.cretas.aims.dto.smartbi.ExcelParseResponse;
import com.cretas.aims.dto.smartbi.PythonForecastResponse;
import com.cretas.aims.dto.smartbi.MetricResult;
import com.cretas.aims.exception.PythonServiceUnavailableException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;
import java.io.FilterInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Python SmartBI 服务客户端
 *
 * 提供与 Python SmartBI 服务的 HTTP 通信，支持：
 * - Excel 解析
 * - 指标计算
 * - 预测分析
 * - 服务健康检查
 *
 * 包含重试机制和健康状态缓存，避免频繁调用不可用的服务。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-24
 */
@Slf4j
@Component
public class PythonSmartBIClient {

    private final PythonSmartBIConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final PythonServiceCircuitBreaker circuitBreaker;
    private final String internalSecret;
    private final HttpUrl serviceBaseUrl;

    /**
     * F4 fault-injection hook (optional). Bean exists only under
     * {@code dev-fault-injection} Spring profile; remains {@code null} in prod.
     * Referenced inside the OkHttp interceptor below.
     */
    @Autowired(required = false)
    private PythonClientFaultInjector pythonClientFaultInjector;

    // 服务可用性状态
    private final AtomicBoolean serviceAvailable = new AtomicBoolean(false);
    private final AtomicLong lastHealthCheck = new AtomicLong(0);

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final long MAX_JSON_RESPONSE_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_GENERAL_ANALYSIS_REQUEST_BYTES = 8L * 1024L * 1024L;
    private static final long MAX_SSE_RESPONSE_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_SSE_EVENT_BYTES = 1024 * 1024;
    private static final long MAX_ERROR_INSPECTION_BYTES = 8L * 1024L;
    private static final Set<String> ALLOWED_GENERAL_ANALYSIS_EVENTS = Set.of(
            "status", "chunk", "charts", "done", "error");
    private static final Set<String> ALLOWED_FINANCIAL_DASHBOARD_ENDPOINTS = Set.of(
            "/generate", "/export-ppt");
    private static final Pattern INTERNAL_IDENTITY =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    private static final Set<String> ALLOWED_RESTAURANT_SECTIONS = Set.of(
            "cost_rigidity", "diagnostics", "expense_breakdown", "benchmark_alerts",
            "channel_margin", "dining_heatmap", "long_tail_sku", "menu_normalization",
            "temporal_comparison", "review_analysis", "member_rfm", "stored_value",
            "multi_store_comparison", "calibration_history", "store_pnl_one_pager",
            "bom_layer_status", "shrinkage_analysis", "department_pnl", "menu_engineering",
            "monthly_ppt_export", "cross_chain_benchmark", "restaurant_forecast",
            "bom_variance", "labor_productivity", "sales_plan_tracking", "seat_occupancy",
            "combo_split", "return_anomaly", "review_competitive", "smart_reorder",
            "daily_reconciliation", "procurement_forecast", "shift_analysis", "piecework_calc",
            "performance_eval", "store_kpi_dashboard", "value_summary",
            "advanced_traffic_persona", "boss_decision_brief");

    public PythonSmartBIClient(PythonSmartBIConfig config,
                               @Qualifier("aiServiceHttpClient") OkHttpClient baseHttpClient,
                               ObjectMapper objectMapper,
                               PythonServiceCircuitBreaker circuitBreaker,
                               @Qualifier("pythonAiInternalSecret") String internalSecret) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.circuitBreaker = circuitBreaker;
        this.internalSecret = internalSecret;
        this.serviceBaseUrl = requireServiceBaseUrl(config.getUrl());

        // 为 Python SmartBI 服务创建专用的 HttpClient
        // 添加拦截器：所有 Java→Python 内部调用自动带 X-Internal-Secret 头
        this.httpClient = baseHttpClient.newBuilder()
                .connectTimeout(config.getConnectTimeout(), TimeUnit.MILLISECONDS)
                .readTimeout(config.getTimeout(), TimeUnit.MILLISECONDS)
                .writeTimeout(config.getTimeout(), TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .addInterceptor(chain -> {
                    Request original = chain.request();
                    requireSameServiceAuthority(original.url());
                    // F4 fault-injection (dev-fault-injection profile only):
                    // throw IOException to simulate Python unreachable.
                    // Field-read inside lambda is fine because @Autowired field
                    // is set by Spring before any HTTP call fires (the bean is
                    // wrapped in a CGLIB proxy and httpClient is not called until
                    // post-construction).
                    if (pythonClientFaultInjector != null) {
                        pythonClientFaultInjector.maybeThrowUnreachable(original.url().encodedPath());
                    }
                    if (this.internalSecret == null || this.internalSecret.isBlank()) {
                        throw new IOException("INTERNAL_API_SECRET not configured; cannot call Python SmartBI");
                    }
                    Request.Builder builder = original.newBuilder()
                            .header("X-Internal-Secret", this.internalSecret);
                    if (original.header("Accept") == null) {
                        builder.header("Accept", "application/json");
                    }
                    // Propagate correlation ID from MDC to outgoing Python calls
                    String correlationId = MDC.get("correlationId");
                    if (correlationId != null) {
                        builder.header("X-Correlation-ID", correlationId);
                    }
                    return chain.proceed(builder.build());
                })
                .build();
    }

    @PostConstruct
    public void init() {
        if (config.isEnabled()) {
            log.info("Python SmartBI 客户端初始化: url={}", config.getUrl());
            // 异步检查服务可用性
            checkAvailabilityAsync();
        } else {
            log.info("Python SmartBI 服务已禁用");
        }
    }

    /**
     * 检查 Python 服务是否可用
     *
     * 使用缓存机制避免频繁检查，健康检查间隔由配置决定。
     *
     * @return true 如果服务可用且响应正常
     */
    public boolean isAvailable() {
        if (!config.isEnabled()) {
            return false;
        }

        long now = System.currentTimeMillis();
        long lastCheck = lastHealthCheck.get();

        // 使用缓存的健康状态
        if (now - lastCheck < config.getHealthCheckInterval()) {
            return serviceAvailable.get();
        }

        // 需要重新检查
        return checkAvailability();
    }

    /**
     * 异步检查服务可用性
     */
    private void checkAvailabilityAsync() {
        new Thread(() -> {
            try {
                checkAvailability();
            } catch (Exception e) {
                log.warn("Python SmartBI 服务健康检查失败: {}", e.getMessage());
            }
        }, "python-smartbi-health-check").start();
    }

    /**
     * 同步检查服务可用性
     */
    private boolean checkAvailability() {
        try {
            Request request = new Request.Builder()
                    .url(serviceBaseUrl.newBuilder().addPathSegment("health").build())
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                boolean available = response.isSuccessful()
                        && readJsonBody(response.body(), PythonServiceHealthResponse.class).isHealthy();
                serviceAvailable.set(available);
                lastHealthCheck.set(System.currentTimeMillis());

                if (available) {
                    log.debug("Python SmartBI 服务健康检查通过");
                } else {
                    log.warn("Python SmartBI 服务健康检查失败: status={}", response.code());
                }

                return available;
            }
        } catch (IOException e) {
            serviceAvailable.set(false);
            lastHealthCheck.set(System.currentTimeMillis());
            log.warn("Python SmartBI 服务不可达: {}", e.getMessage());
            return false;
        }
    }

    // ==================== Excel 解析 ====================

    /**
     * 使用 Python 服务解析 Excel 文件
     *
     * @param file      Excel 文件
     * @param factoryId 工厂ID
     * @param dataType  数据类型提示
     * @return 解析结果
     * @throws IOException 如果请求失败
     */
    public ExcelParseResponse parseExcel(MultipartFile file, String factoryId, String dataType) throws IOException {
        return parseExcel(file, factoryId, dataType, 0, 1);
    }

    /**
     * 使用 Python 服务解析 Excel 文件（带 sheet 和 header 参数）
     *
     * @param file       Excel 文件
     * @param factoryId  工厂ID
     * @param dataType   数据类型提示
     * @param sheetIndex Sheet 索引（0-based）
     * @param headerRows 表头行数
     * @return 解析结果
     * @throws IOException 如果请求失败
     */
    /**
     * 调用 Python /auto-parse 端点解析 Excel
     *
     * 注意：/auto-parse 使用 StructureDetector 自动检测表头结构，
     * headerRows 参数会被忽略（保留参数是为了向后兼容）
     *
     * @param file        Excel 文件
     * @param factoryId   工厂 ID
     * @param dataType    数据类型
     * @param sheetIndex  Sheet 索引 (从 0 开始)
     * @param headerRows  [已废弃] 表头行数（Python auto-parse 会忽略此参数）
     * @return 解析结果
     */
    public ExcelParseResponse parseExcel(MultipartFile file, String factoryId, String dataType,
                                          int sheetIndex, int headerRows) throws IOException {
        return parseExcel(file, factoryId, dataType, sheetIndex, headerRows, null, null);
    }

    /**
     * Bug #25b (2026-04-18): overload accepting multi-stacked-table region bounds.
     * When both start/end are non-null, the Python pipeline crops to that region.
     */
    public ExcelParseResponse parseExcel(MultipartFile file, String factoryId, String dataType,
                                          int sheetIndex, int headerRows,
                                          Integer selectedRegionStart, Integer selectedRegionEnd) throws IOException {
        log.info("调用 Python SmartBI /auto-parse: fileName={}, factoryId={}, dataType={}, sheetIndex={}, size={}MB (headerRows={} 将被忽略), region=[{},{}]",
                file.getOriginalFilename(), factoryId, dataType, sheetIndex,
                file.getSize() / 1024 / 1024, headerRows, selectedRegionStart, selectedRegionEnd);

        // 流式传输 — 避免 file.getBytes() 对 60MB CSV 触发 OOM (BUG-2, 2026-04-15).
        // Copy to a stable temp File so executeWithRetry can re-read on retry
        // (MultipartFile.getInputStream() can return exhausted stream on 2nd attempt).
        final String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload.bin";
        final File tempFile = java.io.File.createTempFile("cretas-upload-", "-" + filename);
        tempFile.deleteOnExit();
        file.transferTo(tempFile);
        final MediaType mediaType = MediaType.parse(guessMimeFromName(filename));
        RequestBody fileBody = RequestBody.create(mediaType, tempFile);

        // 注意：/auto-parse 端点使用 StructureDetector 自动检测，不需要 header_rows
        MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.getOriginalFilename(), fileBody)
                .addFormDataPart("sheetIndex", String.valueOf(sheetIndex));

        // 添加可选的 factory_id 用于自定义字段映射
        if (factoryId != null && !factoryId.isEmpty()) {
            bodyBuilder.addFormDataPart("factory_id", factoryId);
        }

        // Bug #25b: forward region bounds when the user picked a specific stacked table.
        if (selectedRegionStart != null && selectedRegionEnd != null) {
            bodyBuilder.addFormDataPart("selectedRegionStart", String.valueOf(selectedRegionStart));
            bodyBuilder.addFormDataPart("selectedRegionEnd", String.valueOf(selectedRegionEnd));
        }

        Request request = new Request.Builder()
                .url(config.getParseExcelUrl())
                .post(bodyBuilder.build())
                .build();

        return executeWithRetry(request, ExcelParseResponse.class);
    }

    // ==================== 大文件异步解析 ====================

    public static final long LARGE_FILE_ASYNC_BYTES = 50L * 1024 * 1024; // 50MB
    private static final int ASYNC_POLL_INTERVAL_MS = 3_000;
    private static final int ASYNC_POLL_MAX_ATTEMPTS = 200; // 200 × 3s = 10 min

    /**
     * 对大文件（> 50MB）透明切换到 Python 异步解析路径。
     * 调用方不感知：接口与 parseExcel 相同，返回同样的 ExcelParseResponse。
     * 流程：POST /auto-parse-async → 202 uploadId → 轮询 status 至 COMPLETED。
     */
    @SuppressWarnings("unchecked")
    public ExcelParseResponse parseExcelViaAsync(MultipartFile file, String factoryId,
                                                  int sheetIndex,
                                                  Integer selectedRegionStart,
                                                  Integer selectedRegionEnd) throws IOException {
        final String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload.bin";
        String trustedFactoryId = requireFactoryId(factoryId);
        log.info("[async-parse] 大文件 {}MB, 启动异步解析: file={} factory={}",
                file.getSize() / 1024 / 1024, filename, factoryId);

        // 1. 写临时文件（OkHttp 重试安全）
        final File tempFile = java.io.File.createTempFile("cretas-async-", "-" + filename);
        tempFile.deleteOnExit();
        file.transferTo(tempFile);

        // 2. POST /api/smartbi/excel/auto-parse-async
        MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", filename, RequestBody.create(MediaType.parse(guessMimeFromName(filename)), tempFile))
                .addFormDataPart("factory_id", trustedFactoryId)
                .addFormDataPart("sheetIndex", String.valueOf(sheetIndex))
                .addFormDataPart("max_rows", "500000");
        if (selectedRegionStart != null) bodyBuilder.addFormDataPart("selected_region_start", String.valueOf(selectedRegionStart));
        if (selectedRegionEnd != null)   bodyBuilder.addFormDataPart("selected_region_end",   String.valueOf(selectedRegionEnd));

        Request asyncReq = new Request.Builder()
                .url(config.getUrl() + "/api/smartbi/excel/auto-parse-async")
                .header("X-Factory-Id", trustedFactoryId)
                .post(bodyBuilder.build())
                .build();

        Map<String, Object> asyncResp;
        try (Response resp = httpClient.newCall(asyncReq).execute()) {
            if (resp.code() != 202 && resp.code() != 200) {
                inspectErrorBodyBounded(resp.body());
                log.error("[async-parse] POST failed: HTTP {}", resp.code());
                ExcelParseResponse err = new ExcelParseResponse();
                err.setSuccess(false);
                err.setErrorMessage("异步上传失败: HTTP " + resp.code());
                return err;
            }
            asyncResp = readJsonBody(resp.body(), Map.class);
        }

        Object uploadIdRaw = asyncResp.get("uploadId");
        if (uploadIdRaw == null) {
            ExcelParseResponse err = new ExcelParseResponse();
            err.setSuccess(false);
            err.setErrorMessage("异步上传未返回 uploadId");
            return err;
        }
        int uploadId = ((Number) uploadIdRaw).intValue();
        log.info("[async-parse] uploadId={}, 开始轮询 (间隔 {}s, 最多 {}次)",
                uploadId, ASYNC_POLL_INTERVAL_MS / 1000, ASYNC_POLL_MAX_ATTEMPTS);

        // 3. 轮询 /auto-parse-status/{uploadId}; tenant identity is header-only.
        String pollUrl = config.getUrl() + "/api/smartbi/excel/auto-parse-status/" + uploadId;
        for (int attempt = 0; attempt < ASYNC_POLL_MAX_ATTEMPTS; attempt++) {
            try { Thread.sleep(ASYNC_POLL_INTERVAL_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }

            Request pollReq = new Request.Builder()
                    .url(pollUrl)
                    .header("X-Factory-Id", trustedFactoryId)
                    .get()
                    .build();
            Map<String, Object> status;
            try (Response resp = httpClient.newCall(pollReq).execute()) {
                if (!resp.isSuccessful()) {
                    inspectErrorBodyBounded(resp.body());
                    log.warn("[async-parse] poll attempt {} HTTP {}", attempt, resp.code());
                    continue;
                }
                status = readJsonBody(resp.body(), Map.class);
            }

            String st = (String) status.get("status");
            log.debug("[async-parse] uploadId={} attempt={} status={}", uploadId, attempt, st);

            if ("COMPLETED".equals(st)) {
                log.info("[async-parse] uploadId={} COMPLETED after {}s",
                        uploadId, (attempt + 1) * ASYNC_POLL_INTERVAL_MS / 1000);
                return buildParseResponseFromAsyncStatus(status, uploadId);
            }
            if ("FAILED".equals(st)) {
                String errMsg = (String) status.getOrDefault("error", "异步解析失败");
                log.error("[async-parse] uploadId={} FAILED: {}", uploadId, errMsg);
                ExcelParseResponse err = new ExcelParseResponse();
                err.setSuccess(false);
                err.setErrorMessage(errMsg);
                return err;
            }
        }

        log.error("[async-parse] uploadId={} poll timed out after {}min", uploadId,
                (long) ASYNC_POLL_MAX_ATTEMPTS * ASYNC_POLL_INTERVAL_MS / 60_000);
        ExcelParseResponse err = new ExcelParseResponse();
        err.setSuccess(false);
        err.setErrorMessage("大文件解析超时（10 分钟），请尝试拆分后上传");
        return err;
    }

    @SuppressWarnings("unchecked")
    private ExcelParseResponse buildParseResponseFromAsyncStatus(Map<String, Object> status, int uploadId) {
        ExcelParseResponse pr = new ExcelParseResponse();
        pr.setSuccess(true);
        pr.setUploadId((long) uploadId);
        pr.setRowCount(status.get("rowCount") instanceof Number n ? n.intValue() : null);
        pr.setColumnCount(status.get("columnCount") instanceof Number n ? n.intValue() : null);

        Object headersRaw = status.get("headers");
        if (headersRaw instanceof List<?> hl) {
            pr.setHeaders(hl.stream().map(Object::toString).collect(java.util.stream.Collectors.toList()));
        }

        Object fmRaw = status.get("fieldMappings");
        if (fmRaw instanceof List<?> fml) {
            List<com.cretas.aims.dto.smartbi.FieldMappingResult> mappings = new java.util.ArrayList<>();
            for (Object fmObj : fml) {
                if (fmObj instanceof Map<?, ?> fmMap) {
                    com.cretas.aims.dto.smartbi.FieldMappingResult fm = new com.cretas.aims.dto.smartbi.FieldMappingResult();
                    Object orig = fmMap.get("originalColumn"); if (orig != null) fm.setOriginalColumn(orig.toString());
                    Object std  = fmMap.get("standardField");  if (std  != null) fm.setStandardField(std.toString());
                    Object conf = fmMap.get("confidence");      if (conf instanceof Number cn) fm.setConfidence(cn.doubleValue());
                    mappings.add(fm);
                }
            }
            pr.setFieldMappings(mappings);
        }

        Object previewRaw = status.get("previewData");
        if (previewRaw instanceof List<?> pvl) {
            List<Map<String, Object>> preview = new java.util.ArrayList<>();
            for (Object row : pvl) { if (row instanceof Map<?, ?> rm) preview.add((Map<String, Object>) rm); }
            pr.setPreviewData(preview);
        }

        Object tableType = status.get("detectedTableType");
        if (tableType != null) pr.setTableType(tableType.toString());
        return pr;
    }

    // ==================== General analysis and health ====================
    /**
     * Calls the exact Python root health route and parses its typed JSON
     * contract. HTTP success alone is not a healthy result.
     */
    public PythonServiceHealthResponse health() throws IOException {
        Request request = new Request.Builder()
                .url(serviceBaseUrl.newBuilder().addPathSegment("health").build())
                .get()
                .build();
        try {
            return executeWithRetry(request, PythonServiceHealthResponse.class);
        } catch (IOException | PythonServiceUnavailableException failure) {
            throw new IOException("Python SmartBI health check is unavailable");
        }
    }

    /**
     * Calls Python general analysis with trusted identities in headers only.
     * POST requests are never retried by {@link #executeWithRetry(Request, Class)}.
     */
    public PythonGeneralAnalysisResponse analyzeGeneral(
            String factoryId,
            String userId,
            PythonGeneralAnalysisRequest analysisRequest) throws IOException {
        if (analysisRequest == null) {
            throw new IOException("Python SmartBI general analysis request is invalid");
        }
        GeneralAnalysisCall call;
        try {
            call = new GeneralAnalysisCall(
                    analysisRequest.getMessage(),
                    null,
                    null,
                    analysisRequest.getSessionId(),
                    analysisRequest.getEnableThinking(),
                    analysisRequest.getThinkingBudget(),
                    analysisRequest.getAllowTenantDataFallback());
        } catch (IllegalArgumentException invalidRequest) {
            throw new IOException("Python SmartBI general analysis request is invalid");
        }
        return analyzeGeneral(factoryId, userId, null, call);
    }

    /**
     * Typed, tenant-bound general-analysis call for Java callers that provide
     * explicit query/data context. Trusted identities are header-only.
     */
    public PythonGeneralAnalysisResponse analyzeGeneral(
            String factoryId,
            String userId,
            String userRole,
            GeneralAnalysisCall analysisRequest) throws IOException {
        if (analysisRequest == null) {
            throw new IOException("Python SmartBI general analysis request is invalid");
        }
        final String trustedFactoryId;
        final String trustedUserId;
        final String trustedUserRole;
        try {
            trustedFactoryId = requireFactoryId(factoryId);
            trustedUserId = optionalIdentity(userId);
            trustedUserRole = optionalIdentity(userRole);
        } catch (IOException invalidIdentity) {
            throw new IOException("Python SmartBI general analysis request is invalid");
        }

        HttpUrl url = serviceBaseUrl.newBuilder()
                .addPathSegments("api/chat/general-analysis")
                .build();
        try {
            String requestJson = serializeBoundedGeneralAnalysisRequest(analysisRequest);
            Request.Builder builder = new Request.Builder()
                    .url(url)
                    .header("X-Factory-Id", trustedFactoryId)
                    .post(RequestBody.create(JSON, requestJson));
            if (trustedUserId != null) {
                builder.header("X-User-Id", trustedUserId);
            }
            if (trustedUserRole != null) {
                builder.header("X-User-Role", trustedUserRole);
            }

            PythonGeneralAnalysisResponse response = executeWithRetry(
                    builder.build(), PythonGeneralAnalysisResponse.class);
            if (response == null || !response.isSuccess() || !response.hasAnalysis()) {
                throw new IOException("Python SmartBI returned an invalid general analysis response");
            }
            return response;
        } catch (IOException | PythonServiceUnavailableException failure) {
            throw new IOException("Python SmartBI general analysis is unavailable");
        }
    }

    /**
     * Streams the fixed Python general-analysis SSE route exactly once. The
     * transport validates content type, event names, JSON framing, total/event
     * size, and requires a non-empty successful {@code done} event.
     */
    public void streamGeneralAnalysis(
            String factoryId,
            String userId,
            String userRole,
            GeneralAnalysisCall analysisRequest,
            GeneralAnalysisEventConsumer consumer) throws IOException {
        if (analysisRequest == null || consumer == null) {
            throw new IOException("Python SmartBI general analysis stream request is invalid");
        }
        final String trustedFactoryId;
        final String trustedUserId;
        final String trustedUserRole;
        try {
            trustedFactoryId = requireFactoryId(factoryId);
            trustedUserId = optionalIdentity(userId);
            trustedUserRole = optionalIdentity(userRole);
        } catch (IOException invalidIdentity) {
            throw new IOException("Python SmartBI general analysis stream request is invalid");
        }

        HttpUrl url = serviceBaseUrl.newBuilder()
                .addPathSegments("api/chat/general-analysis-stream")
                .build();
        String requestJson = serializeBoundedGeneralAnalysisRequest(analysisRequest);
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Accept", "text/event-stream")
                .header("X-Factory-Id", trustedFactoryId)
                .post(RequestBody.create(JSON, requestJson));
        if (trustedUserId != null) {
            builder.header("X-User-Id", trustedUserId);
        }
        if (trustedUserRole != null) {
            builder.header("X-User-Role", trustedUserRole);
        }

        if (!circuitBreaker.isCallPermitted()) {
            throw new IOException("Python SmartBI general analysis stream is unavailable");
        }
        try {
            executeGeneralAnalysisStream(builder.build(), consumer);
            circuitBreaker.recordSuccess();
        } catch (GeneralAnalysisConsumerException disconnectedConsumer) {
            // The Python transport may be healthy while the browser/client has
            // disconnected from Java's SseEmitter. Do not poison the Python
            // service circuit breaker with a downstream delivery failure. A
            // successful Python event also resolves the probe acquired by
            // isCallPermitted(), which is essential in HALF_OPEN.
            circuitBreaker.recordSuccess();
            throw new IOException("General analysis stream delivery was interrupted");
        } catch (IOException | RuntimeException failure) {
            circuitBreaker.recordFailure();
            serviceAvailable.set(false);
            throw new IOException("Python SmartBI general analysis stream is unavailable");
        }
    }

    private String serializeBoundedGeneralAnalysisRequest(GeneralAnalysisCall request)
            throws IOException {
        String requestJson = objectMapper.writeValueAsString(request);
        if (requestJson.getBytes(StandardCharsets.UTF_8).length > MAX_GENERAL_ANALYSIS_REQUEST_BYTES) {
            throw new IOException("Python SmartBI general analysis request exceeded the configured limit");
        }
        return requestJson;
    }

    private void executeGeneralAnalysisStream(
            Request request,
            GeneralAnalysisEventConsumer consumer) throws IOException {
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                inspectErrorBodyBounded(response.body());
                throw sanitizedUpstreamFailure(response.code());
            }
            ResponseBody responseBody = response.body();
            requireEventStreamResponse(responseBody);
            BoundedInputStream in = boundedStream(responseBody, MAX_SSE_RESPONSE_BYTES);
            boolean completed = false;
            boolean hasAnalysis = false;
            String eventName = null;
            StringBuilder eventData = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        eventName = line.substring("event:".length()).trim();
                    } else if (line.startsWith("data:")) {
                        if (eventData.length() > 0) {
                            eventData.append('\n');
                        }
                        eventData.append(line.substring("data:".length()).stripLeading());
                        if (eventData.length() > MAX_SSE_EVENT_BYTES) {
                            throw new IOException("Python SmartBI SSE event exceeded the configured limit");
                        }
                    } else if (line.isEmpty() && (eventName != null || eventData.length() > 0)) {
                        GeneralAnalysisStreamEvent event = parseGeneralAnalysisEvent(eventName, eventData);
                        if ("error".equals(event.event())) {
                            throw new IOException("Python SmartBI general analysis stream failed");
                        }
                        if ("chunk".equals(event.event())
                                && event.data().isTextual()
                                && !event.data().asText().isBlank()) {
                            hasAnalysis = true;
                        }
                        if ("done".equals(event.event())) {
                            JsonNode answer = event.data().path("answer");
                            boolean success = event.data().path("success").asBoolean(false);
                            if (!success || !answer.isTextual() || answer.asText().isBlank()) {
                                throw new IOException("Python SmartBI returned an invalid stream completion");
                            }
                            hasAnalysis = true;
                            completed = true;
                        }
                        try {
                            consumer.onEvent(event);
                        } catch (IOException | RuntimeException deliveryFailure) {
                            throw new GeneralAnalysisConsumerException();
                        }
                        eventName = null;
                        eventData.setLength(0);
                    }
                }
                if (eventName != null || eventData.length() > 0) {
                    throw new IOException("Python SmartBI returned an incomplete SSE event");
                }
                in.drainToEnd();
            } finally {
                in.closeDelegate();
            }
            if (!completed || !hasAnalysis) {
                throw new IOException("Python SmartBI stream ended without a valid completion");
            }
        }
    }

    private GeneralAnalysisStreamEvent parseGeneralAnalysisEvent(
            String rawEventName,
            StringBuilder rawData) throws IOException {
        String eventName = rawEventName == null ? "message" : rawEventName;
        if (!ALLOWED_GENERAL_ANALYSIS_EVENTS.contains(eventName) || rawData.length() == 0) {
            throw new IOException("Python SmartBI returned an unsupported SSE event");
        }
        JsonNode data = objectMapper.readTree(rawData.toString());
        if (data == null) {
            throw new IOException("Python SmartBI returned an invalid SSE event");
        }
        return new GeneralAnalysisStreamEvent(eventName, data);
    }

    private static void requireEventStreamResponse(ResponseBody responseBody) throws IOException {
        if (responseBody == null) {
            throw new IOException("Python SmartBI returned an empty stream");
        }
        MediaType contentType = responseBody.contentType();
        if (contentType == null
                || !"text".equalsIgnoreCase(contentType.type())
                || !"event-stream".equalsIgnoreCase(contentType.subtype())) {
            throw new IOException("Python SmartBI returned an unsupported stream response");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GeneralAnalysisCall(
            String query,
            List<Map<String, Object>> data,
            @JsonProperty("table_type") String tableType,
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("enable_thinking") Boolean enableThinking,
            @JsonProperty("thinking_budget") Integer thinkingBudget,
            @JsonProperty("allow_tenant_data_fallback") Boolean allowTenantDataFallback) {

        public GeneralAnalysisCall {
            if (query == null || query.isBlank() || query.length() > 65_536) {
                throw new IllegalArgumentException("query is required");
            }
            data = data == null ? null : List.copyOf(data);
            if (thinkingBudget != null && (thinkingBudget < 0 || thinkingBudget > 100_000)) {
                throw new IllegalArgumentException("thinking budget is invalid");
            }
        }
    }

    public record GeneralAnalysisStreamEvent(String event, JsonNode data) {
    }

    @FunctionalInterface
    public interface GeneralAnalysisEventConsumer {
        void onEvent(GeneralAnalysisStreamEvent event) throws IOException;
    }

    private static final class GeneralAnalysisConsumerException extends IOException {
        private GeneralAnalysisConsumerException() {
            super("General analysis stream consumer disconnected");
        }
    }

    // ==================== 指标计算 ====================

    /**
     * 使用 Python 服务计算指标
     *
     * @param metricCode    指标代码
     * @param data          数据列表
     * @param fieldMappings 字段映射
     * @return 指标计算结果
     * @throws IOException 如果请求失败
     */
    public MetricResult calculateMetric(String metricCode, List<Map<String, Object>> data,
                                        Map<String, String> fieldMappings) throws IOException {
        log.info("调用 Python SmartBI 计算指标: metricCode={}, dataSize={}", metricCode, data.size());

        Map<String, Object> requestBody = Map.of(
                "metricCode", metricCode,
                "data", data,
                "fieldMappings", fieldMappings
        );

        Request request = new Request.Builder()
                .url(config.getCalculateMetricsUrl())
                .post(RequestBody.create(JSON, objectMapper.writeValueAsString(requestBody)))
                .build();

        return executeWithRetry(request, MetricResult.class);
    }

    /**
     * 批量计算指标
     *
     * @param data          数据列表
     * @param fieldMappings 字段映射
     * @return 指标计算结果列表
     * @throws IOException 如果请求失败
     */
    public List<MetricResult> calculateAllMetrics(List<Map<String, Object>> data,
                                                   Map<String, String> fieldMappings) throws IOException {
        log.info("调用 Python SmartBI 批量计算指标: dataSize={}", data.size());

        Map<String, Object> requestBody = Map.of(
                "data", data,
                "fieldMappings", fieldMappings
        );

        Request request = new Request.Builder()
                .url(config.getCalculateAllMetricsUrl())
                .post(RequestBody.create(JSON, objectMapper.writeValueAsString(requestBody)))
                .build();

        return executeWithRetry(request, new TypeReference<List<MetricResult>>() {});
    }

    // ==================== 预测分析 ====================

    /**
     * 方案 E (2026-04-17): Java 查历史数据后调用 Python 纯算端点。
     *
     * <p>Python /api/forecast/predict 的 pydantic 契约要求 data 字段为 List[float], min_items=3.
     * 调用方应在 Java 侧通过 Repository SQL GROUP BY 组装 data 后再调。
     *
     * @param data      历史数据序列 (至少 3 个点，调用方预检)
     * @param periods   预测期数
     * @param algorithm 算法名 (小写，如 "auto"/"moving_average"/"linear_trend"/"exponential_smoothing")
     * @return Python 原始响应结构
     * @throws IOException 网络失败或 Python 返回非 2xx
     */
    public PythonForecastResponse forecastWithData(List<Double> data, int periods, String algorithm) throws IOException {
        log.info("调用 Python forecast (方案 E): dataPoints={}, periods={}, algorithm={}",
                data.size(), periods, algorithm);

        Map<String, Object> requestBody = new java.util.HashMap<>();
        requestBody.put("data", data);
        requestBody.put("algorithm", algorithm != null ? algorithm : "auto");
        requestBody.put("periods", periods);
        requestBody.put("confidenceLevel", 0.95);

        Request request = new Request.Builder()
                .url(config.getForecastUrl())
                .post(RequestBody.create(JSON, objectMapper.writeValueAsString(requestBody)))
                .build();

        return executeWithRetry(request, PythonForecastResponse.class);
    }

    // ==================== 通用请求执行 ====================

    /**
     * 执行请求并带重试机制 + 熔断器保护
     */
    private <T> T executeWithRetry(Request request, Class<T> responseType) throws IOException {
        // 熔断器检查：如果熔断中，立即快速失败
        if (!circuitBreaker.isCallPermitted()) {
            throw new PythonServiceUnavailableException(
                    circuitBreaker.getState().name(),
                    circuitBreaker.getRemainingOpenTimeMs());
        }

        int retries = 0;
        int maxRetries = isSafelyRetryable(request) ? config.getMaxRetries() : 0;
        IOException lastException = null;

        while (retries <= maxRetries) {
            try {
                T result = execute(request, responseType);
                circuitBreaker.recordSuccess();
                return result;
            } catch (IOException e) {
                lastException = e;
                retries++;
                if (retries <= maxRetries) {
                    log.warn("Python SmartBI 安全读取失败，重试 {}/{}", retries, maxRetries);
                    try {
                        Thread.sleep(1000 * retries); // 指数退避
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("请求被中断", ie);
                    }
                }
            }
        }

        // 所有重试耗尽，记录失败（可能触发熔断）
        circuitBreaker.recordFailure();
        serviceAvailable.set(false);
        throw lastException;
    }

    /**
     * 执行请求并带重试机制 + 熔断器保护（TypeReference 版本）
     */
    private <T> T executeWithRetry(Request request, TypeReference<T> typeReference) throws IOException {
        // 熔断器检查：如果熔断中，立即快速失败
        if (!circuitBreaker.isCallPermitted()) {
            throw new PythonServiceUnavailableException(
                    circuitBreaker.getState().name(),
                    circuitBreaker.getRemainingOpenTimeMs());
        }

        int retries = 0;
        int maxRetries = isSafelyRetryable(request) ? config.getMaxRetries() : 0;
        IOException lastException = null;

        while (retries <= maxRetries) {
            try {
                T result = execute(request, typeReference);
                circuitBreaker.recordSuccess();
                return result;
            } catch (IOException e) {
                lastException = e;
                retries++;
                if (retries <= maxRetries) {
                    log.warn("Python SmartBI 安全读取失败，重试 {}/{}", retries, maxRetries);
                    try {
                        Thread.sleep(1000 * retries);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("请求被中断", ie);
                    }
                }
            }
        }

        // 所有重试耗尽，记录失败（可能触发熔断）
        circuitBreaker.recordFailure();
        serviceAvailable.set(false);
        throw lastException;
    }

    /**
     * 执行单次请求
     */
    private <T> T execute(Request request, Class<T> responseType) throws IOException {
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                inspectErrorBodyBounded(response.body());
                throw sanitizedUpstreamFailure(response.code());
            }

            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                return objectMapper.readValue("{}", responseType);
            }
            requireJsonResponse(responseBody);
            BoundedInputStream in = boundedStream(responseBody, MAX_JSON_RESPONSE_BYTES);
            try {
                T result = objectMapper.readValue(in, responseType);
                in.drainToEnd();
                return result;
            } finally {
                in.closeDelegate();
            }
        }
    }

    /**
     * 执行单次请求（TypeReference 版本）
     */
    private <T> T execute(Request request, TypeReference<T> typeReference) throws IOException {
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                inspectErrorBodyBounded(response.body());
                throw sanitizedUpstreamFailure(response.code());
            }

            // Stream-parse the JSON body instead of buffering to String first.
            // /auto-parse responses for large uploads (60MB CSV → 100MB+ JSON) used
            // to load the full body as a single String before Jackson built the object
            // graph on top — peak heap ≈ 3× response size, which blew -Xmx1280m with
            // "Self-suppression not permitted" / OutOfMemoryError. byteStream() keeps
            // only the active parse frame in memory.
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                return objectMapper.readValue("{}", typeReference);
            }
            requireJsonResponse(responseBody);
            BoundedInputStream in = boundedStream(responseBody, MAX_JSON_RESPONSE_BYTES);
            try {
                T result = objectMapper.readValue(in, typeReference);
                in.drainToEnd();
                return result;
            } finally {
                in.closeDelegate();
            }
        }
    }

    private static boolean isSafelyRetryable(Request request) {
        return "GET".equals(request.method()) || "HEAD".equals(request.method());
    }

    private static HttpUrl requireServiceBaseUrl(String configuredUrl) {
        final HttpUrl parsed;
        try {
            parsed = HttpUrl.get(Objects.requireNonNull(configuredUrl, "Python SmartBI URL"));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Python SmartBI URL is invalid", invalid);
        }
        if (!("http".equals(parsed.scheme()) || "https".equals(parsed.scheme()))
                || !parsed.username().isEmpty()
                || !parsed.password().isEmpty()
                || !"/".equals(parsed.encodedPath())
                || parsed.query() != null
                || parsed.fragment() != null) {
            throw new IllegalArgumentException(
                    "Python SmartBI URL must contain only scheme, host and port");
        }
        return parsed;
    }

    private void requireSameServiceAuthority(HttpUrl requestUrl) throws IOException {
        if (!serviceBaseUrl.scheme().equals(requestUrl.scheme())
                || !serviceBaseUrl.host().equals(requestUrl.host())
                || serviceBaseUrl.port() != requestUrl.port()
                || !requestUrl.username().isEmpty()
                || !requestUrl.password().isEmpty()) {
            throw new IOException("Python SmartBI destination is not permitted");
        }
    }

    private static void requireJsonResponse(ResponseBody responseBody) throws IOException {
        MediaType contentType = responseBody.contentType();
        if (contentType == null
                || !"application".equalsIgnoreCase(contentType.type())
                || !("json".equalsIgnoreCase(contentType.subtype())
                    || contentType.subtype().toLowerCase(java.util.Locale.ROOT).endsWith("+json"))) {
            throw new IOException("Python SmartBI returned an unsupported response");
        }
    }

    private static BoundedInputStream boundedStream(ResponseBody responseBody, long maxBytes)
            throws IOException {
        long contentLength = responseBody.contentLength();
        if (contentLength > maxBytes) {
            throw new IOException("Python SmartBI response exceeded the configured limit");
        }
        return new BoundedInputStream(responseBody.byteStream(), maxBytes);
    }

    private static void inspectErrorBodyBounded(ResponseBody responseBody) {
        if (responseBody == null || responseBody.contentLength() > MAX_ERROR_INSPECTION_BYTES) {
            return;
        }
        try {
            responseBody.source().request(MAX_ERROR_INSPECTION_BYTES + 1L);
        } catch (IOException ignored) {
            // Error payloads are deliberately never surfaced or logged.
        }
    }

    private static IOException sanitizedUpstreamFailure(int statusCode) {
        return new IOException("Python SmartBI request failed with HTTP " + statusCode);
    }

    private <T> T readJsonBody(ResponseBody responseBody, Class<T> responseType)
            throws IOException {
        if (responseBody == null) {
            return objectMapper.readValue("{}", responseType);
        }
        requireJsonResponse(responseBody);
        BoundedInputStream in = boundedStream(responseBody, MAX_JSON_RESPONSE_BYTES);
        try {
            T result = objectMapper.readValue(in, responseType);
            in.drainToEnd();
            return result;
        } finally {
            in.closeDelegate();
        }
    }

    static final class BoundedInputStream extends FilterInputStream {
        private long remaining;
        private boolean delegateClosed;

        BoundedInputStream(InputStream delegate, long maxBytes) {
            super(delegate);
            this.remaining = maxBytes;
        }

        /**
         * Jackson closes its input source after parsing. Keep the delegate open until
         * {@link #drainToEnd()} verifies that a chunked response has not hidden bytes
         * beyond the configured boundary.
         */
        @Override
        public void close() {
            // The owning response parser closes the delegate explicitly in finally.
        }

        void closeDelegate() throws IOException {
            if (!delegateClosed) {
                delegateClosed = true;
                super.close();
            }
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0) {
                int overflow = super.read();
                if (overflow == -1) {
                    return -1;
                }
                throw new IOException("Python SmartBI response exceeded the configured limit");
            }
            int value = super.read();
            if (value != -1) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (remaining == 0) {
                int overflow = super.read();
                if (overflow == -1) {
                    return -1;
                }
                throw new IOException("Python SmartBI response exceeded the configured limit");
            }
            int boundedLength = (int) Math.min((long) length, remaining);
            int count = super.read(target, offset, boundedLength);
            if (count > 0) {
                remaining -= count;
            }
            return count;
        }

        void drainToEnd() throws IOException {
            byte[] discard = new byte[8192];
            while (read(discard, 0, discard.length) != -1) {
                // Consume within the hard bound so chunked responses cannot hide overflow.
            }
        }
    }

    // ==================== 新增：字段检测 ====================

    /**
     * 检测字段类型和语义
     *
     * @param request 检测请求
     * @return 检测结果，服务不可用时返回 empty
     */
    public Optional<PythonFieldDetectionResponse> detectFields(PythonFieldDetectionRequest request) {
        if (!config.isEnabled()) {
            log.debug("Python SmartBI 服务未启用，跳过字段检测");
            return Optional.empty();
        }

        try {
            log.info("调用 Python SmartBI 字段检测: headersCount={}",
                    request.getHeaders() != null ? request.getHeaders().size() : 0);

            Request httpRequest = new Request.Builder()
                    .url(config.getDetectFieldsUrl())
                    .post(RequestBody.create(JSON, objectMapper.writeValueAsString(request)))
                    .build();

            PythonFieldDetectionResponse response = executeWithRetry(httpRequest, PythonFieldDetectionResponse.class);
            return Optional.ofNullable(response);

        } catch (IOException | PythonServiceUnavailableException e) {
            log.error("字段检测失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 便捷方法：从样本数据检测字段
     */
    public Optional<PythonFieldDetectionResponse> detectFields(
            List<Map<String, Object>> sampleData, List<String> headers) {
        PythonFieldDetectionRequest request = PythonFieldDetectionRequest.builder()
                .sampleData(sampleData)
                .headers(headers)
                .build();
        return detectFields(request);
    }

    // ==================== 新增：字段映射 (LLM) ====================

    /**
     * 使用 LLM 映射字段
     *
     * @param request 映射请求
     * @return 映射结果
     */
    public Optional<PythonFieldMappingResponse> mapFields(PythonFieldMappingRequest request) {
        if (!config.isEnabled()) {
            log.debug("Python SmartBI 服务未启用，跳过字段映射");
            return Optional.empty();
        }

        try {
            log.info("调用 Python SmartBI 字段映射 (LLM): dataType={}, headersCount={}",
                    request.getDataType(),
                    request.getHeaders() != null ? request.getHeaders().size() : 0);

            Request httpRequest = new Request.Builder()
                    .url(config.getMapFieldsUrl())
                    .post(RequestBody.create(JSON, objectMapper.writeValueAsString(request)))
                    .build();

            PythonFieldMappingResponse response = executeWithRetry(httpRequest, PythonFieldMappingResponse.class);
            return Optional.ofNullable(response);

        } catch (IOException | PythonServiceUnavailableException e) {
            log.error("字段映射失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 便捷方法：映射字段
     */
    public Optional<PythonFieldMappingResponse> mapFields(List<String> headers, String dataType) {
        PythonFieldMappingRequest request = PythonFieldMappingRequest.builder()
                .headers(headers)
                .dataType(dataType)
                .build();
        return mapFields(request);
    }

    // ==================== 业务 Section 调用 (Domain-agnostic) ====================

    /**
     * 调用 Python 业务 section endpoint（domain-agnostic）。
     *
     * <p>这是架构原则 #5 的物理验证点 — 新业态接入时应该零改这个
     * {@code PythonSmartBIClient}，只需要在 {@code ai/tool/impl/{new_domain}/}
     * 下加新的 Tool 类调用此方法即可。
     *
     * <p>完全复用既有的 {@link #executeWithRetry(Request, Class)} +
     * {@link PythonServiceCircuitBreaker} + 构造函数里的 {@code X-Internal-Secret}
     * 拦截器基础设施，不单独维护一套 circuit breaker / retry / timeout。
     *
     * <p>URL 格式: {@code {baseUrl}/api/smartbi/{domain}/sections/{sectionName}}。
     * 例如 {@code callSection("restaurant", "cost_rigidity", req)} 会 POST 到
     * {@code {baseUrl}/api/smartbi/restaurant/sections/cost_rigidity}。
     *
     * @param domain      业务域 ({@code "restaurant"}, {@code "retail"},
     *                    {@code "beauty"}, ...)
     * @param sectionName section 名 (如 {@code "cost_rigidity"}, {@code "diagnostics"})
     * @param request     通用 section 请求参数
     * @return            section 结果; {@link Optional#empty()} 表示 Python
     *                    服务不可用或熔断中
     * @since 2026-04-10
     */
    public Optional<PythonSectionResponse> callSection(
            String domain, String sectionName, PythonSectionRequest request) {
        if (!config.isEnabled()) {
            log.debug("Python SmartBI 服务未启用，跳过 section 调用 {}/{}", domain, sectionName);
            return Optional.empty();
        }
        if (!"restaurant".equals(domain)
                || !ALLOWED_RESTAURANT_SECTIONS.contains(sectionName)) {
            log.warn("拒绝未登记的 Python section 调用");
            return Optional.empty();
        }

        try {
            HttpUrl url = serviceBaseUrl.newBuilder()
                    .addPathSegments("api/smartbi/restaurant/sections")
                    .addPathSegment(sectionName)
                    .build();
            log.debug("调用 Python section: {} / {}", domain, sectionName);

            Request httpRequest = new Request.Builder()
                    .url(url)
                    .header("X-Factory-Id", requireFactoryId(request.getFactoryId()))
                    .post(RequestBody.create(JSON, objectMapper.writeValueAsString(request)))
                    .build();

            PythonSectionResponse response = executeWithRetry(httpRequest, PythonSectionResponse.class);
            return Optional.ofNullable(response);

        } catch (IOException | PythonServiceUnavailableException e) {
            log.error("Section 调用失败 {}/{}: {}", domain, sectionName, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 便捷方法：调用餐饮域 section endpoint。
     *
     * <p>内部 delegate 到
     * {@link #callSection(String, String, PythonSectionRequest)} with
     * {@code domain="restaurant"}。保留为独立入口的原因：餐饮域的 Tool
     * 调用点更清晰，且未来可能针对餐饮 section 加 typed {@code data} 子 DTO。
     *
     * @param sectionName section 名 (如 {@code "cost_rigidity"}, {@code "diagnostics"})
     * @param request     餐饮 section 请求参数
     * @return            section 结果; {@link Optional#empty()} 表示 Python
     *                    服务不可用或熔断中
     * @since 2026-04-10
     */
    public Optional<PythonRestaurantSectionResponse> callRestaurantSection(
            String sectionName, PythonRestaurantSectionRequest request) {
        return callSection("restaurant", sectionName, request.toGeneric())
                .map(PythonRestaurantSectionResponse::fromGeneric);
    }

    // ==================== 新增：图表配置推荐 ====================

    /**
     * 获取图表配置推荐
     *
     * @param request 配置请求
     * @return 配置结果
     */
    public Optional<PythonChartConfigResponse> getChartConfig(PythonChartConfigRequest request) {
        if (!config.isEnabled()) {
            log.debug("Python SmartBI 服务未启用，跳过图表配置推荐");
            return Optional.empty();
        }

        try {
            log.info("调用 Python SmartBI 图表配置推荐: dataType={}, fieldsCount={}",
                    request.getDataType(),
                    request.getFields() != null ? request.getFields().size() : 0);

            Request httpRequest = new Request.Builder()
                    .url(config.getChartConfigUrl())
                    .post(RequestBody.create(JSON, objectMapper.writeValueAsString(request)))
                    .build();

            PythonChartConfigResponse response = executeWithRetry(httpRequest, PythonChartConfigResponse.class);
            return Optional.ofNullable(response);

        } catch (IOException | PythonServiceUnavailableException e) {
            log.error("图表配置推荐失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 便捷方法：根据字段获取图表配置
     */
    public Optional<PythonChartConfigResponse> getChartConfig(
            List<PythonChartConfigRequest.FieldInfo> fields, String dataType) {
        PythonChartConfigRequest request = PythonChartConfigRequest.builder()
                .fields(fields)
                .dataType(dataType)
                .build();
        return getChartConfig(request);
    }

    // ==================== 新增：指标计算 (新版 DTO) ====================

    /**
     * 计算指标 (使用新版 DTO)
     *
     * @param request 计算请求
     * @return 计算结果
     */
    public Optional<PythonMetricResponse> calculateMetricsV2(PythonMetricRequest request) {
        if (!config.isEnabled()) {
            log.debug("Python SmartBI 服务未启用，跳过指标计算");
            return Optional.empty();
        }

        try {
            log.info("调用 Python SmartBI 指标计算 V2: dataSize={}, metricsCount={}",
                    request.getData() != null ? request.getData().size() : 0,
                    request.getMetrics() != null ? request.getMetrics().size() : 0);

            Request httpRequest = new Request.Builder()
                    .url(config.getCalculateMetricsUrl())
                    .post(RequestBody.create(JSON, objectMapper.writeValueAsString(request)))
                    .build();

            PythonMetricResponse response = executeWithRetry(httpRequest, PythonMetricResponse.class);
            return Optional.ofNullable(response);

        } catch (IOException | PythonServiceUnavailableException e) {
            log.error("指标计算 V2 失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ==================== 新增：预测分析 (新版 DTO) ====================

    /**
     * 执行预测 (使用新版 DTO)
     *
     * @param request 预测请求
     * @return 预测结果
     */
    public Optional<PythonForecastResponse> forecastV2(PythonForecastRequest request) {
        if (!config.isEnabled()) {
            log.debug("Python SmartBI 服务未启用，跳过预测分析");
            return Optional.empty();
        }

        try {
            log.info("调用 Python SmartBI 预测分析 V2: algorithm={}, periods={}, dataSize={}",
                    request.getAlgorithm(),
                    request.getPeriods(),
                    request.getData() != null ? request.getData().size() : 0);

            Request httpRequest = new Request.Builder()
                    .url(config.getForecastUrl())
                    .post(RequestBody.create(JSON, objectMapper.writeValueAsString(request)))
                    .build();

            PythonForecastResponse response = executeWithRetry(httpRequest, PythonForecastResponse.class);
            return Optional.ofNullable(response);

        } catch (IOException | PythonServiceUnavailableException e) {
            log.error("预测分析 V2 失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 便捷方法：执行预测
     */
    public Optional<PythonForecastResponse> forecast(List<Double> data, String algorithm, int periods) {
        PythonForecastRequest request = PythonForecastRequest.builder()
                .data(data)
                .algorithm(algorithm)
                .periods(periods)
                .build();
        return forecastV2(request);
    }

    // ==================== 新增：AI 洞察生成 ====================

    /**
     * 生成 AI 洞察
     *
     * @param request 洞察请求
     * @return 洞察结果
     */
    public Optional<PythonInsightResponse> generateInsight(PythonInsightRequest request) {
        if (!config.isEnabled()) {
            log.debug("Python SmartBI 服务未启用，跳过 AI 洞察生成");
            return Optional.empty();
        }

        try {
            log.info("调用 Python SmartBI AI 洞察生成: dataType={}, insightType={}",
                    request.getDataType(),
                    request.getInsightType());

            Request httpRequest = new Request.Builder()
                    .url(config.getInsightUrl())
                    .post(RequestBody.create(JSON, objectMapper.writeValueAsString(request)))
                    .build();

            PythonInsightResponse response = executeWithRetry(httpRequest, PythonInsightResponse.class);
            return Optional.ofNullable(response);

        } catch (IOException | PythonServiceUnavailableException e) {
            log.error("AI 洞察生成失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 便捷方法：生成洞察
     */
    public Optional<PythonInsightResponse> generateInsight(Map<String, Object> analysisData, String dataType) {
        PythonInsightRequest request = PythonInsightRequest.builder()
                .analysisData(analysisData)
                .dataType(dataType)
                .build();
        return generateInsight(request);
    }

    // ==================== 新增：图表构建 ====================

    /**
     * 构建图表
     *
     * @param request 构建请求
     * @return 图表配置
     */
    public Optional<PythonChartResponse> buildChart(PythonChartRequest request) {
        if (!config.isEnabled()) {
            log.debug("Python SmartBI 服务未启用，跳过图表构建");
            return Optional.empty();
        }

        try {
            log.info("调用 Python SmartBI 图表构建: chartType={}, dataSize={}",
                    request.getChartType(),
                    request.getData() != null ? request.getData().size() : 0);

            Request httpRequest = new Request.Builder()
                    .url(config.getBuildChartUrl())
                    .post(RequestBody.create(JSON, objectMapper.writeValueAsString(request)))
                    .build();

            PythonChartResponse response = executeWithRetry(httpRequest, PythonChartResponse.class);
            return Optional.ofNullable(response);

        } catch (IOException | PythonServiceUnavailableException e) {
            log.error("图表构建失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 便捷方法：构建图表
     */
    public Optional<PythonChartResponse> buildChart(
            String chartType,
            List<Map<String, Object>> data,
            String dimensionField,
            List<String> metricFields) {
        PythonChartRequest request = PythonChartRequest.builder()
                .chartType(chartType)
                .data(data)
                .dimensionField(dimensionField)
                .metricFields(metricFields)
                .build();
        return buildChart(request);
    }

    /**
     * 便捷方法：构建图表 (返回 Map)
     */
    public Optional<Map<String, Object>> buildChartConfig(String chartType, Map<String, Object> data) {
        PythonChartRequest request = PythonChartRequest.builder()
                .chartType(chartType)
                .build();

        return buildChart(request)
                .filter(PythonChartResponse::isSuccess)
                .map(PythonChartResponse::getChartConfig);
    }

    // ==================== 新增：Excel 解析 (新版 DTO) ====================

    /**
     * 解析 Excel 文件 (使用新版 DTO)
     *
     * @param file    Excel 文件
     * @param request 解析参数
     * @return 解析结果
     */
    public Optional<PythonExcelParseResponse> parseExcelV2(MultipartFile file, PythonExcelParseRequest request) {
        if (!config.isEnabled()) {
            log.debug("Python SmartBI 服务未启用，跳过 Excel 解析");
            return Optional.empty();
        }

        try {
            log.info("调用 Python SmartBI 解析 Excel V2: fileName={}, factoryId={}, dataType={}, size={}MB",
                    file.getOriginalFilename(), request.getFactoryId(), request.getDataType(),
                    file.getSize() / 1024 / 1024);

            // 流式传输避免 OOM — 同 parseExcel (BUG-2 修复 2026-04-15).
            // Copy to temp File so OkHttp retries can re-read safely.
            final String vfilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload.bin";
            final File vtempFile = java.io.File.createTempFile("cretas-upload-", "-" + vfilename);
            vtempFile.deleteOnExit();
            file.transferTo(vtempFile);
            final MediaType vmediaType = MediaType.parse(guessMimeFromName(vfilename));
            RequestBody fileBody = RequestBody.create(vmediaType, vtempFile);

            MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", file.getOriginalFilename(), fileBody);

            if (request.getFactoryId() != null) {
                bodyBuilder.addFormDataPart("factoryId", request.getFactoryId());
            }
            if (request.getDataType() != null) {
                bodyBuilder.addFormDataPart("dataType", request.getDataType());
            }
            if (request.getSheetName() != null) {
                bodyBuilder.addFormDataPart("sheetName", request.getSheetName());
            }
            if (request.getHeaderRow() != null) {
                bodyBuilder.addFormDataPart("headerRow", request.getHeaderRow().toString());
            }
            if (request.getAutoDetectType() != null) {
                bodyBuilder.addFormDataPart("autoDetectType", request.getAutoDetectType().toString());
            }
            if (request.getMaxRows() != null && request.getMaxRows() > 0) {
                bodyBuilder.addFormDataPart("maxRows", request.getMaxRows().toString());
            }

            Request httpRequest = new Request.Builder()
                    .url(config.getParseExcelUrl())
                    .post(bodyBuilder.build())
                    .build();

            PythonExcelParseResponse response = executeWithRetry(httpRequest, PythonExcelParseResponse.class);
            return Optional.ofNullable(response);

        } catch (IOException | PythonServiceUnavailableException e) {
            log.error("Excel 解析 V2 失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ==================== 新增：最小二乘法求解 ====================

    /**
     * 调用 Python 服务求解最小二乘问题
     *
     * @param matrixA        系数矩阵 A (m x n)
     * @param vectorB        目标向量 b (m x 1)
     * @param regularization 正则化参数
     * @return 求解结果，服务不可用时返回 empty
     */
    public Optional<PythonLeastSquaresResponse> solveLeastSquares(
            List<List<Double>> matrixA,
            List<Double> vectorB,
            double regularization) {

        if (!config.isEnabled()) {
            log.debug("Python SmartBI 服务未启用，跳过最小二乘法求解");
            return Optional.empty();
        }

        try {
            log.info("调用 Python SmartBI 最小二乘法求解: matrixRows={}, matrixCols={}, vectorSize={}",
                    matrixA.size(),
                    matrixA.isEmpty() ? 0 : matrixA.get(0).size(),
                    vectorB.size());

            PythonLeastSquaresRequest request = PythonLeastSquaresRequest.builder()
                    .matrixA(matrixA)
                    .vectorB(vectorB)
                    .regularization(regularization)
                    .method("ridge")
                    .build();

            Request httpRequest = new Request.Builder()
                    .url(config.getLeastSquaresUrl())
                    .post(RequestBody.create(JSON, objectMapper.writeValueAsString(request)))
                    .build();

            PythonLeastSquaresResponse response = executeWithRetry(httpRequest, PythonLeastSquaresResponse.class);
            return Optional.ofNullable(response);

        } catch (IOException | PythonServiceUnavailableException e) {
            log.error("最小二乘法求解失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 便捷方法：从二维数组调用最小二乘法
     *
     * @param matrixA        系数矩阵 (double[][])
     * @param vectorB        目标向量 (double[])
     * @param regularization 正则化参数
     * @return 求解结果
     */
    public Optional<PythonLeastSquaresResponse> solveLeastSquares(
            double[][] matrixA,
            double[] vectorB,
            double regularization) {

        // 转换为 List
        List<List<Double>> matrix = new java.util.ArrayList<>();
        for (double[] row : matrixA) {
            List<Double> rowList = new java.util.ArrayList<>();
            for (double val : row) {
                rowList.add(val);
            }
            matrix.add(rowList);
        }

        List<Double> vector = new java.util.ArrayList<>();
        for (double val : vectorB) {
            vector.add(val);
        }

        return solveLeastSquares(matrix, vector, regularization);
    }

    // ==================== 分析服务 ====================

    /**
     * 获取财务概览
     *
     * @param data 财务数据
     * @return 财务概览结果
     */
    public Optional<PythonAnalysisResponse> getFinanceOverview(List<Map<String, Object>> data) {
        return callAnalysisEndpoint(config.getFinanceOverviewUrl(), PythonAnalysisRequest.of(data), "财务概览");
    }

    /**
     * 获取利润趋势
     *
     * @param data       财务数据
     * @param periodType 周期类型 (monthly, quarterly, yearly)
     * @return 利润趋势结果
     */
    public Optional<PythonAnalysisResponse> getProfitTrend(List<Map<String, Object>> data, String periodType) {
        return callAnalysisEndpoint(config.getProfitTrendUrl(),
                PythonAnalysisRequest.of(data, periodType), "利润趋势");
    }

    /**
     * 获取成本结构
     *
     * @param data 成本数据
     * @return 成本结构结果
     */
    public Optional<PythonAnalysisResponse> getCostStructure(List<Map<String, Object>> data) {
        return callAnalysisEndpoint(config.getCostStructureUrl(), PythonAnalysisRequest.of(data), "成本结构");
    }

    /**
     * 获取预算瀑布图数据
     *
     * @param data 预算数据
     * @return 瀑布图数据
     */
    public Optional<PythonAnalysisResponse> getBudgetWaterfall(List<Map<String, Object>> data) {
        return callAnalysisEndpoint(config.getBudgetWaterfallUrl(), PythonAnalysisRequest.of(data), "预算瀑布图");
    }

    /**
     * 获取预算对比实际
     *
     * @param data 预算和实际数据
     * @return 对比结果
     */
    public Optional<PythonAnalysisResponse> getBudgetVsActual(List<Map<String, Object>> data) {
        return callAnalysisEndpoint(config.getBudgetVsActualUrl(), PythonAnalysisRequest.of(data), "预算对比实际");
    }

    /**
     * 获取同比环比分析
     *
     * @param data       时间序列数据
     * @param periodType 周期类型
     * @return 同比环比结果
     */
    public Optional<PythonAnalysisResponse> getYoyMomComparison(List<Map<String, Object>> data, String periodType) {
        return callAnalysisEndpoint(config.getYoyMomUrl(),
                PythonAnalysisRequest.of(data, periodType), "同比环比分析");
    }

    /**
     * 获取销售 KPI
     *
     * @param data 销售数据
     * @return 销售 KPI 结果
     */
    public Optional<PythonAnalysisResponse> getSalesKpis(List<Map<String, Object>> data) {
        return callAnalysisEndpoint(config.getSalesKpisUrl(), PythonAnalysisRequest.of(data), "销售KPI");
    }

    /**
     * 获取销售员排名
     *
     * @param data 销售数据
     * @param topN 返回前 N 名
     * @return 销售员排名结果
     */
    public Optional<PythonAnalysisResponse> getSalespersonRanking(List<Map<String, Object>> data, int topN) {
        return callAnalysisEndpoint(config.getSalespersonRankingUrl(),
                PythonAnalysisRequest.ofRanking(data, topN), "销售员排名");
    }

    /**
     * 获取产品排名
     *
     * @param data 销售数据
     * @param topN 返回前 N 名
     * @return 产品排名结果
     */
    public Optional<PythonAnalysisResponse> getProductRanking(List<Map<String, Object>> data, int topN) {
        return callAnalysisEndpoint(config.getProductRankingUrl(),
                PythonAnalysisRequest.ofRanking(data, topN), "产品排名");
    }

    /**
     * 获取销售趋势
     *
     * @param data       销售数据
     * @param periodType 周期类型 (daily, weekly, monthly)
     * @return 销售趋势结果
     */
    public Optional<PythonAnalysisResponse> getSalesTrend(List<Map<String, Object>> data, String periodType) {
        return callAnalysisEndpoint(config.getSalesTrendUrl(),
                PythonAnalysisRequest.of(data, periodType), "销售趋势");
    }

    /**
     * 获取区域分布
     *
     * @param data 销售数据
     * @return 区域分布结果
     */
    public Optional<PythonAnalysisResponse> getRegionDistribution(List<Map<String, Object>> data) {
        return callAnalysisEndpoint(config.getRegionDistributionUrl(), PythonAnalysisRequest.of(data), "区域分布");
    }

    /**
     * 调用生产分析端点 (T6.6 Phase B Sub-A polymorphic Option A).
     *
     * GET /api/mobile/{factoryId}/smart-bi/analysis/production with query
     * parameters startDate / endDate / analysisType. Single URL serves both
     * factory and restaurant tenants; Python side dispatches on
     * cretas_db.factories.type. Bearer JWT must be forwarded by the caller
     * via {@code authorizationHeader} (typically the incoming request's
     * Authorization header).
     *
     * @param factoryId           工厂 / 门店 ID (e.g. "F001", "R_ILTEATRO_REAL")
     * @param startDate           查询区间开始 (inclusive)
     * @param endDate             查询区间结束 (inclusive)
     * @param analysisType        oee / efficiency / equipment / null for overview
     * @param authorizationHeader 完整 "Bearer &lt;jwt&gt;" 头值; null 时不发送 (端点会返回 401)
     * @return 响应 Map; 服务不可用 / 异常时返回 null
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> callAnalysisProduction(String factoryId,
                                                     java.time.LocalDate startDate,
                                                     java.time.LocalDate endDate,
                                                     String analysisType,
                                                     String authorizationHeader) {
        return callAnalysisGetEndpoint(
                config.getAnalysisProductionUrl(factoryId),
                startDate, endDate, analysisType, authorizationHeader, "生产分析");
    }

    /**
     * 调用质量分析端点 (T6.6 Phase B Sub-B polymorphic Option A).
     *
     * GET /api/mobile/{factoryId}/smart-bi/analysis/quality with query
     * parameters startDate / endDate / analysisType. Mirrors
     * {@link #callAnalysisProduction} signature for symmetry — see that
     * Javadoc for parameter semantics.
     *
     * @param factoryId           工厂 / 门店 ID
     * @param startDate           查询区间开始 (inclusive)
     * @param endDate             查询区间结束 (inclusive)
     * @param analysisType        fpy / defect / rework / null for overview
     * @param authorizationHeader 完整 "Bearer &lt;jwt&gt;" 头值; null 时不发送
     * @return 响应 Map; 服务不可用 / 异常时返回 null
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> callAnalysisQuality(String factoryId,
                                                  java.time.LocalDate startDate,
                                                  java.time.LocalDate endDate,
                                                  String analysisType,
                                                  String authorizationHeader) {
        return callAnalysisGetEndpoint(
                config.getAnalysisQualityUrl(factoryId),
                startDate, endDate, analysisType, authorizationHeader, "质量分析");
    }


    /**
     * 调用高管仪表盘端点 (Phase 2C Tier 2 PILOT, period-based).
     *
     * GET /api/mobile/{factoryId}/smart-bi/dashboard/executive with query param
     * period={today,week,month,quarter,year}. Pilot scope per
     * AGGRESSIVE-REVISED — single-primitive wrapper around sales overview.
     * Zero-caller at ship time per PR #360 pattern; future Java callers
     * forward through this method when migrating off
     * SmartBIDashboardController.getExecutiveDashboard.
     *
     * @param factoryId           工厂 / 门店 ID
     * @param period              today / week / month / quarter / year (null defaults to month)
     * @param authorizationHeader 完整 "Bearer &lt;jwt&gt;" 头值; null 时不发送 (端点返回 401)
     * @return 响应 Map; 服务不可用 / 异常时返回 null
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> callDashboardExecutive(String factoryId,
                                                    String period,
                                                    String authorizationHeader) {
        return callDashboardGetEndpoint(
                config.getDashboardExecutiveUrl(factoryId),
                period, null, null, authorizationHeader, "高管仪表盘");
    }

    /**
     * 调用高管仪表盘端点 (Phase 2C Tier 2 PILOT, custom date range).
     *
     * GET /api/mobile/{factoryId}/smart-bi/dashboard/executive/custom with
     * startDate / endDate query params. Mirrors Java
     * SmartBIDashboardController.getExecutiveDashboardCustomRange line 315.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> callDashboardExecutiveCustom(String factoryId,
                                                          java.time.LocalDate startDate,
                                                          java.time.LocalDate endDate,
                                                          String authorizationHeader) {
        return callDashboardGetEndpoint(
                config.getDashboardExecutiveCustomUrl(factoryId),
                null, startDate, endDate, authorizationHeader, "高管仪表盘(自定义范围)");
    }

    /**
     * 调用统一复合仪表盘端点 (Phase 2C Tier 2 PILOT).
     *
     * GET /api/mobile/{factoryId}/smart-bi/dashboard with period query param.
     * Pilot fanout: sales / finance / inventory / procurement + production +
     * quality placeholders. Mirrors Java getUnifiedDashboard line 343.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> callDashboardUnified(String factoryId,
                                                  String period,
                                                  String authorizationHeader) {
        return callDashboardGetEndpoint(
                config.getDashboardUnifiedUrl(factoryId),
                period, null, null, authorizationHeader, "统一仪表盘");
    }

    /**
     * 通用 GET-style dashboard 端点调用 (Phase 2C Tier 2 PILOT 共享).
     *
     * Mirrors {@link #callAnalysisGetEndpoint} shape but for dashboard endpoints
     * which accept ``period`` (string) instead of ``analysisType``. Caller passes
     * non-null period OR non-null startDate+endDate, not both.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> callDashboardGetEndpoint(String baseUrl,
                                                       String period,
                                                       java.time.LocalDate startDate,
                                                       java.time.LocalDate endDate,
                                                       String authorizationHeader,
                                                       String operationName) {
        if (!config.isEnabled()) {
            log.debug("Python SmartBI 服务未启用，跳过{}", operationName);
            return null;
        }

        HttpUrl parsed = HttpUrl.parse(baseUrl);
        if (parsed == null) {
            log.error("{} URL 解析失败: {}", operationName, baseUrl);
            return null;
        }
        HttpUrl.Builder urlBuilder = parsed.newBuilder();
        if (period != null && !period.isEmpty()) {
            urlBuilder.addQueryParameter("period", period);
        }
        if (startDate != null) {
            urlBuilder.addQueryParameter("startDate", startDate.toString());
        }
        if (endDate != null) {
            urlBuilder.addQueryParameter("endDate", endDate.toString());
        }

        Request.Builder reqBuilder = new Request.Builder().url(urlBuilder.build()).get();
        if (authorizationHeader != null && !authorizationHeader.isEmpty()) {
            reqBuilder.header("Authorization", authorizationHeader);
        }
        Request httpRequest = reqBuilder.build();
        log.info("调用 Python SmartBI {}: url={}", operationName, urlBuilder.build());

        try {
            return executeWithRetry(httpRequest, Map.class);
        } catch (IOException | PythonServiceUnavailableException e) {
            log.error("{}调用失败: {}", operationName, e.getMessage());
            return null;
        }
    }

    /**
     * 调用餐饮经营体检报告端点 (餐饮经营体检预警推送 — 反回扣 standing alert 数据源).
     *
     * <p>GET /api/smartbi/restaurant/{factoryId}/health-check-report?month=.
     * 这是一个 server-to-server 内部调用 (定时扫描器触发, 无用户 JWT 可转发), 走
     * X-Internal-Secret (由 OkHttp 拦截器自动加, 见构造函数) + X-Factory-Id
     * (本方法显式加, 让 Python {@code auth_middleware.py} internal 分支正确
     * scope 到该工厂, 见 restaurant_health_check.py 的 tenant guard:
     * {@code caller != "INTERNAL" and caller != factory_id -> 403}).
     *
     * <p>禁降级: 服务不可用 / 请求失败时返回 {@code null} (调用方 —
     * {@code RestaurantHealthAlertBridgeService} — 必须诚实跳过本次 sweep, 不
     * 得编造诊断结果).
     *
     * @param factoryId 门店 factoryId (FactoryType.RESTAURANT)
     * @param month     "YYYY-MM" / "上月" / "本月" / null(默认上月)
     * @return 原始响应 Map ({@code {success, data:{reportMeta,summary,diagnoses}, message}});
     *         null = 服务不可用或请求失败
     * @since 2026-07-11
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getRestaurantHealthCheckReport(String factoryId, String month) {
        if (!config.isEnabled()) {
            log.debug("Python SmartBI 服务未启用，跳过餐饮经营体检报告调用 factoryId={}", factoryId);
            return null;
        }

        final String trustedFactoryId;
        try {
            trustedFactoryId = requireFactoryId(factoryId);
        } catch (IOException invalidFactory) {
            return null;
        }
        HttpUrl.Builder urlBuilder = serviceBaseUrl.newBuilder()
                .addPathSegments("api/smartbi/restaurant")
                .addPathSegment(trustedFactoryId)
                .addPathSegment("health-check-report");
        if (month != null && !month.isBlank()) {
            urlBuilder.addQueryParameter("month", month);
        }

        Request httpRequest = new Request.Builder()
                .url(urlBuilder.build())
                .header("X-Factory-Id", trustedFactoryId)
                .get()
                .build();
        log.info("调用 Python 餐饮经营体检报告: factoryId={}, month={}", factoryId, month);

        try {
            return executeWithRetry(httpRequest, Map.class);
        } catch (IOException | PythonServiceUnavailableException e) {
            log.error("餐饮经营体检报告调用失败 factoryId={}: {}", factoryId, e.getMessage());
            return null;
        }
    }

    /**
     * 通用 GET-style analysis 端点调用 (T6.6 Phase B Sub-A / Sub-B 共享).
     *
     * Mirrors the existing POST-based {@link #callAnalysisEndpoint} shape but
     * for the polymorphic Option A endpoints that take query parameters and
     * return raw Map (no wrapping DTO). Auth header is forwarded verbatim
     * because Python endpoint enforces cross-factory match via JWT factoryId.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> callAnalysisGetEndpoint(String baseUrl,
                                                       java.time.LocalDate startDate,
                                                       java.time.LocalDate endDate,
                                                       String analysisType,
                                                       String authorizationHeader,
                                                       String operationName) {
        if (!config.isEnabled()) {
            log.debug("Python SmartBI 服务未启用，跳过{}", operationName);
            return null;
        }

        HttpUrl parsed = HttpUrl.parse(baseUrl);
        if (parsed == null) {
            log.error("{} URL 解析失败: {}", operationName, baseUrl);
            return null;
        }
        HttpUrl.Builder urlBuilder = parsed.newBuilder();
        if (startDate != null) {
            urlBuilder.addQueryParameter("startDate", startDate.toString());
        }
        if (endDate != null) {
            urlBuilder.addQueryParameter("endDate", endDate.toString());
        }
        if (analysisType != null && !analysisType.isEmpty()) {
            urlBuilder.addQueryParameter("analysisType", analysisType);
        }

        Request.Builder reqBuilder = new Request.Builder().url(urlBuilder.build()).get();
        if (authorizationHeader != null && !authorizationHeader.isEmpty()) {
            reqBuilder.header("Authorization", authorizationHeader);
        }
        Request httpRequest = reqBuilder.build();
        log.info("调用 Python SmartBI {}: url={}", operationName, urlBuilder.build());

        try {
            return executeWithRetry(httpRequest, Map.class);
        } catch (IOException | PythonServiceUnavailableException e) {
            log.error("{}调用失败: {}", operationName, e.getMessage());
            return null;
        }
    }

    /**
     * 通用分析端点调用
     */
    private Optional<PythonAnalysisResponse> callAnalysisEndpoint(String url, PythonAnalysisRequest request, String operationName) {
        if (!config.isEnabled()) {
            log.debug("Python SmartBI 服务未启用，跳过{}", operationName);
            return Optional.empty();
        }

        try {
            log.info("调用 Python SmartBI {}: dataSize={}", operationName,
                    request.getData() != null ? request.getData().size() : 0);

            Request httpRequest = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(JSON, objectMapper.writeValueAsString(request)))
                    .build();

            PythonAnalysisResponse response = executeWithRetry(httpRequest, PythonAnalysisResponse.class);
            return Optional.ofNullable(response);

        } catch (IOException | PythonServiceUnavailableException e) {
            log.error("{}失败: {}", operationName, e.getMessage());
            return Optional.empty();
        }
    }

    // ==================== Getter ====================

    /**
     * 获取配置
     */
    public PythonSmartBIConfig getConfig() {
        return config;
    }

    /**
     * 获取服务 URL
     */
    public String getServiceUrl() {
        return config.getUrl();
    }

    // ==================== 意图分类器 ====================

    /**
     * 调用 Python 意图分类服务
     *
     * @param text 待分类文本
     * @return 分类结果
     */
    public Optional<ClassifierResponse> classifyIntent(String text) {
        return classifyIntent(text, 3);
    }

    /**
     * 调用 Python 意图分类服务（指定 top-k）
     *
     * @param text 待分类文本
     * @param topK 返回 top-k 个结果
     * @return 分类结果
     */
    public Optional<ClassifierResponse> classifyIntent(String text, int topK) {
        if (!config.isEnabled()) {
            log.debug("Python SmartBI 服务未启用，跳过意图分类");
            return Optional.empty();
        }

        try {
            log.debug("调用 Python 意图分类: text='{}'", text);

            ClassifierRequest request = ClassifierRequest.of(text, topK);

            Request httpRequest = new Request.Builder()
                    .url(config.getClassifyUrl())
                    .post(RequestBody.create(JSON, objectMapper.writeValueAsString(request)))
                    .build();

            ClassifierResponse response = executeWithRetry(httpRequest, ClassifierResponse.class);
            return Optional.ofNullable(response);

        } catch (IOException | PythonServiceUnavailableException e) {
            log.error("意图分类失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 批量意图分类
     *
     * @param texts 文本列表
     * @return 批量分类结果
     */
    public Optional<ClassifierBatchResponse> classifyIntentBatch(List<String> texts) {
        return classifyIntentBatch(texts, 1);
    }

    /**
     * 批量意图分类（指定 top-k）
     *
     * @param texts 文本列表
     * @param topK  每个文本返回 top-k 个结果
     * @return 批量分类结果
     */
    public Optional<ClassifierBatchResponse> classifyIntentBatch(List<String> texts, int topK) {
        if (!config.isEnabled()) {
            log.debug("Python SmartBI 服务未启用，跳过批量意图分类");
            return Optional.empty();
        }

        try {
            log.debug("调用 Python 批量意图分类: count={}", texts.size());

            ClassifierRequest request = ClassifierRequest.ofBatch(texts, topK);

            Request httpRequest = new Request.Builder()
                    .url(config.getClassifyBatchUrl())
                    .post(RequestBody.create(JSON, objectMapper.writeValueAsString(request)))
                    .build();

            ClassifierBatchResponse response = executeWithRetry(httpRequest, ClassifierBatchResponse.class);
            return Optional.ofNullable(response);

        } catch (IOException | PythonServiceUnavailableException e) {
            log.error("批量意图分类失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 检查分类器是否可用
     *
     * @return 分类器健康状态
     */
    public boolean isClassifierAvailable() {
        if (!config.isEnabled()) {
            return false;
        }

        try {
            Request request = new Request.Builder()
                    .url(config.getClassifierHealthUrl())
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    Map<?, ?> body = readJsonBody(response.body(), Map.class);
                    return Boolean.TRUE.equals(body.get("model_loaded"));
                }
                if (!response.isSuccessful()) {
                    inspectErrorBodyBounded(response.body());
                }
            }
        } catch (IOException e) {
            log.warn("分类器健康检查失败: {}", e.getMessage());
        }
        return false;
    }

    // ==================== LinUCB 计算 ====================

    /**
     * 调用 Python 计算 LinUCB UCB 值
     *
     * @param matrixA A 矩阵 (n x n)
     * @param vectorB b 向量 (n)
     * @param context 上下文特征向量 (n)
     * @param alpha   探索参数
     * @return UCB 计算结果
     */
    public Optional<LinUCBComputeResponse> computeLinUCB(
            double[][] matrixA,
            double[] vectorB,
            double[] context,
            double alpha) {

        if (!config.isEnabled()) {
            log.debug("Python SmartBI 服务未启用，跳过 LinUCB UCB 计算");
            return Optional.empty();
        }

        try {
            log.debug("调用 Python LinUCB UCB 计算: contextDim={}", context.length);

            LinUCBComputeRequest request = LinUCBComputeRequest.builder()
                    .matrixA(toNestedList(matrixA))
                    .vectorB(toList(vectorB))
                    .context(toList(context))
                    .alpha(alpha)
                    .build();

            Request httpRequest = new Request.Builder()
                    .url(config.getLinucbComputeUrl())
                    .post(RequestBody.create(JSON, objectMapper.writeValueAsString(request)))
                    .build();

            LinUCBComputeResponse response = executeWithRetry(httpRequest, LinUCBComputeResponse.class);
            return Optional.ofNullable(response);

        } catch (IOException | PythonServiceUnavailableException e) {
            log.error("LinUCB UCB 计算失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 调用 Python 更新 LinUCB 模型
     *
     * @param matrixA A 矩阵
     * @param vectorB b 向量
     * @param context 上下文特征
     * @param reward  观察到的奖励
     * @return 更新后的模型参数
     */
    public Optional<LinUCBUpdateResponse> updateLinUCBModel(
            double[][] matrixA,
            double[] vectorB,
            double[] context,
            double reward) {

        if (!config.isEnabled()) {
            log.debug("Python SmartBI 服务未启用，跳过 LinUCB 模型更新");
            return Optional.empty();
        }

        try {
            log.debug("调用 Python LinUCB 模型更新: reward={}", reward);

            LinUCBUpdateRequest request = LinUCBUpdateRequest.builder()
                    .matrixA(toNestedList(matrixA))
                    .vectorB(toList(vectorB))
                    .context(toList(context))
                    .reward(reward)
                    .build();

            Request httpRequest = new Request.Builder()
                    .url(config.getLinucbUpdateUrl())
                    .post(RequestBody.create(JSON, objectMapper.writeValueAsString(request)))
                    .build();

            LinUCBUpdateResponse response = executeWithRetry(httpRequest, LinUCBUpdateResponse.class);
            return Optional.ofNullable(response);

        } catch (IOException | PythonServiceUnavailableException e) {
            log.error("LinUCB 模型更新失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 批量计算多个工人的 LinUCB UCB 值
     *
     * @param matrixAList A 矩阵列表
     * @param vectorBList b 向量列表
     * @param context     共享上下文
     * @param alpha       探索参数
     * @return 批量计算结果
     */
    public Optional<LinUCBBatchResponse> batchComputeLinUCB(
            List<double[][]> matrixAList,
            List<double[]> vectorBList,
            double[] context,
            double alpha) {

        if (!config.isEnabled()) {
            log.debug("Python SmartBI 服务未启用，跳过 LinUCB 批量计算");
            return Optional.empty();
        }

        try {
            log.debug("调用 Python LinUCB 批量计算: workerCount={}", matrixAList.size());

            List<List<List<Double>>> matrixAListConverted = new java.util.ArrayList<>();
            for (double[][] matrix : matrixAList) {
                matrixAListConverted.add(toNestedList(matrix));
            }

            List<List<Double>> vectorBListConverted = new java.util.ArrayList<>();
            for (double[] vector : vectorBList) {
                vectorBListConverted.add(toList(vector));
            }

            LinUCBBatchRequest request = LinUCBBatchRequest.builder()
                    .matrixAList(matrixAListConverted)
                    .vectorBList(vectorBListConverted)
                    .context(toList(context))
                    .alpha(alpha)
                    .build();

            Request httpRequest = new Request.Builder()
                    .url(config.getLinucbBatchUrl())
                    .post(RequestBody.create(JSON, objectMapper.writeValueAsString(request)))
                    .build();

            LinUCBBatchResponse response = executeWithRetry(httpRequest, LinUCBBatchResponse.class);
            return Optional.ofNullable(response);

        } catch (IOException | PythonServiceUnavailableException e) {
            log.error("LinUCB 批量计算失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ==================== 食品知识库 ====================

    /**
     * 食品知识库 RAG 查询
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> queryFoodKnowledge(String query, List<String> categories, int topK) {
        log.info("食品知识库查询: query={}, categories={}, topK={}", query, categories, topK);
        try {
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("query", query);
            body.put("top_k", topK);
            body.put("similarity_threshold", 0.55);
            if (categories != null && !categories.isEmpty()) {
                body.put("categories", categories);
            }
            Request request = new Request.Builder()
                    .url(config.getFoodKbQueryUrl())
                    .post(RequestBody.create(JSON, objectMapper.writeValueAsString(body)))
                    .build();
            return executeWithRetry(request, Map.class);
        } catch (IOException | PythonServiceUnavailableException e) {
            log.error("食品知识库查询失败: {}", e.getMessage());
            Map<String, Object> errorResult = new java.util.HashMap<>();
            errorResult.put("success", false);
            errorResult.put("message", e.getMessage());
            return errorResult;
        }
    }

    /**
     * 食品知识库实体提取 (NER)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> extractFoodEntities(String text) {
        log.debug("食品知识库实体提取: text={}", text);
        try {
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("text", text);
            body.put("use_model", true);
            Request request = new Request.Builder()
                    .url(config.getFoodKbExtractEntitiesUrl())
                    .post(RequestBody.create(JSON, objectMapper.writeValueAsString(body)))
                    .build();
            return executeWithRetry(request, Map.class);
        } catch (IOException | PythonServiceUnavailableException e) {
            log.warn("食品知识库实体提取失败: {}", e.getMessage());
            Map<String, Object> errorResult = new java.util.HashMap<>();
            errorResult.put("success", false);
            return errorResult;
        }
    }

    // ==================== 食品知识库反馈 ====================

    /**
     * 提交食品知识库反馈
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> submitFoodKBFeedback(Map<String, Object> feedbackRequest) {
        log.info("提交食品知识库反馈: query={}", feedbackRequest.get("query"));
        try {
            Request request = new Request.Builder()
                    .url(config.getFoodKbFeedbackSubmitUrl())
                    .post(RequestBody.create(JSON, objectMapper.writeValueAsString(feedbackRequest)))
                    .build();
            return executeWithRetry(request, Map.class);
        } catch (IOException | PythonServiceUnavailableException e) {
            log.error("提交食品知识库反馈失败: {}", e.getMessage());
            Map<String, Object> errorResult = new java.util.HashMap<>();
            errorResult.put("success", false);
            errorResult.put("message", e.getMessage());
            return errorResult;
        }
    }

    /**
     * 记录食品知识库查询日志
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> logFoodKBQuery(Map<String, Object> logRequest) {
        log.debug("记录食品知识库查询: query={}", logRequest.get("query"));
        try {
            Request request = new Request.Builder()
                    .url(config.getFoodKbFeedbackLogQueryUrl())
                    .post(RequestBody.create(JSON, objectMapper.writeValueAsString(logRequest)))
                    .build();
            return executeWithRetry(request, Map.class);
        } catch (IOException | PythonServiceUnavailableException e) {
            log.warn("记录食品知识库查询日志失败（非致命）: {}", e.getMessage());
            Map<String, Object> errorResult = new java.util.HashMap<>();
            errorResult.put("success", false);
            return errorResult;
        }
    }

    /**
     * 获取食品知识库反馈统计
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getFoodKBFeedbackStats() {
        log.info("获取食品知识库反馈统计");
        try {
            Request request = new Request.Builder()
                    .url(config.getFoodKbFeedbackStatsUrl())
                    .get()
                    .build();
            return executeWithRetry(request, Map.class);
        } catch (IOException | PythonServiceUnavailableException e) {
            log.error("获取食品知识库反馈统计失败: {}", e.getMessage());
            Map<String, Object> errorResult = new java.util.HashMap<>();
            errorResult.put("success", false);
            errorResult.put("message", e.getMessage());
            return errorResult;
        }
    }

    // ==================== 财务数据提取 ====================

    /**
     * 从 Excel 数据中提取结构化财务记录
     *
     * @param data      行数据
     * @param columns   列名列表
     * @param sheetName Sheet 名称
     * @return 提取结果（包含 SmartBiFinanceData 兼容的记录列表）
     */
    public Optional<FinanceExtractResponse> extractFinanceData(
            List<Map<String, Object>> data, List<String> columns, String sheetName) {
        if (!config.isEnabled()) {
            log.debug("Python SmartBI 服务未启用，跳过财务数据提取");
            return Optional.empty();
        }

        // Belt-and-suspenders guard. The primary caller (SmartBIUploadFlowServiceImpl)
        // already checks this, but other callers shouldn't have to learn it the hard
        // way. objectMapper.writeValueAsString on a Map with 500K+ cells easily
        // doubles past Xmx=768m due to UTF-16 string materialization.
        long cellCount = (data != null ? data.size() : 0L) * (columns != null ? columns.size() : 0L);
        if (cellCount > 500_000L) {
            log.warn("财务数据提取跳过(client 级): sheetName={}, size={}cells > 500K (heap 保护)",
                    sheetName, cellCount);
            return Optional.empty();
        }

        try {
            log.info("调用 Python 财务数据提取: sheetName={}, dataSize={}, columns={}",
                    sheetName,
                    data != null ? data.size() : 0,
                    columns != null ? columns.size() : 0);

            Map<String, Object> body = new java.util.HashMap<>();
            body.put("data", data);
            body.put("columns", columns);
            body.put("sheet_name", sheetName);

            Request httpRequest = new Request.Builder()
                    .url(config.getExtractFinanceUrl())
                    .post(RequestBody.create(JSON, objectMapper.writeValueAsString(body)))
                    .build();

            FinanceExtractResponse response = executeWithRetry(httpRequest, FinanceExtractResponse.class);
            return Optional.ofNullable(response);

        } catch (IOException | PythonServiceUnavailableException e) {
            log.warn("财务数据提取失败(非阻塞): {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ==================== 财务看板 ====================

    /**
     * 调用财务看板 Python 服务
     *
     * @param endpoint 端点路径，如 "/generate" 或 "/export-ppt"
     * @param request  请求体
     * @return 响应结果 Map，服务不可用时返回 null
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> callFinancialDashboard(
            String endpoint, Map<String, Object> request, String factoryId) {
        if (!config.isEnabled()) {
            log.debug("Python SmartBI 服务未启用，跳过财务看板调用");
            return null;
        }
        if (!ALLOWED_FINANCIAL_DASHBOARD_ENDPOINTS.contains(endpoint)) {
            log.warn("拒绝未登记的财务看板端点");
            return null;
        }

        final String trustedFactoryId;
        try {
            trustedFactoryId = requireFactoryId(factoryId);
        } catch (IOException invalidFactory) {
            return null;
        }
        HttpUrl url = serviceBaseUrl.newBuilder()
                .addPathSegments("api/smartbi/financial-dashboard")
                .addPathSegment(endpoint.substring(1))
                .build();
        log.info("调用财务看板: url={}, keys={}", url, request.keySet());

        try {
            Request httpRequest = new Request.Builder()
                    .url(url)
                    .header("X-Factory-Id", trustedFactoryId)
                    .post(RequestBody.create(JSON, objectMapper.writeValueAsString(request)))
                    .build();
            return executeWithRetry(httpRequest, Map.class);
        } catch (IOException | PythonServiceUnavailableException e) {
            log.error("财务看板调用失败: endpoint={}, error={}", endpoint, e.getMessage());
            return null;
        }
    }

    /**
     * 调用 QHJ 收入管理报表 Python 服务
     *
     * <p>Spec §8.4 / Task H2. Mirrors callFinancialDashboard but accepts the full
     * path (callers build the factory-scoped URL since revenue-report endpoints
     * are mounted at /api/smartbi/{factory_id}/revenue-report/*).
     *
     * @param fullEndpoint full path, e.g. "/api/smartbi/R_QINGHUAJIAO_REAL/revenue-report/prepare"
     * @param request request body Map (will be serialized to JSON)
     * @return response Map (parsed JSON), or null on service unavailable
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> callRevenueReport(
            String fullEndpoint,
            Map<String, Object> request,
            String factoryId,
            String userRole) {
        if (!config.isEnabled()) {
            log.debug("Python SmartBI 服务未启用，跳过收入管理报表调用");
            return null;
        }

        final String trustedFactoryId;
        try {
            trustedFactoryId = requireFactoryId(factoryId);
        } catch (IOException invalidFactory) {
            return null;
        }
        String expectedEndpoint = "/api/smartbi/" + trustedFactoryId
                + "/revenue-report/prepare";
        if (!expectedEndpoint.equals(fullEndpoint)) {
            log.warn("拒绝未登记的收入管理报表端点");
            return null;
        }
        HttpUrl url = serviceBaseUrl.newBuilder()
                .addPathSegments("api/smartbi")
                .addPathSegment(trustedFactoryId)
                .addPathSegments("revenue-report/prepare")
                .build();
        log.info("调用收入管理报表: url={}, keys={}", url, request.keySet());

        try {
            Request.Builder requestBuilder = new Request.Builder()
                    .url(url)
                    .header("X-Factory-Id", trustedFactoryId)
                    .post(RequestBody.create(JSON, objectMapper.writeValueAsString(request)));
            if (userRole != null && !userRole.isBlank()) {
                requestBuilder.header("X-User-Role", userRole);
            }
            Request httpRequest = requestBuilder.build();
            return executeWithRetry(httpRequest, Map.class);
        } catch (IOException | PythonServiceUnavailableException e) {
            log.error("收入管理报表调用失败: endpoint={}, error={}", fullEndpoint, e.getMessage());
            return null;
        }
    }

    private static String requireFactoryId(String factoryId) throws IOException {
        if (factoryId == null || !INTERNAL_IDENTITY.matcher(factoryId).matches()) {
            throw new IOException("factoryId is required for Python SmartBI request");
        }
        return factoryId;
    }

    private static String optionalIdentity(String identity) throws IOException {
        if (identity == null || identity.isBlank()) {
            return null;
        }
        if (!INTERNAL_IDENTITY.matcher(identity).matches()) {
            throw new IOException("Python SmartBI request identity is invalid");
        }
        return identity;
    }

    // ==================== 辅助转换方法 ====================

    /**
     * 将 double[][] 转换为 List<List<Double>>
     */
    private List<List<Double>> toNestedList(double[][] matrix) {
        List<List<Double>> result = new java.util.ArrayList<>();
        for (double[] row : matrix) {
            result.add(toList(row));
        }
        return result;
    }

    /**
     * 将 double[] 转换为 List<Double>
     */
    private List<Double> toList(double[] array) {
        List<Double> result = new java.util.ArrayList<>();
        for (double val : array) {
            result.add(val);
        }
        return result;
    }

    /**
     * γ-1c: 触发 Python 对已持久化 upload 的 field_definitions 重新分类 + re-materialize.
     * 同步 Java 上传路径在 saveFieldDefinitions+backfill 后调用，让 Python 的统一 classifier
     * 覆盖 Java 遗留规则（年/月/日 时间误判、账单号 id 缺失等）。
     *
     * 非阻塞：Python 不可用时返回 false，upload 仍成功（field_definitions 保留 Java 结果，
     * 用户可稍后手动调 POST /api/smartbi/analytics/reclassify/{id} 补救）。
     *
     * @param uploadId  已 persist 的 upload id
     * @param factoryId 当前上传归属的 factory（Python 做 cross-tenant 校验用）
     * @return true=reclassify+rematerialize 均成功；false=任一步失败
     */
    public boolean reclassifyUpload(Long uploadId, String factoryId) {
        if (!config.isEnabled() || uploadId == null || factoryId == null) {
            return false;
        }
        String url = config.getFullUrl("/api/smartbi/analytics/reclassify/" + uploadId);
        try {
            Request httpRequest = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(JSON, "{}"))
                    .header("X-Factory-Id", factoryId)
                    .build();
            try (Response response = httpClient.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    inspectErrorBodyBounded(response.body());
                    log.warn("γ-1c reclassify HTTP {} for upload {}",
                            response.code(), uploadId);
                    return false;
                }
                log.info("γ-1c reclassify OK for upload {} (factory={})", uploadId, factoryId);
                return true;
            }
        } catch (IOException e) {
            log.warn("γ-1c reclassify IO failure for upload {}: {}", uploadId, e.getMessage());
            return false;
        }
    }

    /**
     * γ-2c (Apr 25 2026 / Task C / PROD-1 fix): pre-materialize the cheap part
     * of enrichment (KPI summary via /quick-summary, no LLM) and persist as a
     * partial enrichment_cache row, so the FE cache-first branch hits an
     * instant cache on first visit and renders KPI cards in <1s instead of
     * timing out at 120s on 200K-row POS uploads.
     *
     * Non-blocking: any HTTP/IO failure is logged but does not affect upload
     * status (FE will fall back to running the full enrichment pipeline on
     * first visit, same as before this hook existed).
     *
     * @param uploadId  ID of the upload that just committed
     * @param factoryId factory the upload belongs to (for cross-tenant check)
     * @return true on 2xx + success=true; false on any failure
     */
    public boolean precomputeEnrichmentCache(Long uploadId, String factoryId) {
        if (!config.isEnabled() || uploadId == null || factoryId == null) {
            return false;
        }
        // Endpoint lives on the materialized_analytics router (JWT-protected)
        // because /api/smartbi/analysis-cache/ is in PUBLIC_PREFIXES and can't
        // see request.state.factory_id from the auth_middleware.
        String url = config.getFullUrl(
                "/api/smartbi/analytics/precompute-cache/" + uploadId);
        try {
            Request httpRequest = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(JSON, "{}"))
                    .header("X-Factory-Id", factoryId)
                    .build();
            try (Response response = httpClient.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    inspectErrorBodyBounded(response.body());
                    log.warn("γ-2c precompute-cache HTTP {} for upload {}",
                            response.code(), uploadId);
                    return false;
                }
                log.info("γ-2c precompute-cache OK for upload {} (factory={})",
                        uploadId, factoryId);
                return true;
            }
        } catch (IOException e) {
            log.warn("γ-2c precompute-cache IO failure for upload {}: {}",
                    uploadId, e.getMessage());
            return false;
        }
    }

    // ==================== 指标中心 (Phase 1 Sprint 1 Day 4) ====================

    /**
     * 取指标计算值 — Phase 2B 真实接入前的 stub.
     *
     * <p>Day 4 IndicatorQueryServiceImpl 调用入口; 当前实现返 {@link BigDecimal#ZERO},
     * 不实际发 HTTP. Phase 2B Python 端点上线后, 此方法改为:
     * <pre>
     *   POST {pythonEndpoint}
     *   Headers: X-Internal-Secret, X-Factory-Id
     *   Body: {"factoryId":..., "periodStart":..., "periodEnd":...}
     *   Response: {"value": "12.34", "scale": 4}
     * </pre>
     *
     * <p>调用方 (IndicatorQueryServiceImpl#fetchFromPython) 已处理 stub 返 ZERO 的语义,
     * 不需要本方法抛错告知 stub 状态.
     *
     * @param factoryId      工厂 ID
     * @param pythonEndpoint Python 端点路径 (IndicatorComputation.computeSource)
     * @param start          业务时间窗起
     * @param end            业务时间窗止
     * @return 指标取值 — Phase 1 stub 返 {@link BigDecimal#ZERO}
     */
    public java.math.BigDecimal fetchIndicatorValue(String factoryId, String pythonEndpoint,
                                                     java.time.LocalDate start,
                                                     java.time.LocalDate end) {
        // Phase 1 Day 4: stub returning ZERO. Phase 2B will replace with real HTTP call.
        log.debug("fetchIndicatorValue (stub): factoryId={}, endpoint={}, start={}, end={}",
                factoryId, pythonEndpoint, start, end);
        return java.math.BigDecimal.ZERO;
    }

    /**
     * 根据文件名推断 MIME 类型 — 支持 xlsx/xls/csv/pdf，其他默认 octet-stream。
     * 用于 Python 服务调用时正确设置 multipart body 的 Content-Type，避免 Python
     * 侧根据 MIME 而不是扩展名做 xlsx-only 判断。
     */
    private String guessMimeFromName(String filename) {
        if (filename == null) return "application/octet-stream";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".xls")) return "application/vnd.ms-excel";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }
}
