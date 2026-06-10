package com.cretas.aims.controller.inventory;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.inventory.CreateTransferRequest;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.inventory.TransferService;
import com.cretas.aims.utils.TokenUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import com.cretas.aims.annotation.RequirePermission;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.entity.inventory.InternalTransferItem;

@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/transfers")
@RequiredArgsConstructor
@Tag(name = "内部调拨", description = "总部↔分店/分厂 物资调拨管理")
public class TransferController {

    private final TransferService transferService;
    private final MobileService mobileService;

    @RequireModule("warehouse")
    @PostMapping
    @Operation(summary = "创建调拨单")
    @RequirePermission("inventory:write")
    public ApiResponse<InternalTransfer> createTransfer(
            @PathVariable @NotBlank String factoryId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody CreateTransferRequest request) {
        Long userId = extractUserId(authorization);
        log.info("创建调拨单: sourceFactory={}, targetFactory={}", factoryId, request.getTargetFactoryId());
        InternalTransfer transfer = transferService.createTransfer(factoryId, request, userId);
        return ApiResponse.success("调拨单创建成功", transfer);
    }

    @GetMapping
    @Operation(summary = "调拨单列表（双向视角）")
    @RequirePermission({"inventory:write", "inventory:read"})
    public ApiResponse<PageResponse<InternalTransfer>> listTransfers(
            @PathVariable @NotBlank String factoryId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResponse<InternalTransfer> result = transferService.getTransfers(factoryId, page, size);
        return ApiResponse.success("查询成功", result);
    }

    @GetMapping("/{transferId}")
    @Operation(summary = "调拨单详情")
    @RequirePermission({"inventory:write", "inventory:read"})
    public ApiResponse<InternalTransfer> getTransfer(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String transferId) {
        InternalTransfer transfer = transferService.getTransferById(factoryId, transferId);
        return ApiResponse.success("查询成功", transfer);
    }

    @RequireModule("warehouse")
    @PostMapping("/{transferId}/request")
    @Operation(summary = "提交调拨申请")
    @RequirePermission("inventory:write")
    public ApiResponse<InternalTransfer> requestTransfer(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String transferId,
            @RequestHeader("Authorization") String authorization) {
        Long userId = extractUserId(authorization);
        InternalTransfer transfer = transferService.requestTransfer(factoryId, transferId, userId);
        return ApiResponse.success("调拨申请已提交", transfer);
    }

    @RequireModule("warehouse")
    @PostMapping("/{transferId}/approve")
    @Operation(summary = "审批通过")
    @RequirePermission("inventory:write")
    public ApiResponse<InternalTransfer> approveTransfer(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String transferId,
            @RequestHeader("Authorization") String authorization) {
        Long userId = extractUserId(authorization);
        InternalTransfer transfer = transferService.approveTransfer(factoryId, transferId, userId);
        return ApiResponse.success("调拨已审批通过", transfer);
    }

    @RequireModule("warehouse")
    @PostMapping("/{transferId}/reject")
    @Operation(summary = "驳回调拨")
    @RequirePermission("inventory:write")
    public ApiResponse<InternalTransfer> rejectTransfer(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String transferId,
            @RequestHeader("Authorization") String authorization,
            @RequestParam(required = false) String reason) {
        Long userId = extractUserId(authorization);
        InternalTransfer transfer = transferService.rejectTransfer(factoryId, transferId, userId, reason);
        return ApiResponse.success("调拨已驳回", transfer);
    }

    @RequireModule("warehouse")
    @PostMapping("/{transferId}/ship")
    @Operation(summary = "调拨发货（扣减调出方库存）")
    @RequirePermission("inventory:write")
    public ApiResponse<InternalTransfer> shipTransfer(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String transferId,
            @RequestHeader("Authorization") String authorization) {
        Long userId = extractUserId(authorization);
        InternalTransfer transfer = transferService.shipTransfer(factoryId, transferId, userId);
        return ApiResponse.success("调拨已发货", transfer);
    }

    @RequireModule("warehouse")
    @PostMapping("/{transferId}/receive")
    @Operation(summary = "调拨签收", description = "可选传入 {itemActualQuantities: {itemId: actualQty}} 覆盖各行实收量")
    @RequirePermission("inventory:write")
    public ApiResponse<InternalTransfer> receiveTransfer(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String transferId,
            @RequestHeader("Authorization") String authorization,
            @RequestBody(required = false) Map<String, Object> body) {
        Long userId = extractUserId(authorization);
        // BUG-3 修复: 从可选 body 提取 itemActualQuantities map (itemId Long → actualQuantity BigDecimal)。
        // body=null 或不含该字段时向后兼容 (全部回退为发货量)。
        Map<Long, BigDecimal> itemActualQuantities = null;
        if (body != null && body.containsKey("itemActualQuantities")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = (Map<String, Object>) body.get("itemActualQuantities");
            if (raw != null) {
                itemActualQuantities = new java.util.HashMap<>();
                for (Map.Entry<String, Object> e : raw.entrySet()) {
                    Long id = Long.parseLong(e.getKey());
                    BigDecimal qty = new BigDecimal(e.getValue().toString());
                    itemActualQuantities.put(id, qty);
                }
            }
        }
        InternalTransfer transfer = transferService.receiveTransfer(factoryId, transferId, userId, itemActualQuantities);
        return ApiResponse.success("调拨已签收", transfer);
    }

    @RequireModule("warehouse")
    @PostMapping("/{transferId}/confirm")
    @Operation(summary = "确认调拨（调入方入库）")
    @RequirePermission("inventory:write")
    public ApiResponse<InternalTransfer> confirmTransfer(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String transferId,
            @RequestHeader("Authorization") String authorization) {
        Long userId = extractUserId(authorization);
        InternalTransfer transfer = transferService.confirmTransfer(factoryId, transferId, userId);
        return ApiResponse.success("调拨已确认，库存已更新", transfer);
    }

    @RequireModule("warehouse")
    @PostMapping("/{transferId}/cancel")
    @Operation(summary = "取消调拨")
    @RequirePermission("inventory:write")
    public ApiResponse<InternalTransfer> cancelTransfer(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String transferId,
            @RequestHeader("Authorization") String authorization,
            @RequestParam(required = false) String reason) {
        Long userId = extractUserId(authorization);
        InternalTransfer transfer = transferService.cancelTransfer(factoryId, transferId, userId, reason);
        return ApiResponse.success("调拨已取消", transfer);
    }

    @GetMapping("/statistics")
    @Operation(summary = "调拨统计")
    @RequirePermission({"inventory:write", "inventory:read", "report:read"})
    public ApiResponse<Map<String, Object>> getStatistics(
            @PathVariable @NotBlank String factoryId) {
        Map<String, Object> stats = transferService.getTransferStatistics(factoryId);
        return ApiResponse.success("查询成功", stats);
    }

    // ==================== B1 两阶段批次选择 (PR #309 B1=C, 2026-05-11) ====================

    @GetMapping("/{transferId}/items/{itemId}/available-batches")
    @Operation(summary = "B1: 列出 transfer item 在 source warehouse 当前可用批次 (SHIP 前选批次 dropdown)")
    @RequirePermission({"inventory:write", "inventory:read"})
    public ApiResponse<List<Map<String, Object>>> getAvailableBatchesForItem(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String transferId,
            @PathVariable Long itemId) {
        List<Map<String, Object>> batches = transferService.getAvailableBatchesForItem(factoryId, transferId, itemId);
        return ApiResponse.success("查询成功", batches);
    }

    @RequireModule("warehouse")
    @PutMapping("/{transferId}/items/{itemId}/source-batch")
    @Operation(summary = "B1: 更新 transfer item 的预选批次 (status=APPROVED 时, null=清除走 FEFO)")
    @RequirePermission("inventory:write")
    public ApiResponse<InternalTransferItem> updateItemSourceBatch(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String transferId,
            @PathVariable Long itemId,
            @RequestBody(required = false) Map<String, String> body) {
        String sourceBatchId = body != null ? body.get("sourceBatchId") : null;
        InternalTransferItem updated = transferService.updateItemSourceBatch(factoryId, transferId, itemId, sourceBatchId);
        return ApiResponse.success("批次选择已保存", updated);
    }

    private Long extractUserId(String authorization) {
        String token = TokenUtils.extractToken(authorization);
        return mobileService.getUserFromToken(token).getId();
    }
}
