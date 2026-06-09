package com.cretas.aims.controller.inventory;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.enums.ExceptionDecision;
import com.cretas.aims.entity.inventory.PurchaseException;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.inventory.PurchaseExceptionService;
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
 * 采购异常单 REST 控制器（SP6）
 *
 * <p>异常单由 confirmReceive 自动创建（超收/少收），采购员事后做 ACCEPT/RETURN 决策。
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/purchase-exceptions")
@RequiredArgsConstructor
@Tag(name = "采购异常管理", description = "SP6 采购超收/少收异常单管理")
@RequirePermission({"procurement:read_write", "procurement:read"})
public class PurchaseExceptionController {

    private final PurchaseExceptionService purchaseExceptionService;
    private final MobileService mobileService;

    // ─── 查询异常单列表 ────────────────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "查询采购异常单列表", description = "status 不传时返回全部，可传 PENDING/RESOLVED 过滤")
    public ApiResponse<List<PurchaseException>> listExceptions(
            @PathVariable @NotBlank String factoryId,
            @RequestParam(required = false) String status) {
        List<PurchaseException> list = purchaseExceptionService.listExceptions(factoryId, status);
        return ApiResponse.success("查询成功", list);
    }

    // ─── 异常单决策 ────────────────────────────────────────────────────────────

    @PostMapping("/{exceptionId}/decide")
    @Operation(summary = "对采购异常单做决策", description = "decision: ACCEPT_OVER（接受超收）/ RETURN_OVER（退回超收）/ ACCEPT_UNDER（接受少收）/ REORDER（补采购）")
    @RequirePermission({"procurement:read_write"})
    public ApiResponse<PurchaseException> decideException(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String exceptionId,
            @RequestHeader("Authorization") String authorization,
            @RequestBody Map<String, String> body) {
        Long userId = extractUserId(authorization);
        String decisionStr = body.get("decision");
        String notes = body.get("notes");

        ExceptionDecision decision;
        try {
            decision = ExceptionDecision.valueOf(decisionStr);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new com.cretas.aims.exception.BusinessException(400,
                    "无效的决策类型: " + decisionStr + "，可选值: ACCEPT_OVER / RETURN_OVER / ACCEPT_UNDER / REORDER");
        }

        log.info("[SP6] 异常单决策: factoryId={}, exceptionId={}, decision={}, userId={}",
                factoryId, exceptionId, decision, userId);
        PurchaseException updated = purchaseExceptionService.decideException(
                exceptionId, factoryId, decision, notes, userId);
        return ApiResponse.success("决策已记录", updated);
    }

    // ─── 私有工具 ────────────────────────────────────────────────────────────

    private Long extractUserId(String authorization) {
        String token = TokenUtils.extractToken(authorization);
        return mobileService.getUserFromToken(token).getId();
    }
}
