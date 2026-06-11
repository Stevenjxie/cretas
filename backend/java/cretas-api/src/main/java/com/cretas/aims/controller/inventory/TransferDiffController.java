package com.cretas.aims.controller.inventory;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.enums.TransferDiffDecision;
import com.cretas.aims.entity.inventory.TransferDiffRecord;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.inventory.TransferDiffService;
import com.cretas.aims.utils.TokenUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

/**
 * 调拨差异找单 REST 控制器
 *
 * <p>差异记录由 receiveTransfer 自动创建（实收 < 发货），
 * 责任人事后做 FOUND_BACK / WRITE_OFF_LOSS / TRANSFER_DAMAGE 决策。
 *
 * @since 2026-06-11
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/transfer-diffs")
@RequiredArgsConstructor
@Tag(name = "调拨差异找单", description = "调拨发货量与实收量差异追踪及处理")
@RequirePermission({"warehouse:read_write", "warehouse:read"})
public class TransferDiffController {

    private final TransferDiffService transferDiffService;
    private final MobileService mobileService;

    // ─── 列表 ─────────────────────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "查询调拨差异单列表",
            description = "status 不传时返回全部，可传 PENDING / RESOLVED。双向：source 或 target 工厂均可查。")
    public ApiResponse<List<TransferDiffRecord>> listDiffs(
            @PathVariable @NotBlank String factoryId,
            @RequestParam(required = false) String status) {
        List<TransferDiffRecord> list = transferDiffService.listDiffs(factoryId, status);
        return ApiResponse.success("查询成功", list);
    }

    @GetMapping("/by-transfer/{transferId}")
    @Operation(summary = "查询指定调拨单的差异记录",
            description = "返回该调拨单下所有差异行，用于调拨详情页内联展示。")
    public ApiResponse<List<TransferDiffRecord>> listDiffsByTransfer(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String transferId) {
        List<TransferDiffRecord> list = transferDiffService.listDiffsByTransfer(factoryId, transferId);
        return ApiResponse.success("查询成功", list);
    }

    // ─── 决策 ─────────────────────────────────────────────────────────────

    @PostMapping("/{diffRecordId}/decide")
    @Operation(summary = "对调拨差异单做决策",
            description = "decision: FOUND_BACK（找回货物）/ WRITE_OFF_LOSS（核销损耗）/ TRANSFER_DAMAGE（转报损）。"
                    + " 调出方或调入方均可决策。")
    @RequirePermission({"warehouse:read_write"})
    public ApiResponse<TransferDiffRecord> decideRecord(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String diffRecordId,
            @RequestHeader("Authorization") String authorization,
            @RequestBody Map<String, String> body) {

        String token = TokenUtils.extractToken(authorization);
        Long userId = mobileService.getUserFromToken(token).getId();
        String decisionStr = body.get("decision");
        String notes = body.get("notes");

        TransferDiffDecision decision;
        try {
            decision = TransferDiffDecision.valueOf(decisionStr);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new com.cretas.aims.exception.BusinessException(400,
                    "无效的决策类型: " + decisionStr
                            + "，可选值: FOUND_BACK / WRITE_OFF_LOSS / TRANSFER_DAMAGE");
        }

        log.info("[TDIFF] 差异单决策: factoryId={}, diffRecordId={}, decision={}, userId={}",
                factoryId, diffRecordId, decision, userId);
        TransferDiffRecord updated = transferDiffService.decideRecord(
                diffRecordId, factoryId, decision, notes, userId);
        return ApiResponse.success("决策已记录", updated);
    }
}
