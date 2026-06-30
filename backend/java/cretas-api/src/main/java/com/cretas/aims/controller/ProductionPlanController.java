package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.ImportResult;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.production.CreateProductionPlanRequest;
import com.cretas.aims.dto.production.DeliveryWarnDTO;
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.dto.production.ProductionPlanMaterialAdvisoryDTO;
import com.cretas.aims.dto.production.ProductionSettlementPrefillResponse;
import com.cretas.aims.dto.production.ProductionSettlementRequest;
import com.cretas.aims.dto.production.ProductionSettlementResponse;
import com.cretas.aims.dto.production.ProductionTransitClearingRequest;
import com.cretas.aims.dto.production.ProductionTransitLedgerDTO;
import com.cretas.aims.entity.ProductionTransitLedger;
import com.cretas.aims.repository.ProductionTransitLedgerRepository;
import com.cretas.aims.dto.production.ProductionWarehouseReceiptRequest;
import com.cretas.aims.dto.production.ProductionWarehouseReceiptResponse;
import com.cretas.aims.dto.yield.ProductionSummaryDTO;
import com.cretas.aims.service.yield.ProductionSummaryService;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.ProductionPlanService;
import com.cretas.aims.service.orchestration.ProductionWorkflowOrchestrator;
import com.cretas.aims.service.yield.InterimSettleService;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.utils.TokenUtils;
import com.cretas.aims.entity.ProductionBatch;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.exception.BusinessException;

/**
 * 生产计划管理控制器
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/production-plans")
@RequiredArgsConstructor
@Tag(name = "生产计划管理", description = "生产计划管理相关接口")
@RequireModule("production_plan")
public class ProductionPlanController {

    private final ProductionPlanService productionPlanService;
    private final MobileService mobileService;
    private final ProductionPlanRepository planRepository;
    private final InterimSettleService interimSettleService;
    private final ProductionWorkflowOrchestrator workflowOrchestrator;
    private final SalesOrderRepository salesOrderRepository;
    private final PriceMaskResolver priceMaskResolver;
    private final ProductionTransitLedgerRepository transitLedgerRepository;
    private final ProductionSummaryService productionSummaryService;

    /**
     * 创建生产计划
     */
    @RequirePermission({"production:read_write", "scheduling:read_write"})
    @RequireModule("production_plan")
    @PostMapping
    @Operation(summary = "创建生产计划")
    @com.cretas.aims.annotation.Loggable(module = "PRODUCTION_PLAN", action = "CREATE",
            entityType = "ProductionPlan")
    public ApiResponse<ProductionPlanDTO> createProductionPlan(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "访问令牌", required = true, example = "Bearer eyJhbGciOiJIUzI1NiJ9...")
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody CreateProductionPlanRequest request) {

        // 获取当前用户ID
        String token = TokenUtils.extractToken(authorization);
        Long userId = mobileService.getUserFromToken(token).getId();

        log.info("创建生产计划: factoryId={}, productTypeId={}", factoryId, request.getProductTypeId());
        ProductionPlanDTO plan = productionPlanService.createProductionPlan(factoryId, request, userId);
        return ApiResponse.success("生产计划创建成功", plan);
    }

    /**
     * 以销定产批量建计划 (六扇门 2026-06-24): 选一张销售订单的多个产品行, 各建一张生产计划。
     * 产品+数量后端权威解析自 SO 行; 原子 (任一失败全回滚)。单产品 = itemIds 1 个。
     */
    @RequirePermission({"production:read_write", "scheduling:read_write"})
    @RequireModule("production_plan")
    @PostMapping("/batch-from-so")
    @Operation(summary = "以销定产批量建计划", description = "选 SO 多个产品行各建一张计划, 产品/数量取自 SO 行")
    @com.cretas.aims.annotation.Loggable(module = "PRODUCTION_PLAN", action = "CREATE",
            entityType = "ProductionPlan")
    public ApiResponse<java.util.List<ProductionPlanDTO>> createPlansFromSalesOrder(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "访问令牌", required = true)
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody com.cretas.aims.dto.production.BatchPlanFromSalesOrderRequest request) {

        String token = TokenUtils.extractToken(authorization);
        Long userId = mobileService.getUserFromToken(token).getId();

        log.info("以销定产批量建计划: factoryId={}, soId={}, 产品行数={}",
                factoryId, request.getSourceOrderId(),
                request.getItemIds() != null ? request.getItemIds().size() : 0);
        java.util.List<ProductionPlanDTO> plans =
                productionPlanService.createPlansFromSalesOrder(factoryId, request, userId);
        return ApiResponse.success("已生成 " + plans.size() + " 张生产计划", plans);
    }

    /**
     * 获取工厂级"免工序报工默认值" (Fable 审计修复 2026-06-11 — 问题1).
     *
     * <p>web 新建计划对话框据此初始化"免工序报工"开关: F006 返 true (默认两点), 其他工厂返 false (默认逐道)。
     */
    @RequireModule("production_plan")
    @GetMapping("/report-mode-default")
    @Operation(summary = "获取工厂免工序报工默认值")
    public ApiResponse<Boolean> getReportModeDefault(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId) {
        return ApiResponse.success(productionPlanService.getSkipProcessReportingDefault(factoryId));
    }

    /**
     * 创建草稿态生产计划 (M-PREP-1, Sprint 4 W2) — status = PREPARED.
     *
     * <p>与 POST `/api/mobile/{factoryId}/production-plans` 区别: 初始状态为 PREPARED 而非 PENDING,
     * 允许在正式提交前预览物料短缺情况, 然后通过 POST `/{planId}/commit` 提交到 PENDING。</p>
     */
    @RequirePermission({"production:read_write", "scheduling:read_write"})
    @RequireModule("production_plan")
    @PostMapping("/draft")
    @Operation(summary = "创建草稿态生产计划 (PREPARED)", description = "M-PREP-1: 创建草稿, 提交前可预览物料短缺")
    public ApiResponse<ProductionPlanDTO> createDraftProductionPlan(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "访问令牌", required = true)
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody CreateProductionPlanRequest request) {

        String token = TokenUtils.extractToken(authorization);
        Long userId = mobileService.getUserFromToken(token).getId();

        log.info("创建草稿生产计划: factoryId={}, productTypeId={}", factoryId, request.getProductTypeId());
        ProductionPlanDTO plan = productionPlanService.createDraftProductionPlan(factoryId, request, userId);
        return ApiResponse.success("草稿生产计划创建成功", plan);
    }

    /**
     * 提交草稿态生产计划 (M-PREP-1, Sprint 4 W2) — PREPARED → PENDING.
     */
    @RequirePermission({"production:read_write", "scheduling:read_write"})
    @RequireModule("production_plan")
    @PostMapping("/{planId}/commit")
    @Operation(summary = "提交草稿生产计划 (PREPARED→PENDING)", description = "M-PREP-1: 将草稿态计划正式提交")
    public ApiResponse<ProductionPlanDTO> commitDraftProductionPlan(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "计划ID", required = true)
            @PathVariable @NotNull String planId) {

        log.info("提交草稿生产计划: factoryId={}, planId={}", factoryId, planId);
        ProductionPlanDTO plan = productionPlanService.commitDraftProductionPlan(factoryId, planId);
        return ApiResponse.success("草稿生产计划已提交", plan);
    }

    /**
     * 更新生产计划
     */
    @RequirePermission({"production:read_write", "scheduling:read_write"})
    @RequireModule("production_plan")
    @PutMapping("/{planId}")
    @Operation(summary = "更新生产计划")
    @com.cretas.aims.annotation.Loggable(module = "PRODUCTION_PLAN", action = "UPDATE",
            entityType = "ProductionPlan", entityIdParam = "planId")
    public ApiResponse<ProductionPlanDTO> updateProductionPlan(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "计划ID", required = true, example = "PP-2025-001")
            @PathVariable @NotNull String planId,
            @Valid @RequestBody CreateProductionPlanRequest request) {

        log.info("更新生产计划: factoryId={}, planId={}", factoryId, planId);
        ProductionPlanDTO plan = productionPlanService.updateProductionPlan(factoryId, planId, request);
        return ApiResponse.success("生产计划更新成功", plan);
    }

    /**
     * 删除生产计划
     */
    @RequirePermission({"production:read_write", "scheduling:read_write"})
    @RequireModule("production_plan")
    @DeleteMapping("/{planId}")
    @Operation(summary = "删除生产计划")
    @com.cretas.aims.annotation.Loggable(module = "PRODUCTION_PLAN", action = "DELETE",
            entityType = "ProductionPlan", entityIdParam = "planId")
    public ApiResponse<Void> deleteProductionPlan(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "计划ID", required = true, example = "PP-2025-001")
            @PathVariable @NotNull String planId) {

        log.info("删除生产计划: factoryId={}, planId={}", factoryId, planId);
        productionPlanService.deleteProductionPlan(factoryId, planId);
        return ApiResponse.success("生产计划删除成功", null);
    }

    /**
     * 获取生产计划详情
     */
    @GetMapping("/{planId}")
    @Operation(summary = "获取生产计划详情")
    public ApiResponse<ProductionPlanDTO> getProductionPlanById(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "计划ID", required = true, example = "PP-2025-001")
            @PathVariable @NotNull String planId) {

        ProductionPlanDTO plan = productionPlanService.getProductionPlanById(factoryId, planId);
        return ApiResponse.success(plan);
    }

    @GetMapping("/{planId}/material-advisory")
    @Operation(summary = "获取生产计划原料库存预警", description = "只读参考值: 展示缺料/单位配置预警, 不阻断生产计划流转")
    public ApiResponse<ProductionPlanMaterialAdvisoryDTO> getMaterialAdvisory(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "计划ID", required = true, example = "PP-2025-001")
            @PathVariable @NotNull String planId) {

        ProductionPlanMaterialAdvisoryDTO advisory = productionPlanService.getMaterialAdvisory(factoryId, planId);
        return ApiResponse.success(advisory);
    }

    /**
     * 获取生产计划列表
     */
    @GetMapping
    @Operation(summary = "获取生产计划列表（分页）")
    public ApiResponse<PageResponse<ProductionPlanDTO>> getProductionPlanList(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Valid PageRequest pageRequest) {

        PageResponse<ProductionPlanDTO> response = productionPlanService.getProductionPlanList(factoryId, pageRequest);
        return ApiResponse.success(response);
    }

    /**
     * 按状态获取生产计划
     */
    @GetMapping("/status/{status}")
    @Operation(summary = "按状态获取生产计划")
    public ApiResponse<List<ProductionPlanDTO>> getProductionPlansByStatus(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "状态", required = true, example = "IN_PROGRESS")
            @PathVariable ProductionPlanStatus status) {

        List<ProductionPlanDTO> plans = productionPlanService.getProductionPlansByStatus(factoryId, status);
        return ApiResponse.success(plans);
    }

    /**
     * 按日期范围获取生产计划
     */
    @GetMapping("/date-range")
    @Operation(summary = "按日期范围获取生产计划")
    public ApiResponse<List<ProductionPlanDTO>> getProductionPlansByDateRange(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "开始日期", required = true, example = "2025-01-01")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "结束日期", required = true, example = "2025-01-31")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        List<ProductionPlanDTO> plans = productionPlanService.getProductionPlansByDateRange(factoryId, startDate, endDate);
        return ApiResponse.success(plans);
    }

    /**
     * 获取今日生产计划
     */
    @GetMapping("/today")
    @Operation(summary = "获取今日生产计划")
    public ApiResponse<List<ProductionPlanDTO>> getTodayProductionPlans(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId) {

        List<ProductionPlanDTO> plans = productionPlanService.getTodayProductionPlans(factoryId);
        return ApiResponse.success(plans);
    }

    /**
     * 获取交货预警列表 (M-DELIVERY-WARN-1, Sprint 4 W2).
     *
     * <p>返回 expectedCompletionDate 在 (今日, 今日+windowDays) 之间且状态非 COMPLETED/CANCELLED
     * 的生产计划, 按预警等级分级:</p>
     * <ul>
     *   <li><b>OVERDUE</b>: 已超期 (expectedCompletionDate &lt; today)</li>
     *   <li><b>URGENT</b>: &lt; 3 天到期</li>
     *   <li><b>WARN</b>: 3 - 7 天到期</li>
     *   <li><b>NORMAL</b>: &ge; 7 天到期 (windowDays 较大时可能包含)</li>
     * </ul>
     */
    @GetMapping("/delivery-warnings")
    @Operation(summary = "获取交货预警列表", description = "M-DELIVERY-WARN-1: 按 expectedCompletionDate 分级预警 (OVERDUE/URGENT/WARN/NORMAL)")
    public ApiResponse<List<DeliveryWarnDTO>> getDeliveryWarnings(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "预警窗口天数 (默认 7)", example = "7")
            @RequestParam(value = "windowDays", required = false, defaultValue = "7") int windowDays) {

        List<DeliveryWarnDTO> warnings = productionPlanService.getDeliveryWarnings(factoryId, windowDays);
        return ApiResponse.success(warnings);
    }

    /**
     * 开始生产
     */
    @RequirePermission({"production:read_write", "scheduling:read_write"})
    @RequireModule("production_plan")
    @PostMapping("/{planId}/start")
    @Operation(summary = "开始生产")
    public ApiResponse<ProductionPlanDTO> startProduction(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "计划ID", required = true, example = "PP-2025-001")
            @PathVariable @NotNull String planId) {

        log.info("开始生产: factoryId={}, planId={}", factoryId, planId);
        ProductionPlanDTO plan = productionPlanService.startProduction(factoryId, planId);
        return ApiResponse.success("生产已开始", plan);
    }

    /**
     * 完成生产
     */
    @RequirePermission({"production:read_write", "scheduling:read_write"})
    @RequireModule("production_plan")
    @PostMapping("/{planId}/complete")
    @Operation(summary = "完成生产")
    public ApiResponse<ProductionPlanDTO> completeProduction(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "计划ID", required = true, example = "PP-2025-001")
            @PathVariable @NotNull String planId,
            @Parameter(description = "请求体: {actualQuantity: 500}", required = true)
            @RequestBody Map<String, Object> body) {

        Object rawQty = body.get("actualQuantity");
        if (rawQty == null) {
            throw new BusinessException(400, "缺少必要参数: actualQuantity");
        }
        BigDecimal actualQuantity = new BigDecimal(rawQty.toString());
        log.info("完成生产: factoryId={}, planId={}, actualQuantity={}", factoryId, planId, actualQuantity);
        ProductionPlanDTO plan = productionPlanService.completeProduction(factoryId, planId, actualQuantity);
        return ApiResponse.success("生产已完成", plan);
    }

    /**
     * 中转挂账对账列表 (web-admin 用, 只读)
     */
    @RequirePermission({"warehouse:read", "warehouse:read_write", "production:read", "production:read_write", "scheduling:read", "scheduling:read_write"})
    @RequireModule("production_plan")
    @GetMapping("/transit-ledgers")
    @Operation(summary = "查询生产中转挂账列表", description = "六扇门: 仓库对账页 — 按工厂查询全部或按状态过滤的中转挂账记录")
    public ApiResponse<List<ProductionTransitLedgerDTO>> listTransitLedgers(
            @Parameter(description = "工厂ID", required = true, example = "F006")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "状态过滤: OPEN / RESOLVED / VOIDED, 不传返回全部")
            @RequestParam(required = false) String status) {

        List<ProductionTransitLedger> ledgers = (status != null && !status.isBlank())
                ? transitLedgerRepository.findByFactoryIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(factoryId, status.toUpperCase())
                : transitLedgerRepository.findByFactoryIdAndDeletedAtIsNullOrderByCreatedAtDesc(factoryId);

        List<ProductionTransitLedgerDTO> dtos = ledgers.stream()
                .map(l -> ProductionTransitLedgerDTO.builder()
                        .id(l.getId())
                        .factoryId(l.getFactoryId())
                        .settlementId(l.getSettlementId())
                        .productionPlanId(l.getProductionPlanId())
                        .planNumber(l.getPlanNumber())
                        .ledgerType(l.getLedgerType())
                        .reportedQuantity(l.getReportedQuantity())
                        .confirmedQuantity(l.getConfirmedQuantity())
                        .varianceQuantity(l.getVarianceQuantity())
                        .toleranceQuantity(l.getToleranceQuantity())
                        .quantityUnit(l.getQuantityUnit())
                        .varianceReason(l.getVarianceReason())
                        .responsibilitySide(l.getResponsibilitySide())
                        .status(l.getStatus())
                        .note(l.getNote())
                        .createdBy(l.getCreatedBy())
                        .createdAt(l.getCreatedAt())
                        .updatedAt(l.getUpdatedAt())
                        .build())
                .toList();

        return ApiResponse.success(dtos);
    }

    /**
     * 核对生产结单
     */
    @RequirePermission({"warehouse:write", "warehouse:read_write", "production:read_write", "scheduling:read_write"})
    @RequireModule("production_plan")
    @PostMapping("/{planId}/transit-ledger/clear")
    @Operation(summary = "清理生产中转挂账", description = "六扇门: 仓库确认入库产生差异挂账后, 由责任侧处理并清账")
    public ApiResponse<ProductionWarehouseReceiptResponse> clearProductionTransitLedger(
            @Parameter(description = "工厂ID", required = true, example = "F006")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "计划ID", required = true)
            @PathVariable @NotNull String planId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody ProductionTransitClearingRequest request) {

        Long userId = extractUserId(authorization);
        log.info("清理生产中转挂账: factoryId={}, planId={}, userId={}", factoryId, planId, userId);
        ProductionWarehouseReceiptResponse response =
                productionPlanService.clearProductionTransitLedger(factoryId, planId, request, userId);
        return ApiResponse.success("生产中转挂账已清账", response);
    }

    @RequirePermission({"production:read_write", "scheduling:read_write"})
    @RequireModule("production_plan")
    @PostMapping("/{planId}/settle")
    @Operation(summary = "核对生产结单", description = "六扇门: 文员录入实际产量、实际领用和人效后才完成生产计划")
    public ApiResponse<ProductionSettlementResponse> settleProduction(
            @Parameter(description = "工厂ID", required = true, example = "F006")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "计划ID", required = true)
            @PathVariable @NotNull String planId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody ProductionSettlementRequest request) {

        Long userId = extractUserId(authorization);
        log.info("核对生产结单: factoryId={}, planId={}, userId={}", factoryId, planId, userId);
        ProductionSettlementResponse response =
                productionPlanService.settleProduction(factoryId, planId, request, userId);
        return ApiResponse.success("生产结单已提交", response);
    }

    @RequirePermission({"production:read_write", "scheduling:read_write"})
    @RequireModule("production_plan")
    @PostMapping("/{planId}/interim-settle")
    @Operation(summary = "存货生产小结", description = "存货生产 (sourceType=SAFETY_STOCK): 对自上次小结以来的增量分批入库(半成品/成品)+实时扣减原料, 会话幂等, 不关闭计划")
    public ApiResponse<Map<String, Object>> interimSettle(
            @Parameter(description = "工厂ID", required = true, example = "F006")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "计划ID", required = true)
            @PathVariable @NotNull String planId,
            @RequestHeader("Authorization") String authorization) {

        Long userId = extractUserId(authorization);
        log.info("库存生产小结: factoryId={}, planId={}, userId={}", factoryId, planId, userId);
        Map<String, Object> summary = interimSettleService.interimSettle(factoryId, planId, userId);
        return ApiResponse.success("小结已提交", summary);
    }

    /**
     * 停产 — 存货生产 (SAFETY_STOCK) 计划关闭
     *
     * <p>纯状态关闭 (→ COMPLETED), 不扣料、不发 BatchCompletedEvent。
     * 配合小结 (interim-settle) 使用: 小结已逐批扣料+入库, 停产只关闭计划。
     */
    @RequirePermission({"production:read_write", "scheduling:read_write"})
    @RequireModule("production_plan")
    @PostMapping("/{planId}/stop-production")
    @Operation(summary = "停产", description = "存货生产 (sourceType=SAFETY_STOCK): 纯状态关闭 (COMPLETED), 不扣料不发事件, 配合小结使用")
    public ApiResponse<Void> stopProduction(
            @Parameter(description = "工厂ID", required = true, example = "F006")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "计划ID", required = true)
            @PathVariable @NotNull String planId) {

        log.info("停产: factoryId={}, planId={}", factoryId, planId);
        productionPlanService.stopProduction(factoryId, planId);
        return ApiResponse.success("已停产", null);
    }

    @RequirePermission({"production:read", "production:read_write", "scheduling:read", "scheduling:read_write"})
    @RequireModule("production_plan")
    @GetMapping("/{planId}/settlement")
    @Operation(summary = "查询生产结单状态", description = "六扇门: 查询文员结单、仓库确认入库和中转挂账状态")
    public ApiResponse<ProductionSettlementResponse> getProductionSettlement(
            @Parameter(description = "工厂ID", required = true, example = "F006")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "计划ID", required = true)
            @PathVariable @NotNull String planId) {

        ProductionSettlementResponse response = productionPlanService.getProductionSettlement(factoryId, planId);
        return ApiResponse.success("生产结单状态", response);
    }

    /**
     * Phase 2A (报工→核算自动化): 从逐道报工 derive 出核对结单预填表单 + 审计。
     * 只读, 不扣库存; 前端核对结单 dialog 打开时调用, 一键带入省去二次手敲。
     */
    @RequirePermission({"production:read", "production:read_write", "scheduling:read", "scheduling:read_write"})
    @RequireModule("production_plan")
    @GetMapping("/{planId}/settlement-prefill")
    @Operation(summary = "核对结单自动预填", description = "六扇门: 从逐道报工 derive 预填表单+审计, 文员核对后一键确认(仍人工确认才扣库存)")
    public ApiResponse<ProductionSettlementPrefillResponse> getSettlementPrefill(
            @Parameter(description = "工厂ID", required = true, example = "F006")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "计划ID", required = true)
            @PathVariable @NotNull String planId) {

        ProductionSettlementPrefillResponse response = productionPlanService.getSettlementPrefill(factoryId, planId);
        return ApiResponse.success("核对结单预填", response);
    }

    /**
     * 库存生产阅读汇总 — 按生产计划聚合出成率、成本六桶、副产品等核心指标。
     *
     * <p>价格字段按 procurement:price:view 权限脱敏，与 cost-breakdown 端点策略一致。
     * <p>只读，不修改任何库存或计划状态。
     */
    @RequirePermission({"production:read", "production:read_write", "scheduling:read", "scheduling:read_write"})
    @RequireModule("production_plan")
    @GetMapping("/{planId}/production-summary")
    @Operation(summary = "库存生产阅读汇总", description = "按计划聚合出成率/成本六桶/副产品; 价格按 procurement:price:view 脱敏")
    public ApiResponse<ProductionSummaryDTO> getProductionSummary(
            @Parameter(description = "工厂ID", required = true, example = "F006")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "计划ID", required = true)
            @PathVariable @NotNull String planId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        boolean maskPrice = priceMaskResolver.shouldMaskPrice(authorization);
        return ApiResponse.success(productionSummaryService.computeSummary(factoryId, planId, maskPrice));
    }

    @RequirePermission({"warehouse:write", "warehouse:read_write", "production:read_write", "scheduling:read_write"})
    @RequireModule("production_plan")
    @PostMapping("/{planId}/warehouse-receipt")
    @Operation(summary = "仓库确认生产入库", description = "六扇门: 仓库确认实收后才生成成品库存; 差异超容差进入中转挂账")
    public ApiResponse<ProductionWarehouseReceiptResponse> confirmWarehouseReceipt(
            @Parameter(description = "工厂ID", required = true, example = "F006")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "计划ID", required = true)
            @PathVariable @NotNull String planId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody ProductionWarehouseReceiptRequest request) {

        Long userId = extractUserId(authorization);
        log.info("仓库确认生产入库: factoryId={}, planId={}, userId={}", factoryId, planId, userId);
        ProductionWarehouseReceiptResponse response =
                productionPlanService.confirmWarehouseReceipt(factoryId, planId, request, userId);
        return ApiResponse.success("仓库已确认生产入库", response);
    }

    @RequirePermission({"production:read_write", "scheduling:read_write"})
    @RequireModule("production_plan")
    @PostMapping("/{planId}/cancel")
    @Operation(summary = "取消生产计划")
    public ApiResponse<Void> cancelProductionPlan(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "计划ID", required = true, example = "PP-2025-001")
            @PathVariable @NotNull String planId,
            @Parameter(description = "取消原因", required = true, example = "订单取消")
            @RequestParam @NotBlank String reason) {

        log.info("取消生产计划: factoryId={}, planId={}, reason={}", factoryId, planId, reason);
        productionPlanService.cancelProductionPlan(factoryId, planId, reason);
        return ApiResponse.success("生产计划已取消", null);
    }

    /**
     * SP12 T3: 申请撤回已完成的生产计划（触发审批流）.
     * 六扇门红线：COMPLETED 计划不可操作员直接撤回，必须主管审批。
     * COMPLETED → PENDING_APPROVAL → (审批通过) → CANCELLED
     */
    @PostMapping("/{planId}/request-cancel")
    @Operation(summary = "申请撤回已完成的生产计划（触发 PRODUCTION_REVERSAL 审批流）")
    public ApiResponse<String> requestCancelWithApproval(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "计划ID", required = true)
            @PathVariable @NotNull String planId,
            @Parameter(description = "撤回原因", required = true)
            @RequestParam @NotBlank String reason,
            @RequestHeader("Authorization") String authorization) {

        Long userId = extractUserId(authorization);
        log.info("申请撤回生产计划: factoryId={}, planId={}, reason={}, userId={}",
                factoryId, planId, reason, userId);
        String instanceId = productionPlanService.requestCancelWithApproval(factoryId, planId, reason, userId);
        return ApiResponse.success("撤回申请已提交，等待主管审批", instanceId);
    }

    /**
     * 复制生产计划 — #860 follow-up.
     * 基于现有计划创建新草稿, 复制产品/数量/日期/成本预估, 不复制实际值/审批/锁定状态.
     */
    @RequirePermission({"production:read_write", "scheduling:read_write"})
    @RequireModule("production_plan")
    @PostMapping("/{planId}/copy")
    @Operation(summary = "复制生产计划 (创建新草稿)",
            description = "复制计划业务字段, status 重置为 PENDING. 跳过 SO source / Canvas validation.")
    public ApiResponse<ProductionPlanDTO> copyProductionPlan(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "源计划ID", required = true, example = "PLAN-1234567890-ABCDEF12")
            @PathVariable @NotBlank String planId,
            @RequestHeader("Authorization") String authorization) {

        Long userId = extractUserId(authorization);
        log.info("复制生产计划: factoryId={}, sourcePlanId={}, userId={}",
                factoryId, planId, userId);
        ProductionPlanDTO plan = productionPlanService.copyProductionPlan(factoryId, planId, userId);
        return ApiResponse.success("生产计划已复制为新草稿", plan);
    }

    /**
     * 锁定生产计划 (Issue #759, 2026-05-17).
     *
     * <p>锁定后该计划不可编辑数量/日期/取消 — 进入排产阶段后防误操作.
     * 前端 (PR #755) 已加 banner + lock button; 本接口实现后端真实写入.
     */
    @RequirePermission({"production:read_write", "scheduling:read_write"})
    @RequireModule("production_plan")
    @PostMapping("/{planId}/lock")
    @Operation(summary = "锁定生产计划",
            description = "锁定后不可编辑数量/日期/取消. 客户场景: 进入排产阶段后防误操作.")
    public ApiResponse<ProductionPlanDTO> lockProductionPlan(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "计划ID", required = true, example = "PP-2025-001")
            @PathVariable @NotBlank String planId,
            @Parameter(description = "锁定原因 (可选)", example = "排产已下达")
            @RequestParam(required = false) String reason,
            @RequestHeader("Authorization") String authorization) {

        Long userId = extractUserId(authorization);
        log.info("锁定生产计划: factoryId={}, planId={}, userId={}, reason={}",
                factoryId, planId, userId, reason);
        ProductionPlanDTO plan = productionPlanService.lockProductionPlan(factoryId, planId, reason, userId);
        return ApiResponse.success("生产计划已锁定", plan);
    }

    /**
     * 解锁生产计划 (Issue #759, 2026-05-17).
     */
    @RequirePermission({"production:read_write", "scheduling:read_write"})
    @RequireModule("production_plan")
    @PostMapping("/{planId}/unlock")
    @Operation(summary = "解锁生产计划")
    public ApiResponse<ProductionPlanDTO> unlockProductionPlan(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "计划ID", required = true, example = "PP-2025-001")
            @PathVariable @NotBlank String planId,
            @RequestHeader("Authorization") String authorization) {

        Long userId = extractUserId(authorization);
        log.info("解锁生产计划: factoryId={}, planId={}, userId={}", factoryId, planId, userId);
        ProductionPlanDTO plan = productionPlanService.unlockProductionPlan(factoryId, planId, userId);
        return ApiResponse.success("生产计划已解锁", plan);
    }

    /**
     * 暂停生产
     */
    @RequirePermission({"production:read_write", "scheduling:read_write"})
    @RequireModule("production_plan")
    @PostMapping("/{planId}/pause")
    @Operation(summary = "暂停生产")
    public ApiResponse<ProductionPlanDTO> pauseProduction(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "计划ID", required = true, example = "PP-2025-001")
            @PathVariable @NotNull String planId) {

        log.info("暂停生产: factoryId={}, planId={}", factoryId, planId);
        ProductionPlanDTO plan = productionPlanService.pauseProduction(factoryId, planId);
        return ApiResponse.success("生产已暂停", plan);
    }

    /**
     * 恢复生产
     */
    @RequirePermission({"production:read_write", "scheduling:read_write"})
    @RequireModule("production_plan")
    @PostMapping("/{planId}/resume")
    @Operation(summary = "恢复生产")
    public ApiResponse<ProductionPlanDTO> resumeProduction(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "计划ID", required = true, example = "PP-2025-001")
            @PathVariable @NotNull String planId) {

        log.info("恢复生产: factoryId={}, planId={}", factoryId, planId);
        ProductionPlanDTO plan = productionPlanService.resumeProduction(factoryId, planId);
        return ApiResponse.success("生产已恢复", plan);
    }

    /**
     * 更新实际成本
     */
    @RequirePermission({"production:read_write", "scheduling:read_write"})
    @RequireModule("production_plan")
    @PutMapping("/{planId}/costs")
    @Operation(summary = "更新实际成本")
    public ApiResponse<ProductionPlanDTO> updateActualCosts(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "计划ID", required = true, example = "PP-2025-001")
            @PathVariable @NotNull String planId,
            @Parameter(description = "材料成本", example = "1000.00")
            @RequestParam(required = false) BigDecimal materialCost,
            @Parameter(description = "人工成本", example = "500.00")
            @RequestParam(required = false) BigDecimal laborCost,
            @Parameter(description = "设备成本", example = "200.00")
            @RequestParam(required = false) BigDecimal equipmentCost,
            @Parameter(description = "其他成本", example = "100.00")
            @RequestParam(required = false) BigDecimal otherCost) {

        log.info("更新实际成本: factoryId={}, planId={}", factoryId, planId);
        ProductionPlanDTO plan = productionPlanService.updateActualCosts(
            factoryId, planId, materialCost, laborCost, equipmentCost, otherCost);
        return ApiResponse.success("成本更新成功", plan);
    }

    /**
     * 排产 → 自动生成调拨单 (BOM展开 → 创建 InternalTransfer → 自动提交)
     */
    @RequirePermission({"production:read_write", "scheduling:read_write"})
    @RequireModule("production_plan")
    @PostMapping("/{planId}/generate-transfer")
    @Operation(summary = "根据BOM生成调拨单")
    public ApiResponse<InternalTransfer> generateTransfer(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotNull String planId,
            @RequestParam(required = false) String targetFactoryId,
            @RequestHeader("Authorization") String authorization) {

        String token = TokenUtils.extractToken(authorization);
        Long userId = mobileService.getUserFromToken(token).getId();
        log.info("生成调拨单: factoryId={}, planId={}, target={}", factoryId, planId, targetFactoryId);
        InternalTransfer transfer = workflowOrchestrator.generateTransferFromPlan(
                factoryId, planId, targetFactoryId, userId);
        return ApiResponse.success("调拨单生成成功，共 " + transfer.getItems().size() + " 项物料", transfer);
    }

    /**
     * 分配原材料批次
     */
    @RequirePermission({"production:read_write", "scheduling:read_write"})
    @RequireModule("production_plan")
    @PostMapping("/{planId}/batches")
    @Operation(summary = "分配原材料批次")
    public ApiResponse<Void> assignMaterialBatches(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "计划ID", required = true, example = "PP-2025-001")
            @PathVariable @NotNull String planId,
            @Parameter(description = "批次ID列表", required = true)
            @RequestBody @NotNull List<String> batchIds) {

        log.info("分配原材料批次: factoryId={}, planId={}, batchCount={}", factoryId, planId, batchIds.size());
        productionPlanService.assignMaterialBatches(factoryId, planId, batchIds);
        return ApiResponse.success("批次分配成功", null);
    }

    /**
     * 记录材料消耗
     */
    @RequirePermission({"production:read_write", "scheduling:read_write"})
    @RequireModule("production_plan")
    @PostMapping("/{planId}/consumption")
    @Operation(summary = "记录材料消耗")
    public ApiResponse<Void> recordMaterialConsumption(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "计划ID", required = true, example = "PP-2025-001")
            @PathVariable @NotNull String planId,
            @Parameter(description = "访问令牌", example = "Bearer eyJhbGciOiJIUzI1NiJ9...")
            @RequestHeader("Authorization") String authorization,
            @Parameter(description = "批次ID", required = true, example = "MB-2025-001")
            @RequestParam @NotBlank String batchId,
            @Parameter(description = "消耗数量", required = true, example = "100.5")
            @RequestParam @NotNull BigDecimal quantity) {

        // 获取当前用户ID (写入 MaterialConsumption.recordedBy)
        String token = TokenUtils.extractToken(authorization);
        Long userId = mobileService.getUserFromToken(token).getId();

        log.info("记录材料消耗: factoryId={}, planId={}, batchId={}, quantity={}",
                factoryId, planId, batchId, quantity);
        productionPlanService.recordMaterialConsumption(factoryId, planId, batchId, quantity, userId);
        return ApiResponse.success("消耗记录成功", null);
    }

    /**
     * 获取生产统计
     */
    @GetMapping("/statistics")
    @Operation(summary = "获取生产统计")
    public ApiResponse<Map<String, Object>> getProductionStatistics(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "开始日期", required = true, example = "2025-01-01")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "结束日期", required = true, example = "2025-01-31")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        Map<String, Object> statistics = productionPlanService.getProductionStatistics(factoryId, startDate, endDate);
        return ApiResponse.success(statistics);
    }

    /**
     * 获取待执行的计划
     */
    @GetMapping("/pending-execution")
    @Operation(summary = "获取待执行的计划")
    public ApiResponse<List<ProductionPlanDTO>> getPendingPlansToExecute(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId) {

        List<ProductionPlanDTO> plans = productionPlanService.getPendingPlansToExecute(factoryId);
        return ApiResponse.success(plans);
    }

    /**
     * 批量创建生产计划
     */
    @RequirePermission({"production:read_write", "scheduling:read_write"})
    @RequireModule("production_plan")
    @PostMapping("/batch")
    @Operation(summary = "批量创建生产计划")
    public ApiResponse<List<ProductionPlanDTO>> batchCreateProductionPlans(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "访问令牌", required = true, example = "Bearer eyJhbGciOiJIUzI1NiJ9...")
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody List<CreateProductionPlanRequest> requests) {

        // 获取当前用户ID
        String token = TokenUtils.extractToken(authorization);
        Long userId = mobileService.getUserFromToken(token).getId();

        log.info("批量创建生产计划: factoryId={}, count={}", factoryId, requests.size());
        List<ProductionPlanDTO> plans = productionPlanService.batchCreateProductionPlans(factoryId, requests, userId);
        return ApiResponse.success("批量创建成功", plans);
    }

    /**
     * 从生产计划创建生产批次（计划→执行转换）
     */
    @RequirePermission({"production:read_write", "scheduling:read_write"})
    @RequireModule("production_plan")
    @PostMapping("/{planId}/create-batch")
    @Operation(summary = "从计划创建批次")
    public ApiResponse<ProductionBatch> createBatchFromPlan(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotNull String planId) {

        log.info("从计划创建批次: factoryId={}, planId={}", factoryId, planId);
        ProductionBatch batch = productionPlanService.createBatchFromPlan(factoryId, planId);
        return ApiResponse.success("批次创建成功", batch);
    }

    /**
     * 导出生产计划
     */
    @GetMapping("/export")
    @Operation(summary = "导出生产计划",
            description = "导出生产计划为Excel。RBAC: maskPrice 参数 wired through (PR P0-C sweep), "
                    + "当前 ProductionPlanImportDTO 不含 cost 列, 所以 no-op; 当未来 export 加 actualMaterialCost 等列时即生效.")
    public void exportProductionPlans(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "开始日期", required = true, example = "2025-01-01")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "结束日期", required = true, example = "2025-01-31")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestHeader("Authorization") String authorization,
            HttpServletResponse response) throws IOException {

        // RBAC defense-in-depth (P0-C sweep, 2026-05-12): mirror PR #450 pattern.
        boolean maskPrice = priceMaskResolver.shouldMaskPrice(authorization);

        log.info("导出生产计划: factoryId={}, startDate={}, endDate={}, maskPrice={}",
                factoryId, startDate, endDate, maskPrice);
        byte[] data = productionPlanService.exportProductionPlans(factoryId, startDate, endDate, maskPrice);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=production-plans.xlsx");
        response.getOutputStream().write(data);
        response.getOutputStream().flush();
    }

    /**
     * 下载生产计划导入模板
     */
    @GetMapping("/import-template")
    @Operation(summary = "下载生产计划导入模板")
    public void downloadImportTemplate(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=production-plan-template.xlsx");
        byte[] template = productionPlanService.generateImportTemplate();
        response.getOutputStream().write(template);
        response.getOutputStream().flush();
    }

    /**
     * Excel批量导入生产计划
     */
    @RequirePermission({"production:read_write", "scheduling:read_write"})
    @RequireModule("production_plan")
    @PostMapping("/import")
    @Operation(summary = "Excel批量导入生产计划")
    public ApiResponse<ImportResult<ProductionPlanDTO>> importProductionPlans(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "访问令牌", required = true)
            @RequestHeader("Authorization") String authorization,
            @RequestParam("file") MultipartFile file) throws IOException {

        if (file.isEmpty()) {
            throw new BusinessException(400, "请选择要导入的文件");
        }

        String token = TokenUtils.extractToken(authorization);
        Long userId = mobileService.getUserFromToken(token).getId();

        log.info("Excel导入生产计划: factoryId={}, fileName={}", factoryId, file.getOriginalFilename());
        ImportResult<ProductionPlanDTO> result = productionPlanService.importProductionPlansFromExcel(
                factoryId, file.getInputStream(), userId);
        return ApiResponse.success("导入完成", result);
    }

    /**
     * 获取今日生产看板卡片（进行中/计划中/待处理）
     */
    @GetMapping("/today-cards")
    @Operation(summary = "获取今日生产看板卡片")
    public ApiResponse<List<Map<String, Object>>> getTodayCards(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId) {

        List<ProductionPlan> plans = planRepository.findByFactoryId(factoryId).stream()
                .filter(p -> {
                    ProductionPlanStatus s = p.getStatus();
                    return s == ProductionPlanStatus.IN_PROGRESS
                            || s == ProductionPlanStatus.PLANNED
                            || s == ProductionPlanStatus.PENDING;
                })
                .collect(Collectors.toList());

        List<Map<String, Object>> cards = plans.stream().map(p -> {
            Map<String, Object> card = new HashMap<>();
            card.put("planId", p.getId());
            card.put("planNumber", p.getPlanNumber());
            card.put("customerName", p.getSourceCustomerName());
            card.put("productName", p.getProductType() != null ? p.getProductType().getName() : "");
            card.put("processName", p.getProcessName());
            card.put("plannedQty", p.getPlannedQuantity());
            card.put("reportedQty", p.getActualQuantity() != null ? p.getActualQuantity() : BigDecimal.ZERO);
            BigDecimal planned = p.getPlannedQuantity();
            BigDecimal actual = p.getActualQuantity() != null ? p.getActualQuantity() : BigDecimal.ZERO;
            int progress = planned != null && planned.compareTo(BigDecimal.ZERO) > 0
                    ? actual.multiply(BigDecimal.valueOf(100))
                            .divide(planned, 0, java.math.RoundingMode.HALF_UP).intValue()
                    : 0;
            card.put("progress", progress);
            return card;
        }).collect(Collectors.toList());

        return ApiResponse.success(cards);
    }

    /**
     * P0-12: 获取可关联的销售订单列表(用于生产计划来源选择器)
     * 仅返回已确认/处理中/部分发货等尚未完成的订单
     */
    @GetMapping("/sales-orders/selectable")
    @Operation(summary = "获取可关联的销售订单列表")
    public ApiResponse<List<Map<String, Object>>> getSelectableSalesOrders(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId) {

        // R23 audit C3+I6: was inline whitelist + findAll().stream().filter() — full table scan
        // loading ALL factories' SOs into JVM heap before filtering (multi-tenant data leak risk).
        // Now JPQL push-down + centralized SO_PLANNABLE.
        List<SalesOrder> orders = salesOrderRepository.findByFactoryIdAndStatusInOrderByCreatedAtDesc(
                factoryId,
                com.cretas.aims.domain.OrderUsageWhitelists.SO_PLANNABLE,
                org.springframework.data.domain.PageRequest.of(0, 200));

        List<Map<String, Object>> result = orders.stream().map(so -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", so.getId());
            m.put("orderNo", so.getOrderNumber());
            m.put("customerId", so.getCustomerId());
            m.put("customerName", so.getCustomerName());
            m.put("totalAmount", so.getTotalAmount());
            m.put("status", so.getStatus() != null ? so.getStatus().name() : null);
            m.put("statusLabel", so.getStatus() != null ? so.getStatus().getDisplayName() : null);
            m.put("orderDate", so.getOrderDate());
            List<Map<String, Object>> items = so.getItems() == null ? List.of() :
                    so.getItems().stream().map(it -> {
                        Map<String, Object> im = new HashMap<>();
                        // P0-12: 暴露行ID,前端 sourceOrderItemId 用
                        im.put("id", it.getId() != null ? String.valueOf(it.getId()) : null);
                        im.put("productTypeId", it.getProductTypeId());
                        im.put("productName", it.getProductName());
                        im.put("specification", it.getSpecification());
                        im.put("quantity", it.getQuantity());
                        im.put("deliveredQuantity", it.getDeliveredQuantity());
                        java.math.BigDecimal q = it.getQuantity() != null ? it.getQuantity() : java.math.BigDecimal.ZERO;
                        java.math.BigDecimal d = it.getDeliveredQuantity() != null ? it.getDeliveredQuantity() : java.math.BigDecimal.ZERO;
                        im.put("remainingQty", q.subtract(d));
                        return im;
                    }).collect(Collectors.toList());
            m.put("items", items);
            return m;
        }).collect(Collectors.toList());

        return ApiResponse.success(result);
    }

    // ==================== SP5 双向检索端点 ====================

    /**
     * SP5 双向检索 — 销售订单 ID → 关联生产计划列表.
     *
     * <p>同时覆盖两条路径:
     * <ol>
     *   <li>旧数据: {@code source_order_id} 精确匹配 (单 SO 历史工单)。</li>
     *   <li>新数据: {@code source_order_ids @> ["<soId>"] JSONB} 包含匹配 (多 SO 合并工单)。</li>
     * </ol>
     * 结果去重后按创建时间倒序返回。
     *
     * @param factoryId    工厂 ID
     * @param salesOrderId 销售订单 ID
     */
    @GetMapping("/by-sales-order/{salesOrderId}")
    @Operation(summary = "SP5 双向检索 — 销售单→生产计划列表",
            description = "查询关联到指定销售订单 (单 SO 历史 + 多 SO 合并) 的全部生产计划")
    public ApiResponse<List<ProductionPlanDTO>> getPlansBySalesOrder(
            @Parameter(description = "工厂ID", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "销售订单ID", required = true)
            @PathVariable @NotBlank String salesOrderId) {

        log.info("SP5 双向检索 SO→Plans: factoryId={}, salesOrderId={}", factoryId, salesOrderId);

        // 路径1: source_order_id 精确匹配 (历史单 SO 数据)
        java.util.Set<String> seenIds = new java.util.LinkedHashSet<>();
        List<ProductionPlanDTO> results = new java.util.ArrayList<>();

        planRepository.findByFactoryIdAndSourceOrderIdExact(factoryId, salesOrderId)
                .forEach(p -> {
                    if (seenIds.add(p.getId())) {
                        results.add(productionPlanService.toPlanDTO(p));
                    }
                });

        // 路径2: source_order_ids @> ["<soId>"] JSONB 包含 (多 SO 合并场景)
        String soIdJson = "[\"" + salesOrderId.replace("\"", "\\\"") + "\"]";
        planRepository.findByFactoryIdAndSourceOrderIdsContaining(factoryId, soIdJson)
                .forEach(p -> {
                    if (seenIds.add(p.getId())) {
                        results.add(productionPlanService.toPlanDTO(p));
                    }
                });

        // 按创建时间倒序 (两路合并后重排)
        results.sort((a, b) -> {
            if (a.getCreatedAt() == null && b.getCreatedAt() == null) return 0;
            if (a.getCreatedAt() == null) return 1;
            if (b.getCreatedAt() == null) return -1;
            return b.getCreatedAt().compareTo(a.getCreatedAt());
        });

        return ApiResponse.success(results);
    }

    /**
     * SP5 双向检索 — 生产计划 ID → 关联销售订单 ID 列表.
     *
     * <p>返回 {@code sourceOrderIds} 列表 (含主 {@code sourceOrderId}); 遗留数据若列表为空
     * 但 {@code sourceOrderId} 有值则自动包装返回。
     *
     * @param factoryId 工厂 ID
     * @param planId    生产计划 ID
     */
    @GetMapping("/{planId}/source-order-ids")
    @Operation(summary = "SP5 双向检索 — 生产计划→关联销售订单ID列表",
            description = "查询指定生产计划覆盖的全部销售订单 ID (多 SO 合并工单)")
    public ApiResponse<Map<String, Object>> getSourceOrderIdsByPlan(
            @Parameter(description = "工厂ID", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "生产计划ID", required = true)
            @PathVariable @NotBlank String planId) {

        log.info("SP5 双向检索 Plan→SO IDs: factoryId={}, planId={}", factoryId, planId);

        ProductionPlan plan = planRepository.findByIdAndFactoryId(planId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "生产计划不存在: " + planId));

        java.util.List<String> ids = plan.getSourceOrderIds() != null && !plan.getSourceOrderIds().isEmpty()
                ? plan.getSourceOrderIds()
                : (plan.getSourceOrderId() != null && !plan.getSourceOrderId().isBlank()
                        ? java.util.List.of(plan.getSourceOrderId())
                        : java.util.List.of());

        Map<String, Object> data = new HashMap<>();
        data.put("planId", planId);
        data.put("planNumber", plan.getPlanNumber());
        data.put("sourceOrderIds", ids);
        data.put("sourceOrderId", plan.getSourceOrderId());  // backward compat primary key
        data.put("count", ids.size());

        return ApiResponse.success(data);
    }

    /** Issue #759: shared user-id extraction for lock/unlock endpoints. */
    private Long extractUserId(String authorization) {
        String token = TokenUtils.extractToken(authorization);
        if (token == null) {
            throw new BusinessException(401, "Authorization header 缺失");
        }
        return mobileService.getUserFromToken(token).getId();
    }
}
