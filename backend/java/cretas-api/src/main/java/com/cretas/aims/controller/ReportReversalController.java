package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.ReportReversalLog;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.service.reversal.ReportReversalService;
import com.cretas.aims.service.wip.WipInventoryService;
import com.cretas.aims.service.ProductionPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * SP2 报工撤回 + 二次加工计划 控制器。
 *
 * <p>C1 twin bug 规避: userId 从 {@code request.getAttribute("userId")} 取 (JwtAuthInterceptor 已 set),
 * 不用 SecurityUtils (SecurityContext 永空). 同 WorkProcessTaskController 等已修模式。
 *
 * @since SP2 (2026-06-10, feat/liushanmen-sp2-reversal)
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}")
@Tag(name = "SP2 报工撤回与二次加工")
@RequireModule("production_plan")
public class ReportReversalController {

    @Autowired
    private ReportReversalService reversalService;

    @Autowired
    private WipInventoryService wipInventoryService;

    @Autowired
    private ProductionPlanService productionPlanService;

    // ========== 报工撤回 ==========

    /**
     * 提交报工整单撤回申请。
     *
     * <p>三层 fail-closed 守卫 (下游消费 / 成品出货 / 幂等) 在 Service 层执行。
     * 有报工数据 → 创建 PENDING 申请需审批; 无报工数据 → 直通 DONE。
     */
    @RequirePermission({"production:read_write"})
    @PostMapping("/processing/batches/{batchId}/reversal")
    @Operation(summary = "提交报工撤回申请", description = "对指定批次提交整单撤回申请 (SP2)")
    public ApiResponse<ReportReversalLog> submitReversal(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "生产批次 ID") Long batchId,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        String reason = body.getOrDefault("reason", "");
        log.info("SP2 提交撤回申请: factoryId={}, batchId={}, userId={}", factoryId, batchId, userId);
        ReportReversalLog log_ = reversalService.submitReversal(factoryId, batchId, userId, reason);
        return ApiResponse.success(log_);
    }

    /**
     * 列出工厂的撤回申请记录，可按状态过滤。
     *
     * @param status 可选: PENDING / APPROVED / DONE / REJECTED
     */
    @RequirePermission({"production:read_write"})
    @GetMapping("/reversals")
    @Operation(summary = "撤回申请列表", description = "查询工厂的报工撤回申请 (SP2)")
    public ApiResponse<List<ReportReversalLog>> listReversals(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(required = false) @Parameter(description = "状态过滤: PENDING/APPROVED/DONE/REJECTED") String status) {
        List<ReportReversalLog> result = reversalService.listReversals(factoryId, status);
        return ApiResponse.success(result);
    }

    /**
     * 审批通过撤回申请。仅 PENDING 状态可批准，批准后自动执行撤回。
     */
    @RequirePermission({"production:read_write"})
    @PutMapping("/reversals/{logId}/approve")
    @Operation(summary = "审批通过撤回申请", description = "批准后自动执行撤回 (SP2)")
    public ApiResponse<Void> approveReversal(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "撤回申请 ID") Long logId,
            HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        log.info("SP2 批准撤回: logId={}, approvedBy={}, factoryId={}", logId, userId, factoryId);
        reversalService.approveReversal(logId, userId);
        return ApiResponse.success(null);
    }

    /**
     * 审批拒绝撤回申请。仅 PENDING 状态可拒绝。
     */
    @RequirePermission({"production:read_write"})
    @PutMapping("/reversals/{logId}/reject")
    @Operation(summary = "审批拒绝撤回申请", description = "拒绝撤回申请 (SP2)")
    public ApiResponse<Void> rejectReversal(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "撤回申请 ID") Long logId,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        String reason = body.getOrDefault("reason", "");
        log.info("SP2 拒绝撤回: logId={}, approvedBy={}, factoryId={}", logId, userId, factoryId);
        reversalService.rejectReversal(logId, userId, reason);
        return ApiResponse.success(null);
    }

    // ========== T6: WIP 可用库存列表 ==========

    /**
     * 查询工厂当前可用 WIP 半成品库存列表，用于二次加工选料。
     *
     * <p>仅返回 status=AVAILABLE 且 availableQuantity &gt; 0 的行 (Service 层保证)。
     */
    @RequirePermission({"production:read_write"})
    @GetMapping("/wip/available")
    @Operation(summary = "可用 WIP 半成品列表", description = "查询可用于二次加工的半成品库存 (SP2)")
    public ApiResponse<List<SemiFinishedInventory>> listAvailableWip(
            @PathVariable @Parameter(description = "工厂ID") String factoryId) {
        List<SemiFinishedInventory> result = wipInventoryService.listAvailableWip(factoryId);
        return ApiResponse.success(result);
    }

    // ========== 二次加工计划 ==========

    /**
     * 创建二次加工计划 (SP2)。
     *
     * <p>基于 WIP 半成品创建 planSourceType=SECONDARY 的生产计划。
     * WIP 扣减在 startProduction 时执行 (fail-closed)，此接口只校验余量并创建 PENDING 计划。
     *
     * <p>请求 body 示例:
     * <pre>
     * {
     *   "wipId": 123,
     *   "quantity": 50.0,
     *   "productTypeId": "PT-001",
     *   "plannedDate": "2026-06-15"
     * }
     * </pre>
     */
    @RequirePermission({"production:read_write"})
    @PostMapping("/processing/secondary-plan")
    @Operation(summary = "创建二次加工计划", description = "基于 WIP 半成品创建二次加工计划 (SP2)")
    public ApiResponse<ProductionPlanDTO> createSecondaryPlan(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");

        Long wipId = body.get("wipId") instanceof Number
                ? ((Number) body.get("wipId")).longValue() : null;
        BigDecimal quantity = body.get("quantity") != null
                ? new BigDecimal(body.get("quantity").toString()) : null;
        String productTypeId = (String) body.get("productTypeId");
        LocalDate plannedDate = body.get("plannedDate") != null
                ? LocalDate.parse(body.get("plannedDate").toString()) : null;

        log.info("SP2 创建二次加工计划: factoryId={}, wipId={}, quantity={}, productTypeId={}",
                factoryId, wipId, quantity, productTypeId);

        ProductionPlanDTO result = productionPlanService.createSecondaryPlan(
                factoryId, wipId, quantity, productTypeId, plannedDate, userId);
        return ApiResponse.success(result);
    }
}
