package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.bom.BomCopyCandidateDTO;
import com.cretas.aims.dto.bom.BomCopyToDraftRequest;
import com.cretas.aims.dto.bom.BomSeasoningResponse;
import com.cretas.aims.dto.bom.BomSeasoningSaveRequest;
import com.cretas.aims.dto.bom.BomSeasoningWorkspaceResponse;
import com.cretas.aims.dto.bom.SeasoningBindingCreateRequest;
import com.cretas.aims.dto.bom.SeasoningBindingMutationResponse;
import com.cretas.aims.dto.bom.SeasoningBindingUpdateRequest;
import com.cretas.aims.dto.bom.CreateBomRecipeRequest;
import com.cretas.aims.dto.bom.CreateBomRecipeRequest.BomRecipeItemDTO;
import com.cretas.aims.dto.bom.UpdateBomRecipeRequest;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.service.bom.BomCopyService;
import com.cretas.aims.service.bom.BomRecipeService;
import com.cretas.aims.service.bom.BomSeasoningWorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * M-BOM-1 BOM 配方主子表 Controller (Track D1).
 *
 * <p>路径前缀: {@code /api/mobile/{factoryId}/bom/recipes}，是 BOM 配方与明细的唯一管理入口。
 *
 * <p>权限: 沿用 BomController 模式 ({@code production:read_write} / {@code rd:read_write}
 * / {@code finance:read_write}), 而非 SCHEMA spec 提议的 {@code bom:write} (新权限码暂未引入).
 *
 * @author Cretas Team / Track D1
 * @since 2026-05-14
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/bom/recipes")
@RequiredArgsConstructor
@Tag(name = "BOM 配方管理 (M-BOM-1)", description = "BOM 配方主子表 CRUD + 状态机 + 克隆 + 成本计算")
@RequireModule("bom")
public class BomRecipeController {

    private final BomRecipeService recipeService;
    private final BomCopyService bomCopyService;
    private final BomSeasoningWorkspaceService seasoningWorkspaceService;

    // ========== List / detail ==========

    @GetMapping
    @Operation(summary = "分页查询 BOM 配方列表 (可按 status 过滤)")
    public ApiResponse<Page<BomRecipe>> listRecipes(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(required = false) @Parameter(description = "状态过滤 DRAFT/ACTIVE/ARCHIVED") BomRecipe.Status status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("List recipes: factory={}, status={}, page={}", factoryId, status, page);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
        return ApiResponse.success(recipeService.listRecipes(factoryId, status, pageable));
    }

    @GetMapping("/{recipeId}")
    @Operation(summary = "BOM 配方详情 (含 items)")
    public ApiResponse<BomRecipe> getRecipe(
            @PathVariable String factoryId,
            @PathVariable String recipeId) {
        return ApiResponse.success(recipeService.getRecipe(factoryId, recipeId));
    }

    @GetMapping("/by-product/{productTypeId}/current")
    @Operation(summary = "取产品当前生效 BOM (status=ACTIVE + is_current=TRUE)")
    public ApiResponse<BomRecipe> getCurrentByProduct(
            @PathVariable String factoryId,
            @PathVariable String productTypeId) {
        Optional<BomRecipe> recipe = recipeService.getCurrentRecipe(factoryId, productTypeId);
        return recipe.map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.error(404, "产品无生效 BOM: " + productTypeId));
    }

    @GetMapping("/by-product/{productTypeId}/versions")
    @Operation(summary = "取产品所有版本 BOM (含历史)")
    public ApiResponse<List<BomRecipe>> getVersionsByProduct(
            @PathVariable String factoryId,
            @PathVariable String productTypeId) {
        return ApiResponse.success(recipeService.getRecipeVersions(factoryId, productTypeId));
    }

    @GetMapping("/copy-candidates")
    @Operation(summary = "查询同源且共享工序的当前生效 BOM 复制候选")
    public ApiResponse<List<BomCopyCandidateDTO>> getCopyCandidates(
            @PathVariable String factoryId,
            @RequestParam String targetProductTypeId) {
        return ApiResponse.success(bomCopyService.listCandidates(factoryId, targetProductTypeId));
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @PostMapping("/copy-to-draft")
    @Operation(summary = "逐条复制同源成品 BOM 规则为目标产品可编辑草稿")
    public ApiResponse<BomRecipe> copyToDraft(
            @PathVariable String factoryId,
            @Valid @RequestBody BomCopyToDraftRequest request) {
        return ApiResponse.success(bomCopyService.copySelectedRulesToDraft(factoryId, request));
    }

    // ========== Lifecycle ==========

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @PostMapping
    @Operation(summary = "创建 BOM 配方草稿 (DRAFT)")
    public ApiResponse<BomRecipe> createRecipe(
            @PathVariable String factoryId,
            @Valid @RequestBody CreateBomRecipeRequest request) {
        log.info("Create recipe: factory={}, product={}, items={}",
                factoryId, request.getProductTypeId(), request.getItems().size());
        return ApiResponse.success(recipeService.createRecipe(factoryId, request));
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @PutMapping("/{recipeId}")
    @Operation(summary = "更新 BOM 配方 (仅 DRAFT)")
    public ApiResponse<BomRecipe> updateRecipe(
            @PathVariable String factoryId,
            @PathVariable String recipeId,
            @Valid @RequestBody UpdateBomRecipeRequest request) {
        return ApiResponse.success(recipeService.updateRecipe(factoryId, recipeId, request));
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @PostMapping("/{recipeId}/activate")
    @Operation(summary = "激活 BOM 配方 (DRAFT → ACTIVE)")
    public ApiResponse<BomRecipe> activateRecipe(
            @PathVariable String factoryId,
            @PathVariable String recipeId,
            @RequestParam(required = false) @Parameter(description = "操作人 userId, 可选") Long operatorId) {
        return ApiResponse.success(recipeService.activateRecipe(factoryId, recipeId, operatorId));
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @PostMapping("/{recipeId}/clone")
    @Operation(summary = "克隆 BOM 配方为新版本 (version+1, status=DRAFT)")
    public ApiResponse<BomRecipe> cloneRecipe(
            @PathVariable String factoryId,
            @PathVariable String recipeId) {
        return ApiResponse.success(recipeService.cloneRecipe(factoryId, recipeId));
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @PostMapping("/{recipeId}/archive")
    @Operation(summary = "归档 BOM 配方 (→ ARCHIVED)")
    public ApiResponse<BomRecipe> archiveRecipe(
            @PathVariable String factoryId,
            @PathVariable String recipeId) {
        return ApiResponse.success(recipeService.archiveRecipe(factoryId, recipeId));
    }

    @PostMapping("/{recipeId}/calculate-cost")
    @Operation(summary = "重算 BOM 成本 (返回更新后的 recipe)")
    public ApiResponse<BomRecipe> calculateCost(
            @PathVariable String factoryId,
            @PathVariable String recipeId) {
        return ApiResponse.success(recipeService.calculateCost(factoryId, recipeId));
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @DeleteMapping("/{recipeId}")
    @Operation(summary = "软删 BOM 配方 (仅 DRAFT; ACTIVE/ARCHIVED 用 archive)")
    public ApiResponse<Void> deleteRecipe(
            @PathVariable String factoryId,
            @PathVariable String recipeId) {
        recipeService.deleteRecipe(factoryId, recipeId);
        return ApiResponse.success(null);
    }

    // ========== Item-level CRUD ==========

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @PostMapping("/{recipeId}/items")
    @Operation(summary = "添加配方项 (仅 DRAFT)")
    public ApiResponse<BomRecipeItem> addItem(
            @PathVariable String factoryId,
            @PathVariable String recipeId,
            @Valid @RequestBody BomRecipeItemDTO dto) {
        return ApiResponse.success(recipeService.addItem(factoryId, recipeId, dto));
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @PutMapping("/items/{itemId}")
    @Operation(summary = "更新配方项 (仅 DRAFT)")
    public ApiResponse<BomRecipeItem> updateItem(
            @PathVariable String factoryId,
            @PathVariable Long itemId,
            @Valid @RequestBody BomRecipeItemDTO dto) {
        return ApiResponse.success(recipeService.updateItem(factoryId, itemId, dto));
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @DeleteMapping("/items/{itemId}")
    @Operation(summary = "删除配方项 (仅 DRAFT)")
    public ApiResponse<Void> deleteItem(
            @PathVariable String factoryId,
            @PathVariable Long itemId) {
        recipeService.deleteItem(factoryId, itemId);
        return ApiResponse.success(null);
    }

    // ========== U5: 调料配方 CRUD ==========

    @GetMapping("/{recipeId}/seasoning")
    @Operation(summary = "取 BOM 调料配方 (锅序参数 + 注射/熟制段明细)")
    public ApiResponse<BomSeasoningResponse> getSeasoning(
            @PathVariable String factoryId,
            @PathVariable String recipeId) {
        return ApiResponse.success(recipeService.getSeasoning(factoryId, recipeId));
    }

    @GetMapping("/by-product/{productTypeId}/seasoning")
    @Operation(summary = "按产品取当前 BOM 调料配方 (is_current=TRUE)")
    public ApiResponse<BomSeasoningResponse> getSeasoningByProduct(
            @PathVariable String factoryId,
            @PathVariable String productTypeId) {
        return recipeService.getSeasoningByProduct(factoryId, productTypeId)
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.error(404, "产品未建 BOM 配方: " + productTypeId));
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @PutMapping("/{recipeId}/seasoning")
    @Operation(summary = "全量替换 BOM 调料配方 (仅 DRAFT)")
    public ApiResponse<BomSeasoningResponse> saveSeasoning(
            @PathVariable String factoryId,
            @PathVariable String recipeId,
            @Valid @RequestBody BomSeasoningSaveRequest request) {
        return ApiResponse.success(recipeService.saveSeasoning(factoryId, recipeId, request));
    }

    @GetMapping("/{recipeId}/seasoning/workspace")
    @Operation(summary = "获取工序优先的调料配置工作区")
    public ApiResponse<BomSeasoningWorkspaceResponse> getSeasoningWorkspace(
            @PathVariable String factoryId,
            @PathVariable String recipeId) {
        return ApiResponse.success(seasoningWorkspaceService.getWorkspace(factoryId, recipeId));
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @PostMapping("/{recipeId}/seasoning/processes/{workProcessId}/bindings")
    @Operation(summary = "向指定工序新增调料绑定")
    public ApiResponse<SeasoningBindingMutationResponse> createSeasoningBinding(
            @PathVariable String factoryId,
            @PathVariable String recipeId,
            @PathVariable String workProcessId,
            @Valid @RequestBody SeasoningBindingCreateRequest request) {
        return ApiResponse.success(seasoningWorkspaceService.createBinding(
                factoryId, recipeId, workProcessId, request));
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @PutMapping("/{recipeId}/seasoning/bindings/{bindingId}")
    @Operation(summary = "修改单条工序调料绑定")
    public ApiResponse<SeasoningBindingMutationResponse> updateSeasoningBinding(
            @PathVariable String factoryId,
            @PathVariable String recipeId,
            @PathVariable Long bindingId,
            @Valid @RequestBody SeasoningBindingUpdateRequest request) {
        return ApiResponse.success(seasoningWorkspaceService.updateBinding(
                factoryId, recipeId, bindingId, request));
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @DeleteMapping("/{recipeId}/seasoning/bindings/{bindingId}")
    @Operation(summary = "删除单条工序调料绑定")
    public ApiResponse<SeasoningBindingMutationResponse> deleteSeasoningBinding(
            @PathVariable String factoryId,
            @PathVariable String recipeId,
            @PathVariable Long bindingId,
            @RequestParam Long expectedRevision) {
        return ApiResponse.success(seasoningWorkspaceService.deleteBinding(
                factoryId, recipeId, bindingId, expectedRevision));
    }

}
