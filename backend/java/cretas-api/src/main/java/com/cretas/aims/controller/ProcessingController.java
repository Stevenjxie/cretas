package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.dto.MobileDTO;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.batch.AssignWorkersDTO;
import com.cretas.aims.dto.batch.TeamBatchReportRequest;
import com.cretas.aims.dto.batch.WorkerCheckoutDTO;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.processing.CreateMaterialReceiptRequest;
import com.cretas.aims.dto.processing.CreateProductionBatchRequest;
import com.cretas.aims.dto.processing.ProcessingStageRecordDTO;
import com.cretas.aims.dto.quality.*;
import com.cretas.aims.dto.yield.OutputOptionsResponse;
import com.cretas.aims.service.wip.WipInventoryService;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.enums.ProcessingStageType;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.service.AIEnterpriseService;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.ProcessingService;
import com.cretas.aims.service.ProcessingStageRecordService;
import com.cretas.aims.service.QualityDispositionRuleService;
import com.cretas.aims.service.SpecialApprovalService;
import com.cretas.aims.utils.TokenUtils;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import com.cretas.aims.util.ErrorSanitizer;

/**
 * 生产加工控制器
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/processing")
@RequiredArgsConstructor
@Tag(name = "生产加工管理")
@RequireModule("production_plan")
public class ProcessingController {

    private final ProcessingService processingService;
    private final MobileService mobileService;
    private final AIEnterpriseService aiEnterpriseService;
    private final ProcessingStageRecordService stageRecordService;
    private final QualityDispositionRuleService qualityDispositionRuleService;
    private final SpecialApprovalService specialApprovalService;
    private final QualityInspectionRepository qualityInspectionRepository;
    private final WipInventoryService wipInventoryService;

    // ========== 批次管理接口 ==========

    /**
     * 创建生产批次
     */
    @RequirePermission({"production:read_write"})
    @PostMapping("/batches")
    @Operation(summary = "创建生产批次", description = "创建新的生产批次")
    public ApiResponse<ProductionBatch> createBatch(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @Valid @RequestBody CreateProductionBatchRequest request) {
        log.info("创建生产批次: factoryId={}, batchNumber={}", factoryId, request.getBatchNumber());
        ProductionBatch batch = toProductionBatch(request);
        ProductionBatch result = processingService.createBatch(factoryId, batch);
        return ApiResponse.success(result);
    }

    /**
     * 开始生产
     */
    @RequirePermission({"production:read_write"})
    @PostMapping("/batches/{batchId}/start")
    @Operation(summary = "开始生产", description = "开始批次生产")
    public ApiResponse<ProductionBatch> startProduction(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "批次ID", example = "123") String batchId,
            @RequestParam @Parameter(description = "负责人ID", example = "1") Integer supervisorId) {
        log.info("开始生产: factoryId={}, batchId={}", factoryId, batchId);
        ProductionBatch result = processingService.startProduction(factoryId, batchId, supervisorId);
        return ApiResponse.success(result);
    }

    /**
     * 暂停生产
     */
    @RequirePermission({"production:read_write"})
    @PostMapping("/batches/{batchId}/pause")
    @Operation(summary = "暂停生产", description = "暂停批次生产")
    public ApiResponse<ProductionBatch> pauseProduction(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "批次ID", example = "123") String batchId,
            @RequestParam @Parameter(description = "暂停原因", example = "设备故障") String reason) {
        log.info("暂停生产: factoryId={}, batchId={}, reason={}", factoryId, batchId, reason);
        ProductionBatch result = processingService.pauseProduction(factoryId, batchId, reason);
        return ApiResponse.success(result);
    }

    /**
     * 恢复生产
     */
    @RequirePermission({"production:read_write"})
    @PostMapping("/batches/{batchId}/resume")
    @Operation(summary = "恢复生产", description = "恢复已暂停的批次生产")
    public ApiResponse<ProductionBatch> resumeProduction(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "批次ID", example = "123") String batchId) {
        log.info("恢复生产: factoryId={}, batchId={}", factoryId, batchId);
        ProductionBatch result = processingService.resumeProduction(factoryId, batchId);
        return ApiResponse.success(result);
    }

    /**
     * 完成生产
     */
    @RequirePermission({"production:read_write"})
    @PostMapping("/batches/{batchId}/complete")
    @Operation(summary = "完成生产", description = "完成批次生产")
    public ApiResponse<ProductionBatch> completeProduction(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "批次ID", example = "123") String batchId,
            @RequestParam @Parameter(description = "实际产量", example = "100.5") BigDecimal actualQuantity,
            @RequestParam @Parameter(description = "良品数量", example = "98.0") BigDecimal goodQuantity,
            @RequestParam @Parameter(description = "不良品数量", example = "2.5") BigDecimal defectQuantity) {
        log.info("完成生产: factoryId={}, batchId={}, actualQuantity={}", factoryId, batchId, actualQuantity);
        ProductionBatch result = processingService.completeProduction(
                factoryId, batchId, actualQuantity, goodQuantity, defectQuantity);
        return ApiResponse.success(result);
    }

    /**
     * 取消生产
     */
    @RequirePermission({"production:read_write"})
    @PostMapping("/batches/{batchId}/cancel")
    @Operation(summary = "取消生产", description = "取消批次生产")
    public ApiResponse<ProductionBatch> cancelProduction(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "批次ID", example = "123") String batchId,
            @RequestParam @Parameter(description = "取消原因", example = "订单取消") String reason) {
        log.info("取消生产: factoryId={}, batchId={}, reason={}", factoryId, batchId, reason);
        ProductionBatch result = processingService.cancelProduction(factoryId, batchId, reason);
        return ApiResponse.success(result);
    }

    /**
     * 获取批次详情
     */
    @GetMapping("/batches/{batchId}")
    @Operation(summary = "获取批次详情", description = "获取生产批次详细信息")
    public ApiResponse<ProductionBatch> getBatchById(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "批次ID", example = "123") String batchId) {
        log.info("获取批次详情: factoryId={}, batchId={}", factoryId, batchId);
        ProductionBatch batch = processingService.getBatchById(factoryId, batchId);
        return ApiResponse.success(batch);
    }

    /**
     * 获取批次列表
     */
    @GetMapping("/batches-with-clerk-wip")
    @Operation(summary = "获取含小结批次的批次列表", description = "分页获取生产批次列表，包含小结生成的 CLERK_WIP 批次")
    public ApiResponse<PageResponse<ProductionBatch>> getBatchesWithClerkWip(
            @PathVariable String factoryId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long supervisorId,
            @RequestParam(required = false) Boolean isTrial,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        log.info("Get production batches with clerk WIP: factoryId={}, status={}, supervisorId={}, isTrial={}",
                factoryId, status, supervisorId, isTrial);
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPage(page);
        pageRequest.setSize(size);
        PageResponse<ProductionBatch> result = processingService.getBatches(
                factoryId, status, supervisorId, isTrial, true, pageRequest);
        return ApiResponse.success(result);
    }

    @GetMapping("/batches")
    @Operation(summary = "获取批次列表", description = "分页获取生产批次列表")
    public ApiResponse<PageResponse<ProductionBatch>> getBatches(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @RequestParam(required = false) @Parameter(description = "状态", example = "PROCESSING") String status,
            @RequestParam(required = false) @Parameter(description = "主管ID(车间主管过滤)") Long supervisorId,
            @RequestParam(required = false) @Parameter(description = "仅试制批次(SP10中报价下拉)") Boolean isTrial,
            @RequestParam(defaultValue = "1") @Parameter(description = "页码", example = "1") Integer page,
            @RequestParam(defaultValue = "20") @Parameter(description = "每页大小", example = "20") Integer size) {
        log.info("获取批次列表: factoryId={}, status={}, supervisorId={}, isTrial={}", factoryId, status, supervisorId, isTrial);
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPage(page);
        pageRequest.setSize(size);
        PageResponse<ProductionBatch> result = processingService.getBatches(factoryId, status, supervisorId, isTrial, pageRequest);
        return ApiResponse.success(result);
    }

    /**
     * 获取批次时间线
     */
    @GetMapping("/batches/{batchId}/timeline")
    @Operation(summary = "获取批次时间线", description = "获取批次生产时间线")
    public ApiResponse<List<Map<String, Object>>> getBatchTimeline(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "批次ID", example = "123") String batchId) {
        log.info("获取批次时间线: factoryId={}, batchId={}", factoryId, batchId);
        List<Map<String, Object>> timeline = processingService.getBatchTimeline(factoryId, batchId);
        return ApiResponse.success(timeline);
    }

    /**
     * SP1 T4 — 获取批次半成品产出选项
     *
     * <p>RN 报工屏幕在 outputKind=SEMI|BOTH 时调用此接口，填充"半成品产出编号"下拉框。
     * 返回该批次下所有配置了 {@code semiFinishedOutputCode} 的工序任务列表。
     */
    @GetMapping("/batches/{batchId}/output-options")
    @Operation(summary = "获取批次半成品产出选项",
               description = "返回该批次下配置了半成品产出编号的工序任务列表，用于 RN 报工屏幕选择半成品产出码")
    public ApiResponse<OutputOptionsResponse> getBatchOutputOptions(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "批次ID", example = "123") Long batchId) {
        log.info("获取批次半成品产出选项: factoryId={}, batchId={}", factoryId, batchId);
        OutputOptionsResponse response = wipInventoryService.getOutputOptions(factoryId, batchId);
        return ApiResponse.success(response);
    }

    // ========== 批次员工分配接口 ==========

    /**
     * 分配员工到批次
     */
    @RequirePermission({"production:read_write"})
    @PostMapping("/batches/{batchId}/assign-workers")
    @Operation(summary = "分配员工到批次", description = "将多个员工分配到生产批次")
    public ApiResponse<List<Map<String, Object>>> assignWorkersToBatch(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "批次ID", example = "123") Long batchId,
            @RequestBody @Valid @Parameter(description = "分配请求") AssignWorkersDTO request) {
        log.info("分配员工到批次: factoryId={}, batchId={}, workerCount={}",
                factoryId, batchId, request.getWorkerIds().size());
        List<Map<String, Object>> results = processingService.assignWorkersToBatch(
                factoryId, batchId, request.getWorkerIds(), request.getAssignedBy(), request.getNotes());
        return ApiResponse.success(results);
    }

    /**
     * 员工签出（完成批次工作）
     */
    @RequirePermission({"production:read_write"})
    @PostMapping("/batches/{batchId}/workers/{workerId}/checkout")
    @Operation(summary = "员工签出", description = "员工完成批次工作并签出")
    public ApiResponse<Map<String, Object>> workerCheckout(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "批次ID", example = "123") Long batchId,
            @PathVariable @Parameter(description = "员工ID", example = "1") Long workerId,
            @RequestBody(required = false) @Parameter(description = "签出信息") WorkerCheckoutDTO request) {
        log.info("员工签出: factoryId={}, batchId={}, workerId={}", factoryId, batchId, workerId);
        Integer workMinutes = request != null ? request.getWorkMinutes() : null;
        String notes = request != null ? request.getNotes() : null;
        Map<String, Object> result = processingService.workerCheckout(
                factoryId, batchId, workerId, workMinutes, notes);
        return ApiResponse.success(result);
    }

    /**
     * 获取批次员工列表
     */
    @GetMapping("/batches/{batchId}/workers")
    @Operation(summary = "获取批次员工", description = "获取分配到批次的所有员工及其工时状态")
    public ApiResponse<List<Map<String, Object>>> getBatchWorkers(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "批次ID", example = "123") Long batchId) {
        log.info("获取批次员工: factoryId={}, batchId={}", factoryId, batchId);
        List<Map<String, Object>> workers = processingService.getBatchWorkers(factoryId, batchId);
        return ApiResponse.success(workers);
    }

    /**
     * 取消员工批次分配
     */
    @RequirePermission({"production:read_write"})
    @DeleteMapping("/batches/{batchId}/workers/{workerId}")
    @Operation(summary = "取消员工分配", description = "取消员工在批次的分配")
    public ApiResponse<Map<String, Object>> cancelWorkerAssignment(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "批次ID", example = "123") Long batchId,
            @PathVariable @Parameter(description = "员工ID", example = "1") Long workerId) {
        log.info("取消员工分配: factoryId={}, batchId={}, workerId={}", factoryId, batchId, workerId);
        Map<String, Object> result = processingService.cancelWorkerAssignment(factoryId, batchId, workerId);
        return ApiResponse.success(result);
    }

    // ========== 班组批量报工接口 ==========

    /**
     * 班组批量报工
     * 车间主管为班组成员批量提交产出报工
     */
    @RequirePermission({"production:read_write"})
    @PostMapping("/work-sessions/batch-report")
    @Operation(summary = "班组批量报工", description = "车间主管为班组成员批量提交产出")
    public ApiResponse<Map<String, Object>> submitTeamBatchReport(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @RequestBody @Valid @Parameter(description = "班组报工请求") TeamBatchReportRequest request) {
        log.info("班组批量报工: factoryId={}, batchId={}, totalOutput={}, memberCount={}",
                factoryId, request.getBatchId(), request.getTotalOutput(),
                request.getMembers() != null ? request.getMembers().size() : 0);
        Map<String, Object> result = processingService.submitTeamBatchReport(factoryId, request);
        return ApiResponse.success(result);
    }

    // ========== 原材料管理接口 ==========

    /**
     * 创建原材料接收记录
     */
    @RequirePermission({"production:read_write"})
    @PostMapping("/material-receipt")
    @Operation(summary = "原材料接收", description = "创建原材料接收记录")
    public ApiResponse<MaterialBatch> createMaterialReceipt(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @RequestHeader(value = "Authorization", required = false) @Parameter(description = "访问令牌", example = "Bearer eyJhbGciOiJIUzI1NiJ9...") String authorization,
            @Valid @RequestBody @Parameter(description = "原材料接收请求") CreateMaterialReceiptRequest request) {
        log.info("原材料接收: factoryId={}, batchNumber={}", factoryId, request.getBatchNumber());

        MaterialBatch materialBatch = toMaterialBatch(request);

        // 获取当前用户ID（如果提供了token）
        Long userId = null;
        if (authorization != null && !authorization.trim().isEmpty()) {
            try {
                String token = TokenUtils.extractToken(authorization);
                var userDTO = mobileService.getUserFromToken(token);
                userId = userDTO.getId();
            } catch (Exception e) {
                log.warn("无法从token获取用户信息: {}", e.getMessage());
            }
        }

        // 设置创建者ID
        if (userId != null) {
            materialBatch.setCreatedBy(userId);
        }

        MaterialBatch result = processingService.createMaterialReceipt(factoryId, materialBatch);
        return ApiResponse.success(result);
    }

    /**
     * 获取原材料列表
     */
    @GetMapping("/materials")
    @Operation(summary = "获取原材料列表", description = "分页获取原材料列表")
    public ApiResponse<PageResponse<MaterialBatch>> getMaterialReceipts(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @RequestParam(defaultValue = "1") @Parameter(description = "页码", example = "1") Integer page,
            @RequestParam(defaultValue = "20") @Parameter(description = "每页大小", example = "20") Integer size) {
        log.info("获取原材料列表: factoryId={}", factoryId);
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPage(page);
        pageRequest.setSize(size);
        PageResponse<MaterialBatch> result = processingService.getMaterialReceipts(factoryId, pageRequest);
        return ApiResponse.success(result);
    }

    /**
     * 记录原材料消耗
     */
    @RequirePermission({"production:read_write"})
    @PostMapping("/batches/{batchId}/material-consumption")
    @Operation(summary = "记录原材料消耗", description = "记录生产批次的原材料消耗")
    public ApiResponse<Void> recordMaterialConsumption(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "批次ID", example = "123") String batchId,
            @RequestBody @Parameter(description = "消耗记录") List<Map<String, Object>> consumptions,
            @RequestHeader("Authorization") @Parameter(description = "访问令牌", example = "Bearer eyJhbGciOiJIUzI1NiJ9...") String authorization) {
        // 从Token获取用户ID，用于设置recordedBy
        Long userId = null;
        try {
            String token = TokenUtils.extractToken(authorization);
            var userDTO = mobileService.getUserFromToken(token);
            userId = userDTO.getId();
        } catch (Exception e) {
            log.warn("无法从Token获取用户ID: {}", e.getMessage());
        }
        log.info("记录原材料消耗: factoryId={}, batchId={}, userId={}", factoryId, batchId, userId);
        processingService.recordMaterialConsumption(factoryId, batchId, consumptions, userId);
        return ApiResponse.success();
    }

    // ========== 质量检验接口 ==========

    /**
     * 提交质检记录
     */
    @RequirePermission({"quality:read_write"})
    @PostMapping("/quality/inspections")
    @Operation(summary = "提交质检记录", description = "提交产品质量检验记录")
    public ApiResponse<Map<String, Object>> submitInspection(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @RequestParam @Parameter(description = "批次ID", example = "123") String batchId,
            @RequestBody @Parameter(description = "质检信息") Map<String, Object> inspection,
            HttpServletRequest request) {
        log.info("提交质检记录: factoryId={}, batchId={}", factoryId, batchId);
        // 如果前端没传 inspectorId，从 JWT token 获取
        if (inspection.get("inspectorId") == null) {
            Long userId = (Long) request.getAttribute("userId");
            if (userId != null) {
                inspection.put("inspectorId", userId);
            }
        }
        Map<String, Object> result = processingService.submitInspection(factoryId, batchId, inspection);
        return ApiResponse.success(result);
    }

    /**
     * 获取质检记录
     */
    @GetMapping("/quality/inspections")
    @Operation(summary = "获取质检记录", description = "分页获取质检记录")
    public ApiResponse<PageResponse<Map<String, Object>>> getInspections(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @RequestParam(required = false) @Parameter(description = "批次ID", example = "123") String batchId,
            @RequestParam(defaultValue = "1") @Parameter(description = "页码", example = "1") Integer page,
            @RequestParam(defaultValue = "20") @Parameter(description = "每页大小", example = "20") Integer size) {
        log.info("获取质检记录: factoryId={}, batchId={}", factoryId, batchId);
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPage(page);
        pageRequest.setSize(size);
        PageResponse<Map<String, Object>> result = processingService.getInspections(factoryId, batchId, pageRequest);
        return ApiResponse.success(result);
    }

    /**
     * 获取质检详情
     */
    @GetMapping("/quality/inspections/{inspectionId}")
    @Operation(summary = "获取质检详情", description = "根据ID获取质检记录详情")
    public ApiResponse<Map<String, Object>> getInspectionById(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "质检记录ID", example = "QI-2025-001") String inspectionId) {
        log.info("获取质检详情: factoryId={}, inspectionId={}", factoryId, inspectionId);
        Map<String, Object> result = processingService.getInspectionById(factoryId, inspectionId);
        return ApiResponse.success(result);
    }

    /**
     * 获取质量统计
     */
    @GetMapping("/quality/statistics")
    @Operation(summary = "质量统计", description = "获取质量统计数据")
    public ApiResponse<Map<String, Object>> getQualityStatistics(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "开始日期", example = "2025-01-01") LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "结束日期", example = "2025-01-31") LocalDate endDate) {
        log.info("获取质量统计: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);
        Map<String, Object> statistics = processingService.getQualityStatistics(factoryId, startDate, endDate);
        return ApiResponse.success(statistics);
    }

    /**
     * 获取质量趋势
     */
    @GetMapping("/quality/trends")
    @Operation(summary = "质量趋势", description = "获取质量趋势分析")
    public ApiResponse<List<Map<String, Object>>> getQualityTrends(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @RequestParam(defaultValue = "30") @Parameter(description = "天数", example = "30") Integer days) {
        log.info("获取质量趋势: factoryId={}, days={}", factoryId, days);
        List<Map<String, Object>> trends = processingService.getQualityTrends(factoryId, days);
        return ApiResponse.success(trends);
    }

    // ========== 质检处置与特批放行接口 ==========

    /**
     * 评估处置建议
     * 根据质检结果，通过规则引擎评估推荐的处置动作
     */
    @RequirePermission({"quality:read_write"})
    @PostMapping("/quality/inspections/{inspectionId}/evaluate-disposition")
    @Operation(summary = "评估处置建议", description = "根据质检结果评估推荐的处置动作")
    public ApiResponse<DispositionEvaluationDTO> evaluateDisposition(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "质检记录ID", example = "QI-2025-001") String inspectionId) {
        log.info("评估处置建议: factoryId={}, inspectionId={}", factoryId, inspectionId);

        // 获取质检记录
        QualityInspection inspection = qualityInspectionRepository.findById(inspectionId)
                .orElseThrow(() -> new IllegalArgumentException("质检记录不存在: " + inspectionId));

        // 验证工厂ID
        if (!inspection.getFactoryId().equals(factoryId)) {
            throw new IllegalArgumentException("质检记录不属于该工厂");
        }

        QualityDispositionRuleService.DispositionResult result =
                qualityDispositionRuleService.evaluateDisposition(factoryId, inspection);

        // 构建质检摘要
        DispositionEvaluationDTO.InspectionSummary inspectionSummary =
                DispositionEvaluationDTO.InspectionSummary.builder()
                        .passRate(inspection.getPassRate())
                        .defectRate(inspection.getDefectRate())
                        .sampleSize(inspection.getSampleSize())
                        .passCount(inspection.getPassCount())
                        .failCount(inspection.getFailCount())
                        .inspectionResult(inspection.getResult())
                        .qualityGrade(inspection.getQualityGrade())
                        .build();

        // 转换备选动作
        List<DispositionEvaluationDTO.AlternativeAction> alternativeActions = null;
        if (result.getAlternativeActions() != null) {
            alternativeActions = java.util.Arrays.stream(result.getAlternativeActions())
                    .map(action -> DispositionEvaluationDTO.AlternativeAction.builder()
                            .action(action.name())
                            .description(getActionDescription(action))
                            .requiresApproval(action == QualityDispositionRuleService.DispositionAction.SPECIAL_APPROVAL)
                            .build())
                    .collect(java.util.stream.Collectors.toList());
        }

        // 转换为 DTO
        DispositionEvaluationDTO dto = DispositionEvaluationDTO.builder()
                .inspectionId(inspectionId)
                .productionBatchId(inspection.getProductionBatchId())
                .recommendedAction(result.getRecommendedAction().name())
                .recommendedActionDescription(getActionDescription(result.getRecommendedAction()))
                .requiresApproval(result.isRequiresApproval())
                .triggeredRuleName(result.getTriggeredRuleName())
                .ruleConfigId(result.getRuleConfigId())
                .ruleVersion(result.getRuleVersion())
                .confidence(result.getConfidence())
                .reason(result.getReason())
                .inspectionSummary(inspectionSummary)
                .alternativeActions(alternativeActions)
                .build();

        return ApiResponse.success(dto);
    }

    /**
     * 获取处置动作的中文描述
     */
    private String getActionDescription(QualityDispositionRuleService.DispositionAction action) {
        if (action == null) return null;
        switch (action) {
            case RELEASE: return "直接放行";
            case CONDITIONAL_RELEASE: return "条件放行";
            case REWORK: return "返工处理";
            case SCRAP: return "报废处理";
            case SPECIAL_APPROVAL: return "特批放行";
            case HOLD: return "暂扣待定";
            default: return action.name();
        }
    }

    /**
     * 执行处置动作
     * 直接执行处置动作（不需要审批的情况）
     */
    @RequirePermission({"quality:read_write"})
    @PostMapping("/quality/inspections/{inspectionId}/execute-disposition")
    @Operation(summary = "执行处置动作", description = "执行质检结果的处置动作")
    public ApiResponse<Map<String, Object>> executeDisposition(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "质检记录ID", example = "QI-2025-001") String inspectionId,
            @RequestBody @Valid @Parameter(description = "处置请求") DispositionRequest request,
            HttpServletRequest httpRequest) {
        log.info("执行处置动作: factoryId={}, inspectionId={}, action={}",
                factoryId, inspectionId, request.getRequestedAction());

        // 获取质检记录
        QualityInspection inspection = qualityInspectionRepository.findById(inspectionId)
                .orElseThrow(() -> new IllegalArgumentException("质检记录不存在: " + inspectionId));

        // 验证工厂ID
        if (!inspection.getFactoryId().equals(factoryId)) {
            throw new IllegalArgumentException("质检记录不属于该工厂");
        }

        // 获取当前用户ID
        Long executorId = request.getExecutorId();
        if (executorId == null) {
            String authorization = httpRequest.getHeader("Authorization");
            if (authorization != null) {
                try {
                    String token = TokenUtils.extractToken(authorization);
                    var userDTO = mobileService.getUserFromToken(token);
                    executorId = userDTO.getId();
                } catch (Exception e) {
                    log.warn("无法从token获取用户信息: {}", e.getMessage());
                }
            }
        }

        QualityDispositionRuleService.DispositionAction action =
                QualityDispositionRuleService.DispositionAction.valueOf(request.getRequestedAction());

        QualityDispositionRuleService.DispositionExecutionResult result =
                qualityDispositionRuleService.executeDisposition(
                        factoryId, inspection, action, executorId, request.getReason());

        Map<String, Object> response = Map.of(
                "success", result.isSuccess(),
                "message", result.getMessage(),
                "action", result.getExecutedAction() != null ? result.getExecutedAction().name() : null,
                "newBatchStatus", result.getNewBatchStatus() != null ? result.getNewBatchStatus() : "",
                "auditLogId", result.getAuditLogId() != null ? result.getAuditLogId() : ""
        );

        return ApiResponse.success(response);
    }

    /**
     * 提交特批放行申请
     * 当处置动作需要审批时，提交特批申请
     */
    @RequirePermission({"quality:read_write"})
    @PostMapping("/quality/inspections/{inspectionId}/request-special-approval")
    @Operation(summary = "提交特批申请", description = "提交质检特批放行申请")
    public ApiResponse<SpecialApprovalDTO> submitSpecialApproval(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "质检记录ID", example = "QI-2025-001") String inspectionId,
            @RequestBody @Valid @Parameter(description = "特批申请") SpecialApprovalRequest request,
            HttpServletRequest httpRequest) {
        log.info("提交特批申请: factoryId={}, inspectionId={}", factoryId, inspectionId);

        // 获取当前用户ID
        Long requesterId = null;
        String authorization = httpRequest.getHeader("Authorization");
        if (authorization != null) {
            try {
                String token = TokenUtils.extractToken(authorization);
                var userDTO = mobileService.getUserFromToken(token);
                requesterId = userDTO.getId();
            } catch (Exception e) {
                log.warn("无法从token获取用户信息: {}", e.getMessage());
                throw new IllegalArgumentException("无法获取申请人信息");
            }
        }

        if (requesterId == null) {
            throw new IllegalArgumentException("无法获取申请人信息");
        }

        SpecialApprovalDTO result = specialApprovalService.submitSpecialApproval(
                factoryId, inspectionId, request, requesterId);

        return ApiResponse.success(result);
    }

    /**
     * 获取待审批列表
     */
    @GetMapping("/quality/approvals/pending")
    @Operation(summary = "待审批列表", description = "获取质检特批待审批列表")
    public ApiResponse<List<SpecialApprovalDTO>> getPendingApprovals(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId) {
        log.info("获取待审批列表: factoryId={}", factoryId);
        List<SpecialApprovalDTO> approvals = specialApprovalService.getPendingApprovals(factoryId);
        return ApiResponse.success(approvals);
    }

    /**
     * 获取审批详情
     */
    @GetMapping("/quality/approvals/{approvalId}")
    @Operation(summary = "审批详情", description = "获取特批申请详情")
    public ApiResponse<SpecialApprovalDTO> getApprovalById(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "审批ID", example = "SA-2025-001") String approvalId) {
        log.info("获取审批详情: factoryId={}, approvalId={}", factoryId, approvalId);
        SpecialApprovalDTO approval = specialApprovalService.getApprovalById(factoryId, approvalId);
        return ApiResponse.success(approval);
    }

    /**
     * 处理审批决策
     */
    @RequirePermission({"quality:read_write"})
    @PostMapping("/quality/approvals/{approvalId}/decision")
    @Operation(summary = "处理审批", description = "审批或拒绝特批申请")
    public ApiResponse<SpecialApprovalDTO> processApprovalDecision(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "审批ID", example = "SA-2025-001") String approvalId,
            @RequestBody @Valid @Parameter(description = "审批决策") ApprovalDecisionRequest decision,
            HttpServletRequest httpRequest) {
        log.info("处理审批决策: factoryId={}, approvalId={}, decision={}",
                factoryId, approvalId, decision.getDecision());

        // 获取审批人ID
        Long approverId = decision.getApproverId();
        if (approverId == null) {
            String authorization = httpRequest.getHeader("Authorization");
            if (authorization != null) {
                try {
                    String token = TokenUtils.extractToken(authorization);
                    var userDTO = mobileService.getUserFromToken(token);
                    approverId = userDTO.getId();
                } catch (Exception e) {
                    log.warn("无法从token获取用户信息: {}", e.getMessage());
                    throw new IllegalArgumentException("无法获取审批人信息");
                }
            }
        }

        if (approverId == null) {
            throw new IllegalArgumentException("无法获取审批人信息");
        }

        SpecialApprovalDTO result = specialApprovalService.processDecision(
                factoryId, approvalId, decision, approverId);

        return ApiResponse.success(result);
    }

    /**
     * 获取我的申请记录
     */
    @GetMapping("/quality/approvals/my-requests")
    @Operation(summary = "我的申请", description = "获取当前用户的特批申请记录")
    public ApiResponse<List<SpecialApprovalDTO>> getMyRequests(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            HttpServletRequest httpRequest) {
        log.info("获取我的申请记录: factoryId={}", factoryId);

        // 获取当前用户ID
        String authorization = httpRequest.getHeader("Authorization");
        Long requesterId = null;
        if (authorization != null) {
            try {
                String token = TokenUtils.extractToken(authorization);
                var userDTO = mobileService.getUserFromToken(token);
                requesterId = userDTO.getId();
            } catch (Exception e) {
                log.warn("无法从token获取用户信息: {}", e.getMessage());
            }
        }

        if (requesterId == null) {
            return ApiResponse.success(List.of());
        }

        List<SpecialApprovalDTO> requests = specialApprovalService.getMyRequests(factoryId, requesterId);
        return ApiResponse.success(requests);
    }

    /**
     * 获取我的审批记录
     */
    @GetMapping("/quality/approvals/my-decisions")
    @Operation(summary = "我的审批", description = "获取当前用户处理过的审批记录")
    public ApiResponse<List<SpecialApprovalDTO>> getMyApprovals(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            HttpServletRequest httpRequest) {
        log.info("获取我的审批记录: factoryId={}", factoryId);

        // 获取当前用户ID
        String authorization = httpRequest.getHeader("Authorization");
        Long approverId = null;
        if (authorization != null) {
            try {
                String token = TokenUtils.extractToken(authorization);
                var userDTO = mobileService.getUserFromToken(token);
                approverId = userDTO.getId();
            } catch (Exception e) {
                log.warn("无法从token获取用户信息: {}", e.getMessage());
            }
        }

        if (approverId == null) {
            return ApiResponse.success(List.of());
        }

        List<SpecialApprovalDTO> approvals = specialApprovalService.getMyApprovals(factoryId, approverId);
        return ApiResponse.success(approvals);
    }

    // ========== 成本分析接口 ==========

    /**
     * 获取批次成本分析
     */
    @GetMapping("/batches/{batchId}/cost-analysis")
    @Operation(summary = "批次成本分析", description = "获取批次成本详细分析")
    public ApiResponse<Map<String, Object>> getBatchCostAnalysis(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "批次ID", example = "123") String batchId) {
        log.info("获取批次成本分析: factoryId={}, batchId={}", factoryId, batchId);
        Map<String, Object> analysis = processingService.getBatchCostAnalysis(factoryId, batchId);
        return ApiResponse.success(analysis);
    }

    /**
     * 获取增强版批次成本分析（前端CostAnalysisDashboard使用）
     * 返回完整的成本分解结构，包含原材料、人工、设备详情
     */
    @GetMapping("/batches/{batchId}/cost-analysis/enhanced")
    @Operation(summary = "增强版批次成本分析", description = "获取包含costBreakdown的完整成本分析")
    public ApiResponse<Map<String, Object>> getEnhancedBatchCostAnalysis(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "批次ID", example = "123") String batchId) {
        log.info("获取增强版批次成本分析: factoryId={}, batchId={}", factoryId, batchId);
        Map<String, Object> analysis = processingService.getEnhancedBatchCostAnalysis(factoryId, batchId);
        return ApiResponse.success(analysis);
    }

    /**
     * 重新计算批次成本
     */
    @RequirePermission({"production:read_write", "finance:read_write"})
    @PostMapping("/batches/{batchId}/recalculate-cost")
    @Operation(summary = "重算成本", description = "重新计算批次成本")
    public ApiResponse<ProductionBatch> recalculateBatchCost(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "批次ID", example = "123") String batchId) {
        log.info("重新计算批次成本: factoryId={}, batchId={}", factoryId, batchId);
        ProductionBatch batch = processingService.recalculateBatchCost(factoryId, batchId);
        return ApiResponse.success(batch);
    }

    // ========== AI成本分析接口 ==========
    // 所有AI接口已迁移到 AIController (/api/mobile/{factoryId}/ai/*)
    // 详见: com.cretas.aims.controller.AIController

    // ========== 仪表盘接口已迁移 (2025-12-22) ==========
    // Dashboard 端点已迁移到 ReportController: /api/mobile/{factoryId}/reports/dashboard/*
    // 包括: overview, production, quality, equipment, alerts, trends

    // ========== AI接口已全部迁移 ==========
    // 所有AI相关功能（成本分析、配额查询、报告管理、对话历史）已迁移到统一接口
    // 新接口位置: AIController (/api/mobile/{factoryId}/ai/*)
    // 详见: com.cretas.aims.controller.AIController

    // ========== 加工环节记录接口 ==========

    /**
     * 创建加工环节记录
     */
    @RequirePermission({"production:read_write"})
    @PostMapping("/batches/{batchId}/stages")
    @Operation(summary = "创建加工环节记录", description = "为生产批次创建加工环节记录")
    public ApiResponse<ProcessingStageRecordDTO> createStageRecord(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "批次ID", example = "123") Long batchId,
            @RequestBody @Valid @Parameter(description = "环节记录") ProcessingStageRecordDTO dto) {
        log.info("创建加工环节记录: factoryId={}, batchId={}, stageType={}", factoryId, batchId, dto.getStageType());
        dto.setProductionBatchId(batchId);
        ProcessingStageRecordDTO result = stageRecordService.create(factoryId, dto);
        return ApiResponse.success(result);
    }

    /**
     * 批量创建加工环节记录
     */
    @RequirePermission({"production:read_write"})
    @PostMapping("/batches/{batchId}/stages/batch")
    @Operation(summary = "批量创建环节记录", description = "为生产批次批量创建加工环节记录")
    public ApiResponse<List<ProcessingStageRecordDTO>> batchCreateStageRecords(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "批次ID", example = "123") Long batchId,
            @RequestBody @Valid @Parameter(description = "环节记录列表") List<ProcessingStageRecordDTO> dtos) {
        log.info("批量创建加工环节记录: factoryId={}, batchId={}, count={}", factoryId, batchId, dtos.size());
        List<ProcessingStageRecordDTO> results = stageRecordService.batchCreate(factoryId, batchId, dtos);
        return ApiResponse.success(results);
    }

    /**
     * 获取批次的所有环节记录
     */
    @GetMapping("/batches/{batchId}/stages")
    @Operation(summary = "获取批次环节记录", description = "获取生产批次的所有加工环节记录")
    public ApiResponse<List<ProcessingStageRecordDTO>> getStageRecords(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "批次ID", example = "123") Long batchId,
            @RequestParam(defaultValue = "false") @Parameter(description = "是否包含对比数据", example = "false") Boolean withComparison) {
        log.info("获取批次环节记录: factoryId={}, batchId={}, withComparison={}", factoryId, batchId, withComparison);
        List<ProcessingStageRecordDTO> results;
        if (withComparison) {
            results = stageRecordService.getByBatchIdWithComparison(factoryId, batchId);
        } else {
            results = stageRecordService.getByBatchId(factoryId, batchId);
        }
        return ApiResponse.success(results);
    }

    /**
     * 获取单个环节记录
     */
    @GetMapping("/stages/{stageId}")
    @Operation(summary = "获取环节记录详情", description = "获取单个加工环节记录详情")
    public ApiResponse<ProcessingStageRecordDTO> getStageRecord(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "环节记录ID", example = "1") Long stageId) {
        log.info("获取环节记录详情: factoryId={}, stageId={}", factoryId, stageId);
        ProcessingStageRecordDTO result = stageRecordService.getById(factoryId, stageId);
        return ApiResponse.success(result);
    }

    /**
     * 更新环节记录
     */
    @RequirePermission({"production:read_write"})
    @PutMapping("/stages/{stageId}")
    @Operation(summary = "更新环节记录", description = "更新加工环节记录")
    public ApiResponse<ProcessingStageRecordDTO> updateStageRecord(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "环节记录ID", example = "1") Long stageId,
            @RequestBody @Valid @Parameter(description = "环节记录") ProcessingStageRecordDTO dto) {
        log.info("更新环节记录: factoryId={}, stageId={}", factoryId, stageId);
        ProcessingStageRecordDTO result = stageRecordService.update(factoryId, stageId, dto);
        return ApiResponse.success(result);
    }

    /**
     * 删除环节记录
     */
    @RequirePermission({"production:read_write"})
    @DeleteMapping("/stages/{stageId}")
    @Operation(summary = "删除环节记录", description = "删除加工环节记录")
    public ApiResponse<Void> deleteStageRecord(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "环节记录ID", example = "1") Long stageId) {
        log.info("删除环节记录: factoryId={}, stageId={}", factoryId, stageId);
        stageRecordService.delete(factoryId, stageId);
        return ApiResponse.success();
    }

    /**
     * 按环节类型查询记录
     */
    @GetMapping("/stages/by-type/{stageType}")
    @Operation(summary = "按类型查询环节记录", description = "按环节类型查询加工记录")
    public ApiResponse<List<ProcessingStageRecordDTO>> getStageRecordsByType(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "环节类型", example = "CUTTING") ProcessingStageType stageType) {
        log.info("按类型查询环节记录: factoryId={}, stageType={}", factoryId, stageType);
        List<ProcessingStageRecordDTO> results = stageRecordService.getByStageType(factoryId, stageType);
        return ApiResponse.success(results);
    }

    /**
     * 获取环节统计数据 (用于AI分析)
     */
    @GetMapping("/stages/statistics")
    @Operation(summary = "获取环节统计", description = "获取各环节的统计数据，用于AI分析对比")
    public ApiResponse<Map<ProcessingStageType, Map<String, Object>>> getStageStatistics(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId) {
        log.info("获取环节统计: factoryId={}", factoryId);
        Map<ProcessingStageType, Map<String, Object>> stats = stageRecordService.getStageStatistics(factoryId);
        return ApiResponse.success(stats);
    }

    /**
     * 获取批次环节数据 (AI分析格式)
     */
    @GetMapping("/batches/{batchId}/stages/ai-format")
    @Operation(summary = "获取AI分析格式数据", description = "获取批次环节数据，格式化为AI分析所需的格式")
    public ApiResponse<Map<String, String>> getStageRecordsForAI(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "批次ID", example = "123") Long batchId) {
        log.info("获取AI分析格式数据: factoryId={}, batchId={}", factoryId, batchId);
        Map<String, String> result = stageRecordService.formatForAIAnalysis(factoryId, batchId);
        return ApiResponse.success(result);
    }

    // ========== Request DTO → Entity mappers (Rule 17.1 cleanup, PR #370, Issue #384 batches 3+4) ==========

    /**
     * Map {@link CreateProductionBatchRequest} → {@link ProductionBatch} entity.
     *
     * <p>This mapper deliberately does <strong>not</strong> apply any
     * defaults: {@link com.cretas.aims.service.impl.ProcessingServiceImpl#createBatch}
     * is the single owner of the defaulting logic (status=PLANNED,
     * productName="待设置产品名称", quantity=plannedQuantity-or-ZERO, unit="kg",
     * createdAt=now). Duplicating those defaults here would create two
     * sources of truth and risk drift; we just propagate user-provided
     * fields and let the service finish the work.
     *
     * <p>Auto-managed by service / controller path-variable / DB:
     * {@code id}, {@code factoryId}, {@code status}, {@code createdAt},
     * {@code updatedAt}, {@code deletedAt}, {@code createdBy},
     * {@code qualityStatus}, {@code startTime}, {@code endTime},
     * {@code actualQuantity}, {@code goodQuantity}, {@code defectQuantity},
     * {@code totalCost}, {@code unitCost}, {@code yieldRate},
     * {@code efficiency}, {@code workDurationMinutes}.
     */
    private static ProductionBatch toProductionBatch(CreateProductionBatchRequest r) {
        ProductionBatch b = new ProductionBatch();
        b.setBatchNumber(r.getBatchNumber());
        b.setProductionPlanId(r.getProductionPlanId());
        b.setProductTypeId(r.getProductTypeId());
        b.setProductName(r.getProductName());
        b.setPlannedQuantity(r.getPlannedQuantity());
        b.setQuantity(r.getQuantity());
        b.setUnit(r.getUnit());
        b.setEquipmentId(r.getEquipmentId());
        b.setEquipmentName(r.getEquipmentName());
        b.setSupervisorId(r.getSupervisorId());
        b.setSupervisorName(r.getSupervisorName());
        b.setWorkerCount(r.getWorkerCount());
        b.setMaterialCost(r.getMaterialCost());
        b.setLaborCost(r.getLaborCost());
        b.setEquipmentCost(r.getEquipmentCost());
        b.setOtherCost(r.getOtherCost());
        b.setNotes(r.getNotes());
        if (r.getPhotoRequired() != null) {
            b.setPhotoRequired(r.getPhotoRequired());
        }
        b.setSopConfigId(r.getSopConfigId());
        if (r.getCustomFields() != null) {
            b.setCustomFields(r.getCustomFields());
        }
        // feedback_dto_roundtrip_silent_drop fix: 第2处 — mapper 层映射 isTrial / trialSampleId
        if (r.getIsTrial() != null) {
            b.setIsTrial(r.getIsTrial());
        }
        if (r.getTrialSampleId() != null) {
            b.setTrialSampleId(r.getTrialSampleId());
        }
        return b;
    }

    /**
     * Map {@link CreateMaterialReceiptRequest} → {@link MaterialBatch} entity.
     *
     * <p>This mapper deliberately does <strong>not</strong> apply any
     * defaults: {@link com.cretas.aims.service.impl.ProcessingServiceImpl#createMaterialReceipt}
     * is the single owner of the defaulting logic (factoryId from path,
     * status=AVAILABLE, usedQuantity=ZERO, reservedQuantity=ZERO,
     * quantityUnit from materialType-or-"公斤", weightPerUnit=ONE,
     * unitPrice=ZERO, purchaseDate synced from receiptDate). Duplicating
     * those defaults here would create two sources of truth and risk drift;
     * we just propagate user-provided fields and let the service finish
     * the work.
     *
     * <p>Auto-managed by service / controller path-variable / DB:
     * {@code id}, {@code factoryId}, {@code status}, {@code usedQuantity},
     * {@code reservedQuantity}, {@code purchaseDate}, {@code createdAt},
     * {@code updatedAt}, {@code deletedAt}, {@code version},
     * {@code createdBy} (set above from JWT token), {@code lastUsedAt}.
     *
     * <p>FE wire format aliases are resolved by Jackson via {@code @JsonAlias}
     * on the DTO (quantity → receiptQuantity, receivedDate → receiptDate,
     * unit → quantityUnit), so the mapper just propagates the resolved
     * fields onto the entity.
     */
    private static MaterialBatch toMaterialBatch(CreateMaterialReceiptRequest r) {
        MaterialBatch b = new MaterialBatch();
        b.setBatchNumber(r.getBatchNumber());
        b.setMaterialTypeId(r.getMaterialTypeId());
        b.setSupplierId(r.getSupplierId());
        b.setReceiptDate(r.getReceiptDate());
        b.setProductionDate(r.getProductionDate());
        b.setExpireDate(r.getExpireDate());
        b.setReceiptQuantity(r.getReceiptQuantity());
        if (r.getQuantityUnit() != null) {
            b.setQuantityUnit(r.getQuantityUnit());
        }
        b.setWeightPerUnit(r.getWeightPerUnit());
        b.setUnitPrice(r.getUnitPrice());
        b.setStorageLocation(r.getStorageLocation());
        b.setQualityCertificate(r.getQualityCertificate());
        b.setNotes(r.getNotes());
        if (r.getWarehouseId() != null) {
            b.setWarehouseId(r.getWarehouseId());
        }
        if (r.getCustomFields() != null) {
            b.setCustomFields(r.getCustomFields());
        }
        return b;
    }

}
