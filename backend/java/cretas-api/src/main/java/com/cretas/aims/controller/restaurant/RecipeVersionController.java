package com.cretas.aims.controller.restaurant;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.restaurant.RecipeVersion;
import com.cretas.aims.service.restaurant.RecipeVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 餐饮配方版本化 Controller (#60 Phase 2).
 *
 * <p>独立 row-per-approval 版本管理: createDraft / submitForApproval / approve / reject /
 * getCurrentApproved / getHistory. 状态机 + 显式 supersede 由 {@link RecipeVersionService}
 * 实现 (借 bom.BomVersion 模式但 restaurant 包本地化, enum 本地复制避免跨包耦合).
 *
 * <p>防呆: approve 重复审批已 APPROVED 行 → service 抛 IllegalStateException → 全局 handler
 * 映射 409 (Rule 4 幂等). approve 响应含旧→新 snapshot diff + 成本影响, 前端渲染确认对话框。
 *
 * @author Cretas Team / #60 Phase 2
 * @since 2026-06-04
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/recipe-versions")
@RequiredArgsConstructor
@Tag(name = "餐饮-配方版本化")
public class RecipeVersionController {

    private final RecipeVersionService recipeVersionService;

    // ==================== 历史 / 当前 ====================

    @GetMapping("/by-dish/{productTypeId}")
    @Operation(summary = "配方版本历史", description = "某菜品的全部版本, 最新在前")
    public ApiResponse<List<RecipeVersion>> history(
            @PathVariable String factoryId,
            @PathVariable String productTypeId) {
        return ApiResponse.success(recipeVersionService.getHistory(factoryId, productTypeId));
    }

    @GetMapping("/by-dish/{productTypeId}/current")
    @Operation(summary = "当前生效版本", description = "状态=APPROVED 且 effective_to IS NULL 的版本")
    public ApiResponse<RecipeVersion> current(
            @PathVariable String factoryId,
            @PathVariable String productTypeId) {
        return ApiResponse.success(
                recipeVersionService.getCurrentApproved(factoryId, productTypeId).orElse(null));
    }

    @GetMapping("/{versionId}")
    @Operation(summary = "版本详情")
    public ApiResponse<RecipeVersion> detail(
            @PathVariable String factoryId,
            @PathVariable String versionId) {
        return ApiResponse.success(recipeVersionService.getById(factoryId, versionId));
    }

    // ==================== 创建草稿 ====================

    @RequirePermission({"production:read_write", "rd:read_write"})
    @RequireModule("restaurant")
    @PostMapping("/by-dish/{productTypeId}/draft")
    @Operation(summary = "创建配方版本草稿", description = "快照该菜当前在售配方为新版本草稿")
    public ApiResponse<RecipeVersion> createDraft(
            @PathVariable String factoryId,
            @PathVariable String productTypeId,
            @RequestAttribute("userId") @Parameter(hidden = true) Long userId) {
        log.info("创建配方版本草稿: factoryId={}, productTypeId={}, userId={}",
                factoryId, productTypeId, userId);
        RecipeVersion draft = recipeVersionService.createDraft(factoryId, productTypeId, userId);
        return ApiResponse.success("配方版本草稿已创建", draft);
    }

    // ==================== 提交审批 ====================

    @RequirePermission({"production:read_write", "rd:read_write"})
    @RequireModule("restaurant")
    @PostMapping("/{versionId}/submit")
    @Operation(summary = "提交审批", description = "DRAFT → PENDING_APPROVAL")
    public ApiResponse<RecipeVersion> submit(
            @PathVariable String factoryId,
            @PathVariable String versionId) {
        return ApiResponse.success("已提交审批",
                recipeVersionService.submitForApproval(factoryId, versionId));
    }

    // ==================== 审批通过 ====================

    @RequirePermission({"production:read_write", "rd:read_write"})
    @RequireModule("restaurant")
    @PostMapping("/{versionId}/approve")
    @Operation(summary = "审批通过", description = "→ APPROVED, 自动 supersede 旧 APPROVED 版本. 重复审批已通过版本返 409 (Rule 4 幂等)")
    public ApiResponse<RecipeVersion> approve(
            @PathVariable String factoryId,
            @PathVariable String versionId,
            @RequestAttribute("userId") @Parameter(hidden = true) Long userId) {
        log.info("审批配方版本: factoryId={}, versionId={}, approver={}", factoryId, versionId, userId);
        RecipeVersion approved = recipeVersionService.approve(factoryId, versionId, userId);
        return ApiResponse.success("配方版本已审批生效", approved);
    }

    // ==================== 审批驳回 ====================

    @RequirePermission({"production:read_write", "rd:read_write"})
    @RequireModule("restaurant")
    @PostMapping("/{versionId}/reject")
    @Operation(summary = "审批驳回", description = "PENDING_APPROVAL → REJECTED (terminal)")
    public ApiResponse<RecipeVersion> reject(
            @PathVariable String factoryId,
            @PathVariable String versionId,
            @RequestAttribute("userId") @Parameter(hidden = true) Long userId,
            @RequestBody RejectRequest body) {
        return ApiResponse.success("配方版本已驳回",
                recipeVersionService.reject(factoryId, versionId, userId,
                        body == null ? null : body.getReason()));
    }

    /** 驳回原因 body. */
    @Data
    public static class RejectRequest {
        private String reason;
    }
}
