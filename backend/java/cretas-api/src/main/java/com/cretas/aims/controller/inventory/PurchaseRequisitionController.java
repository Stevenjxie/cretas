package com.cretas.aims.controller.inventory;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.inventory.CreatePurchaseRequisitionRequest;
import com.cretas.aims.entity.enums.PurchaseRequisitionStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseRequisition;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.inventory.PurchaseRequisitionService;
import com.cretas.aims.utils.TokenUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 请购单 REST Controller — Sprint 5 Track D (P-REQUISITION-1).
 *
 * <p>Endpoints (复用现有 procurement 权限):
 * <ul>
 *   <li>POST   /api/mobile/{factoryId}/purchase-requisitions — 创建草稿</li>
 *   <li>GET    /api/mobile/{factoryId}/purchase-requisitions — 列表 (可按 status/requesterId 过滤)</li>
 *   <li>GET    /api/mobile/{factoryId}/purchase-requisitions/{id} — 详情</li>
 *   <li>POST   /api/mobile/{factoryId}/purchase-requisitions/{id}/submit — 提交审批</li>
 *   <li>POST   /api/mobile/{factoryId}/purchase-requisitions/{id}/approve — 审批通过</li>
 *   <li>POST   /api/mobile/{factoryId}/purchase-requisitions/{id}/reject — 审批驳回</li>
 *   <li>POST   /api/mobile/{factoryId}/purchase-requisitions/{id}/convert-to-po — 转 PO</li>
 *   <li>DELETE /api/mobile/{factoryId}/purchase-requisitions/{id} — 删除草稿 (仅 DRAFT)</li>
 * </ul>
 *
 * @since 2026-05-19
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/purchase-requisitions")
@RequiredArgsConstructor
@org.springframework.validation.annotation.Validated
@Tag(name = "请购单管理", description = "Sprint 5 Track D — 请购单 entity + state machine + auto-convert to PO")
public class PurchaseRequisitionController {

    private final PurchaseRequisitionService requisitionService;
    private final MobileService mobileService;

    @PostMapping
    @Operation(summary = "创建请购单草稿")
    @RequirePermission({"procurement:read_write", "procurement:read"})
    public ApiResponse<PurchaseRequisition> create(
            @PathVariable @NotBlank String factoryId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody CreatePurchaseRequisitionRequest request) {
        Long requesterId = extractUserId(authorization);
        log.info("创建请购单: factoryId={}, requesterId={}, itemCount={}", factoryId, requesterId,
                request.getRequestedItems() == null ? 0 : request.getRequestedItems().size());
        PurchaseRequisition pr = requisitionService.createRequisition(factoryId, requesterId, request);
        return ApiResponse.success("请购单创建成功", pr);
    }

    @GetMapping
    @Operation(summary = "请购单列表 (可按 status/requesterId 过滤)")
    @RequirePermission({"procurement:read_write", "procurement:read"})
    public ApiResponse<PageResponse<PurchaseRequisition>> list(
            @PathVariable @NotBlank String factoryId,
            @RequestParam(required = false) PurchaseRequisitionStatus status,
            @RequestParam(required = false) Long requesterId,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于0") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "每页大小必须大于0") int size) {
        return ApiResponse.success("查询成功",
                requisitionService.list(factoryId, status, requesterId, page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "请购单详情")
    @RequirePermission({"procurement:read_write", "procurement:read"})
    public ApiResponse<PurchaseRequisition> getById(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String id) {
        return ApiResponse.success("查询成功", requisitionService.getById(factoryId, id));
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "提交审批 (DRAFT → PENDING_APPROVAL)")
    @RequirePermission({"procurement:read_write", "procurement:read"})
    public ApiResponse<PurchaseRequisition> submit(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String id,
            @RequestHeader("Authorization") String authorization) {
        Long userId = extractUserId(authorization);
        return ApiResponse.success("已提交审批",
                requisitionService.submitRequisition(factoryId, id, userId));
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "审批通过 (PENDING_APPROVAL → APPROVED)")
    @RequirePermission("procurement:read_write")
    public ApiResponse<PurchaseRequisition> approve(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String id,
            @RequestHeader("Authorization") String authorization) {
        Long approverId = extractUserId(authorization);
        return ApiResponse.success("审批通过",
                requisitionService.approveRequisition(factoryId, id, approverId));
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "审批驳回 (PENDING_APPROVAL → REJECTED)")
    @RequirePermission("procurement:read_write")
    public ApiResponse<PurchaseRequisition> reject(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String id,
            @RequestHeader("Authorization") String authorization,
            @RequestBody Map<String, String> body) {
        Long approverId = extractUserId(authorization);
        String reason = body == null ? null : body.get("rejectionReason");
        return ApiResponse.success("已驳回",
                requisitionService.rejectRequisition(factoryId, id, approverId, reason));
    }

    @PostMapping("/{id}/convert-to-po")
    @Operation(summary = "转 PO (APPROVED → CONVERTED_TO_PO)",
            description = "幂等: 重复 invoke 返回已转的 PO 不再创建")
    @RequirePermission("procurement:read_write")
    public ApiResponse<PurchaseOrder> convertToPO(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String id,
            @RequestHeader("Authorization") String authorization,
            @RequestBody Map<String, String> body) {
        Long userId = extractUserId(authorization);
        String supplierId = body == null ? null : body.get("supplierId");
        return ApiResponse.success("转 PO 成功",
                requisitionService.convertToPO(factoryId, id, userId, supplierId));
    }

    /**
     * Delete DRAFT 请购单 (Bug #7 prod QA — 2026-05-20).
     *
     * <p>State machine enforcement: 只允许 DRAFT 状态删除. 非 DRAFT 状态返 400 + actionHint
     * 引导用户走 reject 流程 (避免之前 "提交+驳回" workaround 产生虚假审批历史).
     *
     * <p>软删除 (deletedAt) via BaseEntity {@code @SQLDelete}; {@code @Where(deleted_at IS NULL)}
     * 自动从后续查询中过滤.
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除请购单草稿 (仅 DRAFT)",
            description = "状态机约束: 仅 DRAFT 可删. 已提交的请购单请使用 reject 流程")
    @RequirePermission({"procurement:read_write", "procurement:read"})
    public ApiResponse<Void> delete(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String id,
            @RequestHeader("Authorization") String authorization) {
        Long userId = extractUserId(authorization);
        requisitionService.deleteDraft(factoryId, id, userId);
        return ApiResponse.successMessage("请购单已删除");
    }

    private Long extractUserId(String authorization) {
        String token = TokenUtils.extractToken(authorization);
        return mobileService.getUserFromToken(token).getId();
    }
}
