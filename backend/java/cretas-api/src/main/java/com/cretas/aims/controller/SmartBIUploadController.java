package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.smartbi.*;
import com.cretas.aims.entity.smartbi.postgres.SmartBiDynamicData;
import com.cretas.aims.entity.smartbi.postgres.SmartBiPgExcelUpload;
import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.config.smartbi.PythonSmartBIConfig;
import com.cretas.aims.exception.PythonServiceUnavailableException;
import com.cretas.aims.service.smartbi.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cretas.aims.repository.smartbi.postgres.SmartBiPgExcelUploadRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.validation.Valid;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.cretas.aims.util.ErrorSanitizer;
import com.cretas.aims.util.NormalizedFilenameMultipartFile;

/**
 * SmartBI Upload Controller
 *
 * Handles all Excel upload, batch processing, retry, data preview, and backfill endpoints.
 * AUDIT-085: Extracted from SmartBIController to reduce file size.
 *
 * @author Cretas Team
 * @since 2026-02-11
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/smart-bi")
@Tag(name = "SmartBI Upload", description = "SmartBI Excel upload and data management API")
public class SmartBIUploadController {

    /**
     * 单文件上传大小 sanity 上限 (300MB)。
     * 这只挡掉极端文件 (例如 251MB pivot CSV 整库导出)。真正防 OOM 的是 Python
     * 端按 cell budget 截断 (15M cells, 跟 CSV 路径一致), 见 fixed_executor.py 的
     * pd.read_excel nrows 截断逻辑。
     * 历史: 2026-04-29 v1 限 5MB → v2 限 2MB 都太激进伤 UX, 真问题是 xlsx 路径
     * 没有 cell-budget cap (CSV 路径有, 见 excel.py L1053)。修了 xlsx 路径之后
     * 这个上限只起 sanity 作用, 防 251MB+ 文件直接打爆 multipart parser 内存。
     */
    private static final long MAX_UPLOAD_BYTES = 300L * 1024 * 1024;

    /**
     * T-R5-1 (R5 audit §1 B1, 2026-05-12): Sync /upload route blocks the Tomcat
     * worker thread on Python `parseExcel` until the request body is fully
     * streamed and parsed. With 100 MB junk content this was observed to hang
     * 300 s+ with no terminating error — a DoS surface (one curl holds a worker
     * + a Python parse worker for 5 minutes). Reject early at 50 MB on /upload
     * (sync); larger files must use /upload-and-analyze which already routes to
     * Python async parse (line 204, `LARGE_FILE_ASYNC_BYTES`).
     */
    private static final long MAX_SYNC_UPLOAD_BYTES = 50L * 1024 * 1024;

    private final ExcelDynamicParserService excelParserService;
    private final SmartBIUploadFlowService uploadFlowService;
    private final PythonSmartBIClient pythonClient;
    private final PythonSmartBIConfig pythonConfig;
    private final ObjectMapper objectMapper;
    private final DynamicAnalysisService dynamicAnalysisService;
    private final SmartBiPgExcelUploadRepository pgUploadRepository;

    private ResponseEntity<ApiResponse<?>> rejectIfTooLarge(MultipartFile file) {
        if (file == null || file.getSize() <= MAX_UPLOAD_BYTES) return null;
        double mb = file.getSize() / 1024.0 / 1024.0;
        long limitMb = MAX_UPLOAD_BYTES / 1024 / 1024;
        log.warn("Upload rejected — file too large: name={} size={} bytes (limit {} bytes)",
                file.getOriginalFilename(), file.getSize(), MAX_UPLOAD_BYTES);
        String msg = String.format(
                "文件过大 (%.2f MB)，AI 分析仅支持 %d MB 以内的文件。建议按月/按门店拆分后上传。",
                mb, limitMb);
        return ResponseEntity.ok(ApiResponse.error(msg));
    }

    /**
     * T-R5-1: tighter cap for the synchronous /upload path so junk binary
     * uploads can't tie up a worker thread for 5 minutes. Routes user to the
     * async /upload-and-analyze endpoint which already supports >50 MB via the
     * Python async parse path.
     */
    private ResponseEntity<ApiResponse<?>> rejectIfTooLargeForSyncUpload(MultipartFile file) {
        if (file == null || file.getSize() <= MAX_SYNC_UPLOAD_BYTES) return null;
        double mb = file.getSize() / 1024.0 / 1024.0;
        long limitMb = MAX_SYNC_UPLOAD_BYTES / 1024 / 1024;
        log.warn("Sync /upload rejected — exceeds {}MB sync cap: name={} size={} bytes",
                limitMb, file.getOriginalFilename(), file.getSize());
        String msg = String.format(
                "文件过大 (%.2f MB)，同步 /upload 仅支持 %d MB 以内。请改用异步路径 /upload-and-analyze (支持最大 %d MB)。",
                mb, limitMb, MAX_UPLOAD_BYTES / 1024 / 1024);
        return ResponseEntity.ok(ApiResponse.error(msg));
    }

    private ResponseEntity<ApiResponse<?>> handleServiceUnavailable(PythonServiceUnavailableException e) {
        log.warn("Python SmartBI 服务熔断中: state={}, retryAfterMs={}",
                e.getCircuitState(), e.getRetryAfterMs());
        long retrySec = Math.max(1, (e.getRetryAfterMs() + 999) / 1000);
        String msg = String.format(
                "AI 分析服务正在自动恢复中，请 %d 秒后重试。如果反复失败，可能是当前文件过大或服务繁忙。",
                retrySec);
        return ResponseEntity.ok(ApiResponse.error(msg));
    }

    @Autowired
    public SmartBIUploadController(
            ExcelDynamicParserService excelParserService,
            @Autowired(required = false) SmartBIUploadFlowService uploadFlowService,
            PythonSmartBIClient pythonClient,
            PythonSmartBIConfig pythonConfig,
            ObjectMapper objectMapper,
            @Autowired(required = false) DynamicAnalysisService dynamicAnalysisService,
            @Autowired(required = false) SmartBiPgExcelUploadRepository pgUploadRepository) {
        this.excelParserService = excelParserService;
        this.uploadFlowService = uploadFlowService;
        this.pythonClient = pythonClient;
        this.pythonConfig = pythonConfig;
        this.objectMapper = objectMapper;
        this.dynamicAnalysisService = dynamicAnalysisService;
        this.pgUploadRepository = pgUploadRepository;
    }

    // ==================== Excel Upload ====================

    @RequirePermission({"analytics:read_write"})
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload Excel file", description = "Upload and parse Excel file using Python SmartBI service")
    public ResponseEntity<ApiResponse<ExcelParseResponse>> uploadExcel(
            @Parameter(description = "Factory ID") @PathVariable String factoryId,
            @Parameter(description = "Excel file") @RequestParam("file") MultipartFile file,
            @Parameter(description = "Data type: sales/finance/inventory") @RequestParam(required = false) String dataType,
            @Parameter(description = "Sheet index (0-based)") @RequestParam(required = false, defaultValue = "0") Integer sheetIndex,
            @Parameter(description = "Header row (0-based)") @RequestParam(required = false, defaultValue = "0") Integer headerRow,
            @Parameter(description = "Transpose data") @RequestParam(required = false, defaultValue = "false") Boolean transpose,
            @Parameter(description = "Row label column index for transpose") @RequestParam(required = false, defaultValue = "0") Integer rowLabelColumn,
            @Parameter(description = "Header row count for transpose") @RequestParam(required = false, defaultValue = "1") Integer headerRowCount) {

        // T-R5-2: defend against Tomcat ISO-8859-1 multipart filename mojibake.
        // Even with the application*.properties UTF-8 fix, an in-flight request
        // during a deploy window can still hit the old behavior — wrap once here
        // so downstream services (parseExcel, executeUploadFlow) see a clean name.
        file = new NormalizedFilenameMultipartFile(file);

        log.info("Upload Excel: factoryId={}, fileName={}, dataType={}, sheetIndex={}, headerRow={}, transpose={}",
                factoryId, file.getOriginalFilename(), dataType, sheetIndex, headerRow, transpose);

        ResponseEntity<ApiResponse<?>> sizeReject = rejectIfTooLarge(file);
        if (sizeReject != null) {
            // unchecked cast: ApiResponse<?> 与 ApiResponse<ExcelParseResponse> 在错误分支只用 message 字段
            @SuppressWarnings({"unchecked", "rawtypes"})
            ResponseEntity typed = sizeReject;
            return typed;
        }

        // T-R5-1: drop sync /upload cap to 50MB so junk binary can't hang a worker.
        ResponseEntity<ApiResponse<?>> syncReject = rejectIfTooLargeForSyncUpload(file);
        if (syncReject != null) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            ResponseEntity typed = syncReject;
            return typed;
        }

        if (!pythonConfig.isEnabled()) {
            return ResponseEntity.ok(ApiResponse.error("Python SmartBI service not enabled"));
        }
        if (!pythonClient.isAvailable()) {
            return ResponseEntity.ok(ApiResponse.error(
                    "AI 分析服务正在自动恢复中，请稍后重试。如果反复失败，可能是当前文件过大或服务繁忙。"));
        }

        try {
            int headerRows;
            if (headerRow == null || headerRow < 0) {
                headerRows = 0;
                log.debug("Using Python auto-detect for header rows");
            } else {
                headerRows = headerRow + 1;
            }
            ExcelParseResponse response = pythonClient.parseExcel(file, factoryId, dataType, sheetIndex, headerRows);

            if (response == null || !response.isSuccess()) {
                String errorMsg = response != null ? response.getErrorMessage() : "Python service returned null";
                return ResponseEntity.ok(ApiResponse.error("Excel parse failed: " + errorMsg));
            }

            return ResponseEntity.ok(ApiResponse.success("Excel parsed successfully", response));
        } catch (PythonServiceUnavailableException e) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            ResponseEntity typed = handleServiceUnavailable(e);
            return typed;
        } catch (IOException e) {
            log.error("Excel file read failed: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("File read failed: " + ErrorSanitizer.sanitize(e)));
        } catch (Exception e) {
            log.error("Excel parse error: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("Parse failed: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @PostMapping(value = "/upload-and-analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload and analyze", description = "Upload Excel, auto-parse, save data and generate chart analysis")
    public ResponseEntity<ApiResponse<?>> uploadAndAnalyze(
            @Parameter(description = "Factory ID") @PathVariable String factoryId,
            @Parameter(description = "Excel file") @RequestParam("file") MultipartFile file,
            @Parameter(description = "Data type") @RequestParam(required = false) String dataType,
            @Parameter(description = "Sheet index") @RequestParam(required = false, defaultValue = "0") Integer sheetIndex,
            @Parameter(description = "Header row") @RequestParam(required = false, defaultValue = "0") Integer headerRow,
            @Parameter(description = "Auto confirm field mappings") @RequestParam(name = "auto_confirm", required = false, defaultValue = "false") Boolean autoConfirm,
            @Parameter(description = "Transpose data") @RequestParam(required = false, defaultValue = "false") Boolean transpose,
            @Parameter(description = "Row label column index") @RequestParam(required = false, defaultValue = "0") Integer rowLabelColumn,
            @Parameter(description = "Header row count") @RequestParam(required = false, defaultValue = "1") Integer headerRowCount,
            // Bug #25b (2026-04-18): multi-stacked-table region bounds (0-indexed, inclusive)
            @Parameter(description = "Selected region start row (Bug #25b)") @RequestParam(required = false) Integer selectedRegionStart,
            @Parameter(description = "Selected region end row (Bug #25b)") @RequestParam(required = false) Integer selectedRegionEnd) {

        // T-R5-2: normalize filename for the async route too (same persistence target).
        file = new NormalizedFilenameMultipartFile(file);

        log.info("Upload and analyze: factoryId={}, fileName={}, dataType={}, autoConfirm={}, region=[{},{}]",
                factoryId, file.getOriginalFilename(), dataType, autoConfirm,
                selectedRegionStart, selectedRegionEnd);

        ResponseEntity<ApiResponse<?>> sizeReject = rejectIfTooLarge(file);
        if (sizeReject != null) return sizeReject;

        if (uploadFlowService == null) {
            return ResponseEntity.ok(ApiResponse.error("SmartBI upload flow service not configured"));
        }
        if (!pythonConfig.isEnabled() || !pythonClient.isAvailable()) {
            return ResponseEntity.ok(ApiResponse.error(
                    "AI 分析服务正在自动恢复中，请稍后重试。如果反复失败，可能是当前文件过大或服务繁忙。"));
        }

        // 大文件（> 50MB）走 Python 异步解析路径，避免同步 HTTP 超时。
        // Python worker 流式读 + bulk insert，内存占用与文件大小无关。
        if (file.getSize() > PythonSmartBIClient.LARGE_FILE_ASYNC_BYTES) {
            log.info("Large file detected ({}MB), routing to async parse: file={} factory={}",
                    file.getSize() / 1024 / 1024, file.getOriginalFilename(), factoryId);
            try {
                com.cretas.aims.dto.smartbi.ExcelParseResponse asyncResult =
                        pythonClient.parseExcelViaAsync(file, factoryId, sheetIndex != null ? sheetIndex : 0,
                                selectedRegionStart, selectedRegionEnd);
                if (!asyncResult.isSuccess()) {
                    return ResponseEntity.ok(ApiResponse.error(asyncResult.getErrorMessage()));
                }
                return ResponseEntity.ok(ApiResponse.success("大文件解析完成（异步路径）", asyncResult));
            } catch (Exception e) {
                log.error("Async parse failed: {}", e.getMessage(), e);
                return ResponseEntity.ok(ApiResponse.error("大文件解析失败: " + ErrorSanitizer.sanitize(e)));
            }
        }

        try {
            SmartBIUploadFlowService.UploadFlowResult result = uploadFlowService.executeUploadFlow(
                    factoryId, file, dataType, sheetIndex, headerRow, Boolean.TRUE.equals(autoConfirm),
                    selectedRegionStart, selectedRegionEnd);
            if (result.isSuccess()) {
                return ResponseEntity.ok(ApiResponse.success(result.getMessage(), result));
            } else {
                return ResponseEntity.ok(ApiResponse.error(result.getMessage()));
            }
        } catch (PythonServiceUnavailableException e) {
            return handleServiceUnavailable(e);
        } catch (Exception e) {
            log.error("Upload and analyze failed: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("Upload and analyze failed: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @PostMapping("/upload/confirm")
    @Operation(summary = "Confirm field mappings", description = "Confirm field mappings, save data and generate chart")
    public ResponseEntity<ApiResponse<?>> confirmMappingsAndSave(
            @Parameter(description = "Factory ID") @PathVariable String factoryId,
            @RequestBody @Valid ConfirmMappingRequest request) {

        log.info("Confirm field mappings: factoryId={}, dataType={}, mappings={}",
                factoryId, request.getDataType(),
                request.getConfirmedMappings() != null ? request.getConfirmedMappings().size() : 0);

        if (uploadFlowService == null) {
            return ResponseEntity.ok(ApiResponse.error("Upload flow service not configured"));
        }

        try {
            List<FieldMappingResult> mappings = new java.util.ArrayList<>();
            if (request.getConfirmedMappings() != null) {
                request.getConfirmedMappings().forEach((original, standard) -> {
                    FieldMappingResult mapping = new FieldMappingResult();
                    mapping.setOriginalColumn(original);
                    mapping.setStandardField(standard);
                    mapping.setConfidence(100.0);
                    mappings.add(mapping);
                });
            }

            // Bug #43 fix: pass uploadId (pre-persisted during /upload-and-analyze)
            // so confirmAndPersist skips the 50-row-trim re-persist path.
            SmartBIUploadFlowService.UploadFlowResult result = uploadFlowService.confirmAndPersist(
                    factoryId, request.getUploadId(), request.getParseResponse(), mappings, request.getDataType());
            if (result.isSuccess()) {
                return ResponseEntity.ok(ApiResponse.success(result.getMessage(), result));
            } else {
                return ResponseEntity.ok(ApiResponse.error(result.getMessage()));
            }
        } catch (Exception e) {
            log.error("Confirm and save failed: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("Save failed: " + ErrorSanitizer.sanitize(e)));
        }
    }

    // ==================== Batch Sheet Processing ====================

    @RequirePermission({"analytics:read_write"})
    @PostMapping(value = "/sheets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "List sheets", description = "Preview all sheets in the Excel file")
    public ResponseEntity<ApiResponse<List<SheetInfo>>> listSheets(
            @Parameter(description = "Factory ID") @PathVariable String factoryId,
            @Parameter(description = "Excel file") @RequestParam("file") MultipartFile file) {

        log.info("List sheets: factoryId={}, fileName={}", factoryId, file.getOriginalFilename());

        ResponseEntity<ApiResponse<?>> sizeReject = rejectIfTooLarge(file);
        if (sizeReject != null) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            ResponseEntity typed = sizeReject;
            return typed;
        }

        try {
            List<SheetInfo> sheets = excelParserService.listSheets(file.getInputStream());
            return ResponseEntity.ok(ApiResponse.success("Success", sheets));
        } catch (IOException e) {
            log.error("File read failed: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("File read failed: " + ErrorSanitizer.sanitize(e)));
        } catch (Exception e) {
            log.error("List sheets failed: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("Failed: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @PostMapping(value = "/upload-batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Batch upload sheets", description = "Upload and process multiple sheets from Excel file")
    public ResponseEntity<ApiResponse<BatchUploadResult>> uploadBatch(
            @Parameter(description = "Factory ID") @PathVariable String factoryId,
            @Parameter(description = "Excel file") @RequestParam("file") MultipartFile file,
            @Parameter(description = "Sheet configs JSON array") @RequestParam("sheetConfigs") String sheetConfigsJson) {

        log.info("Batch upload: factoryId={}, fileName={}", factoryId, file.getOriginalFilename());

        ResponseEntity<ApiResponse<?>> sizeReject = rejectIfTooLarge(file);
        if (sizeReject != null) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            ResponseEntity typed = sizeReject;
            return typed;
        }

        if (uploadFlowService == null) {
            return ResponseEntity.ok(ApiResponse.error("Batch upload service not available"));
        }

        try {
            List<SheetConfig> configs = objectMapper.readValue(sheetConfigsJson,
                    new TypeReference<List<SheetConfig>>() {});

            if (configs == null || configs.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error("sheetConfigs cannot be empty"));
            }

            BatchUploadResult result = uploadFlowService.executeBatchUpload(
                    factoryId, file.getInputStream(), file.getOriginalFilename(), configs);

            String statusPrefix;
            if (result.isAllSuccess()) {
                statusPrefix = "";
            } else if (result.getRequiresConfirmationCount() > 0) {
                statusPrefix = "Pending confirmation: ";
            } else if (result.isPartialSuccess()) {
                statusPrefix = "Partial success: ";
            } else {
                statusPrefix = "Failed: ";
            }
            return ResponseEntity.ok(ApiResponse.success(statusPrefix + result.getMessage(), result));

        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("Parse sheetConfigs failed: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("sheetConfigs format error: " + ErrorSanitizer.sanitize(e)));
        } catch (IOException e) {
            log.error("File read failed: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("File read failed: " + ErrorSanitizer.sanitize(e)));
        } catch (Exception e) {
            log.error("Batch upload failed: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("Batch upload failed: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @PostMapping(value = "/upload-batch-stream", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Batch upload sheets (streaming)", description = "SSE streaming progress for batch sheet upload")
    public SseEmitter uploadBatchStream(
            @Parameter(description = "Factory ID") @PathVariable String factoryId,
            @Parameter(description = "Excel file") @RequestParam("file") MultipartFile file,
            @Parameter(description = "Sheet configs JSON array") @RequestParam("sheetConfigs") String sheetConfigsJson) {

        log.info("Batch upload (streaming): factoryId={}, fileName={}", factoryId, file.getOriginalFilename());

        SseEmitter emitter = new SseEmitter(600000L);

        new Thread(() -> {
            try {
                if (file != null && file.getSize() > MAX_UPLOAD_BYTES) {
                    double mb = file.getSize() / 1024.0 / 1024.0;
                    long limitMb = MAX_UPLOAD_BYTES / 1024 / 1024;
                    log.warn("Stream upload rejected — file too large: name={} size={} bytes",
                            file.getOriginalFilename(), file.getSize());
                    sendEvent(emitter, UploadProgressEvent.error(String.format(
                            "文件过大 (%.2f MB)，AI 分析仅支持 %d MB 以内的文件。建议按月/按门店拆分后上传。",
                            mb, limitMb)));
                    emitter.complete();
                    return;
                }
                if (uploadFlowService == null) {
                    sendEvent(emitter, UploadProgressEvent.error("Batch upload service not available"));
                    emitter.complete();
                    return;
                }

                List<SheetConfig> configs = objectMapper.readValue(sheetConfigsJson,
                        new TypeReference<List<SheetConfig>>() {});

                if (configs == null || configs.isEmpty()) {
                    sendEvent(emitter, UploadProgressEvent.error("sheetConfigs cannot be empty"));
                    emitter.complete();
                    return;
                }

                BatchUploadResult result = uploadFlowService.executeBatchUploadWithProgress(
                        factoryId, file.getInputStream(), file.getOriginalFilename(), configs,
                        event -> sendEvent(emitter, event));

                sendEvent(emitter, UploadProgressEvent.complete(result));
                emitter.complete();

            } catch (Exception e) {
                log.error("Batch upload (streaming) failed: {}", e.getMessage(), e);
                try {
                    sendEvent(emitter, UploadProgressEvent.error(ErrorSanitizer.sanitize(e)));
                    emitter.complete();
                } catch (Exception ex) {
                    emitter.completeWithError(ex);
                }
            }
        }, "upload-stream-" + System.currentTimeMillis()).start();

        emitter.onCompletion(() -> log.debug("SSE connection completed"));
        emitter.onTimeout(() -> log.warn("SSE connection timeout"));
        emitter.onError(e -> log.error("SSE connection error: {}", ErrorSanitizer.sanitize(e)));

        return emitter;
    }

    // ==================== Sheet Retry ====================

    @RequirePermission({"analytics:read_write"})
    @PostMapping("/retry-sheet/{uploadId}")
    @Operation(summary = "Retry failed sheet", description = "Re-parse and persist failed or stuck sheet data")
    public ResponseEntity<ApiResponse<Map<String, Object>>> retrySheet(
            @Parameter(description = "Factory ID") @PathVariable String factoryId,
            @Parameter(description = "Upload record ID") @PathVariable Long uploadId) {

        log.info("Retry sheet upload: factoryId={}, uploadId={}", factoryId, uploadId);

        if (uploadFlowService == null) {
            return ResponseEntity.ok(ApiResponse.error("Upload service not available"));
        }

        try {
            SmartBIUploadFlowService.UploadFlowResult result = uploadFlowService.retrySheetUpload(factoryId, uploadId);

            if (result.isSuccess()) {
                Map<String, Object> data = new HashMap<>();
                data.put("uploadId", result.getUploadId());
                data.put("message", result.getMessage());
                if (result.getParseResult() != null) {
                    data.put("rowCount", result.getParseResult().getRowCount());
                    data.put("headers", result.getParseResult().getHeaders());
                }
                return ResponseEntity.ok(ApiResponse.success("Retry succeeded", data));
            } else {
                return ResponseEntity.ok(ApiResponse.error(result.getMessage()));
            }
        } catch (Exception e) {
            log.error("Retry sheet failed: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("Retry failed: " + ErrorSanitizer.sanitize(e)));
        }
    }

    // ==================== Upload History & Data Preview ====================

    @RequirePermission({"analytics:read"})
    @GetMapping("/uploads")
    @Operation(summary = "Get upload history", description = "Get uploaded Excel files for the factory (paginated, lightweight)")
    public ResponseEntity<ApiResponse<?>> getUploadHistory(
            @Parameter(description = "Factory ID") @PathVariable String factoryId,
            @Parameter(description = "Status filter") @RequestParam(required = false) String status,
            @Parameter(description = "Page number (0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "50") int size) {

        log.info("Get upload history: factoryId={}, status={}, page={}, size={}", factoryId, status, page, size);

        if (pgUploadRepository == null) {
            // Issue #290: still wrap in paginated shape so FE upload history page
            // can render an "empty" state instead of crashing on data:null.
            return ResponseEntity.ok(ApiResponse.success(emptyPageMap(size)));
        }

        try {
            org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(
                    page, Math.min(size, 200));
            org.springframework.data.domain.Page<UploadHistoryDTO> dtoPage =
                    pgUploadRepository.findUploadHistoryLightweight(factoryId, pageable);
            // Issue #290: Spring Boot 3.2+ deprecated default Page Jackson
            // serialization → emits {} or null at FE which broke the upload
            // history page (data:null evidence in PR #286 §X.0). Explicit Map
            // wrapper mirrors what /uploads/{id}/data already does and matches
            // SmartBIConfigController.pageToMap shape.
            return ResponseEntity.ok(ApiResponse.success(pageToMap(dtoPage)));
        } catch (Exception e) {
            log.error("Get upload history failed: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("Get upload history failed: " + ErrorSanitizer.sanitize(e)));
        }
    }

    /**
     * Convert Spring Data Page to plain Map for stable Jackson serialization
     * (Spring Boot 3.2+ deprecated default Page JSON encoding — Issue #290).
     */
    private static Map<String, Object> pageToMap(org.springframework.data.domain.Page<?> page) {
        Map<String, Object> body = new HashMap<>();
        body.put("content", page.getContent());
        body.put("totalElements", page.getTotalElements());
        body.put("totalPages", page.getTotalPages());
        body.put("size", page.getSize());
        body.put("number", page.getNumber());
        body.put("first", page.isFirst());
        body.put("last", page.isLast());
        body.put("numberOfElements", page.getNumberOfElements());
        body.put("empty", page.isEmpty());
        return body;
    }

    /**
     * Empty paginated wrapper for the "repository unavailable" fallback path.
     */
    private static Map<String, Object> emptyPageMap(int size) {
        Map<String, Object> body = new HashMap<>();
        body.put("content", java.util.Collections.emptyList());
        body.put("totalElements", 0L);
        body.put("totalPages", 0);
        body.put("size", size);
        body.put("number", 0);
        body.put("first", true);
        body.put("last", true);
        body.put("numberOfElements", 0);
        body.put("empty", true);
        return body;
    }

    // BUG-01-RBAC-001: finance raw-data endpoints require finance:read_write.
    // analytics:read is granted to sales_manager/restaurant_manager/etc., so it cannot guard
    // finance-sensitive raw rows. Only finance_manager / factory_super_admin / restaurant_owner
    // hold finance:read_write. This mirrors the FinanceReportController class-level guard.
    @RequirePermission({"finance:read_write"})
    @GetMapping("/uploads/{uploadId}/fields")
    @Operation(summary = "Get upload fields", description = "Get field definitions for uploaded data")
    public ResponseEntity<ApiResponse<List<DynamicAnalysisService.FieldDefinitionDTO>>> getUploadFields(
            @Parameter(description = "Factory ID") @PathVariable String factoryId,
            @Parameter(description = "Upload ID") @PathVariable Long uploadId) {

        log.info("Get upload fields: factoryId={}, uploadId={}", factoryId, uploadId);

        if (dynamicAnalysisService == null) {
            return ResponseEntity.ok(ApiResponse.error("Dynamic analysis service not enabled"));
        }

        try {
            List<DynamicAnalysisService.FieldDefinitionDTO> fields =
                    dynamicAnalysisService.getFieldDefinitions(uploadId);
            return ResponseEntity.ok(ApiResponse.success(fields));
        } catch (Exception e) {
            log.error("Get upload fields failed: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("Get fields failed: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"finance:read_write"})
    @GetMapping("/uploads/{uploadId}/data")
    @Operation(summary = "Get upload data", description = "Paginated view of persisted Excel data rows")
    public ResponseEntity<ApiResponse<TableDataResponse>> getUploadData(
            @Parameter(description = "Factory ID") @PathVariable String factoryId,
            @Parameter(description = "Upload ID") @PathVariable Long uploadId,
            @Parameter(description = "Page (0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "50") int size) {

        log.info("Get upload data: factoryId={}, uploadId={}, page={}, size={}", factoryId, uploadId, page, size);

        if (dynamicAnalysisService == null) {
            return ResponseEntity.ok(ApiResponse.error("Dynamic analysis service not enabled"));
        }

        try {
            List<DynamicAnalysisService.FieldDefinitionDTO> fields =
                    dynamicAnalysisService.getFieldDefinitions(uploadId);
            List<String> headers = fields.stream()
                    .map(DynamicAnalysisService.FieldDefinitionDTO::getOriginalName)
                    .collect(java.util.stream.Collectors.toList());

            org.springframework.data.domain.Page<SmartBiDynamicData> dataPage =
                    dynamicAnalysisService.getDataPage(factoryId, uploadId, page, size);

            List<Map<String, Object>> rows = dataPage.getContent().stream()
                    .map(SmartBiDynamicData::getRowData)
                    .collect(java.util.stream.Collectors.toList());

            TableDataResponse response = TableDataResponse.builder()
                    .headers(headers)
                    .data(rows)
                    .total(dataPage.getTotalElements())
                    .page(page)
                    .size(size)
                    .totalPages(dataPage.getTotalPages())
                    .build();

            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            log.error("Get upload data failed: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("Get data failed: " + ErrorSanitizer.sanitize(e)));
        }
    }

    // ==================== Field Definition Backfill ====================

    @RequirePermission({"analytics:read"})
    @GetMapping("/uploads-missing-fields")
    @Operation(summary = "Diagnose missing field definitions", description = "Count uploads missing field definitions")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getUploadsMissingFields(
            @Parameter(description = "Factory ID") @PathVariable String factoryId) {

        log.info("Diagnose missing fields: factoryId={}", factoryId);

        if (pgUploadRepository == null) {
            return ResponseEntity.ok(ApiResponse.error("PostgreSQL not enabled"));
        }

        try {
            List<SmartBiPgExcelUpload> allUploads = pgUploadRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId);
            int totalCount = allUploads.size();
            int missingCount = 0;

            for (SmartBiPgExcelUpload upload : allUploads) {
                if (dynamicAnalysisService != null) {
                    long fieldCount = dynamicAnalysisService.getFieldCount(upload.getId());
                    if (fieldCount == 0) {
                        missingCount++;
                    }
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("totalUploads", totalCount);
            result.put("missingFieldsCount", missingCount);
            result.put("hasIssues", missingCount > 0);

            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            log.error("Diagnose failed: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("Diagnose failed: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @PostMapping("/backfill/fields/{uploadId}")
    @Operation(summary = "Backfill field definitions", description = "Rebuild missing field definitions from field_mappings")
    public ResponseEntity<ApiResponse<BackfillResult>> backfillFieldDefinitions(
            @Parameter(description = "Factory ID") @PathVariable String factoryId,
            @Parameter(description = "Upload ID") @PathVariable Long uploadId) {

        log.info("Backfill field definitions: factoryId={}, uploadId={}", factoryId, uploadId);

        if (dynamicAnalysisService == null || pgUploadRepository == null) {
            return ResponseEntity.ok(ApiResponse.error("Dynamic analysis service not enabled"));
        }

        try {
            BackfillResult result = dynamicAnalysisService.backfillFieldDefinitions(factoryId, uploadId);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            log.error("Backfill failed: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("Backfill failed: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @PostMapping("/backfill/batch")
    @Operation(summary = "Batch backfill field definitions", description = "Backfill all uploads missing field definitions")
    public ResponseEntity<ApiResponse<BatchBackfillResult>> batchBackfill(
            @Parameter(description = "Factory ID") @PathVariable String factoryId,
            @Parameter(description = "Max items to process") @RequestParam(defaultValue = "100") int limit) {

        log.info("Batch backfill: factoryId={}, limit={}", factoryId, limit);

        if (dynamicAnalysisService == null || pgUploadRepository == null) {
            return ResponseEntity.ok(ApiResponse.error("Dynamic analysis service not enabled"));
        }

        try {
            BatchBackfillResult result = dynamicAnalysisService.batchBackfillFieldDefinitions(factoryId, limit);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            log.error("Batch backfill failed: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("Batch backfill failed: " + ErrorSanitizer.sanitize(e)));
        }
    }

    // ==================== Helper ====================

    private void sendEvent(SseEmitter emitter, UploadProgressEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.getType().name().toLowerCase())
                    .data(event, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.warn("Send SSE event failed: {}", e.getMessage());
        }
    }
}
