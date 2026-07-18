package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.bom.BomCostSummaryDTO;
import com.cretas.aims.dto.bom.BomYieldApplyRequest;
import com.cretas.aims.dto.bom.BomYieldApplyResultDTO;
import com.cretas.aims.dto.bom.BomYieldEstimateDTO;
import com.cretas.aims.dto.bom.BomYieldPreviewItemDTO;
import com.cretas.aims.dto.bom.CreateLaborCostRequest;
import com.cretas.aims.dto.bom.UpdateLaborCostRequest;
import com.cretas.aims.entity.bom.BomChangeLog;
import com.cretas.aims.entity.bom.BomYieldSuggestion;
import com.cretas.aims.entity.bom.LaborCostConfig;
import com.cretas.aims.entity.bom.OverheadCostConfig;
import com.cretas.aims.repository.bom.BomChangeLogRepository;
import com.cretas.aims.repository.bom.BomYieldSuggestionRepository;
import com.cretas.aims.dto.orchestration.BomTreeResult;
import com.cretas.aims.service.BomService;
import com.cretas.aims.service.bom.BomYieldEstimateService;
import com.cretas.aims.service.orchestration.RecursiveBomExpansionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * BOM成本管理控制器
 * 提供BOM物料清单和成本配置管理接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-13
 */
/**
 * Bug #318 fix: method-level @RequirePermission on write methods only.
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/bom")
@RequiredArgsConstructor
@Tag(name = "BOM成本管理", description = "BOM物料清单和成本配置管理")
@RequireModule("bom")
public class BomController {

    private final BomService bomService;
    private final RecursiveBomExpansionService recursiveBomExpansionService;
    private final BomYieldEstimateService bomYieldEstimateService;

    /** P1-9 BOM 变更痕迹查询 Repository (可选注入, 老环境未部署 migration 时不阻塞) */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private BomChangeLogRepository bomChangeLogRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private BomYieldSuggestionRepository bomYieldSuggestionRepository;

    // ========== BOM Items (原辅料配方) ==========

    /**
     * M-MATTREE-1 (Sprint 4 W2): 多级 BOM 树展开 + 叶子节点库存短缺计算.
     *
     * <p>基于当前 ACTIVE/current BomRecipe 递归展开 sub-BOM
     * (半成品 → 原料), 返回完整树结构 + maxDepth / leafCount / shortfallLeafCount 统计 +
     * 循环检测 (cycleDetected + cycleTypeIds)。</p>
     */
    @GetMapping("/tree/{productTypeId}")
    @Operation(summary = "多级 BOM 树展开 (M-MATTREE-1)",
            description = "递归展开成品 BOM 到原料叶子, 叶子节点附加库存可用量 + 短缺数量")
    public ApiResponse<BomTreeResult> getBomTree(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "产品类型ID (树根)") String productTypeId,
            @RequestParam(value = "quantity", defaultValue = "1")
            @Parameter(description = "计划生产数量 (默认 1)") java.math.BigDecimal quantity) {
        log.info("Expanding BOM tree: factoryId={}, productTypeId={}, quantity={}",
                factoryId, productTypeId, quantity);
        return ApiResponse.success(
                recursiveBomExpansionService.expandTree(factoryId, productTypeId, quantity));
    }

    /**
     * P1-9 BOM 变更痕迹查询 (v1 §2.2.6).
     * 返回指定产品 BOM 的所有变更历史 (CREATE/UPDATE/DELETE), 按时间倒序.
     */
    @GetMapping("/change-logs/{recipeId}")
    @Operation(summary = "获取 BOM 变更痕迹 (P1-9)")
    public ApiResponse<List<BomChangeLog>> getBomChangeLogs(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "BOM Recipe ID") String recipeId) {
        if (bomChangeLogRepository == null) {
            return ApiResponse.success(java.util.Collections.emptyList());
        }
        return ApiResponse.success(bomChangeLogRepository
                .findByFactoryIdAndBomRecipeIdAndDeletedAtIsNullOrderByCreatedAtDesc(factoryId, recipeId));
    }

    // ========== Labor Cost (人工费用) ==========

    @GetMapping("/labor")
    @Operation(summary = "获取人工费用配置")
    public ApiResponse<List<LaborCostConfig>> getLaborCosts(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(required = false) @Parameter(description = "产品类型ID") String productTypeId) {
        log.info("Getting labor costs: factoryId={}, productTypeId={}", factoryId, productTypeId);
        if (productTypeId != null && !productTypeId.isEmpty()) {
            return ApiResponse.success(bomService.getLaborCostsByProduct(factoryId, productTypeId));
        }
        return ApiResponse.success(bomService.getGlobalLaborCosts(factoryId));
    }

    @GetMapping("/labor/all")
    @Operation(summary = "获取所有人工费用配置")
    public ApiResponse<List<LaborCostConfig>> getAllLaborCosts(
            @PathVariable @Parameter(description = "工厂ID") String factoryId) {
        log.info("Getting all labor costs: factoryId={}", factoryId);
        return ApiResponse.success(bomService.getAllLaborCosts(factoryId));
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @PostMapping("/labor")
    @Operation(summary = "添加人工费用")
    public ApiResponse<LaborCostConfig> addLaborCost(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @Valid @RequestBody CreateLaborCostRequest request) {
        log.info("Adding labor cost: factoryId={}, processName={}", factoryId, request.getProcessName());
        LaborCostConfig config = toLaborCostConfig(request);
        config.setFactoryId(factoryId);
        return ApiResponse.success(bomService.saveLaborCost(config));
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @PutMapping("/labor/{id}")
    @Operation(summary = "更新人工费用")
    public ApiResponse<LaborCostConfig> updateLaborCost(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "人工费用ID") Long id,
            @Valid @RequestBody UpdateLaborCostRequest request) {
        log.info("Updating labor cost: factoryId={}, id={}", factoryId, id);
        LaborCostConfig config = toLaborCostConfig(request);
        config.setId(id);
        config.setFactoryId(factoryId);
        return ApiResponse.success(bomService.saveLaborCost(config));
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @DeleteMapping("/labor/{id}")
    @Operation(summary = "删除人工费用")
    public ApiResponse<Void> deleteLaborCost(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "人工费用ID") Long id) {
        log.info("Deleting labor cost: factoryId={}, id={}", factoryId, id);
        bomService.deleteLaborCost(id);
        return ApiResponse.success(null);
    }

    // ========== Overhead Cost (均摊费用) ==========

    @GetMapping("/overhead")
    @Operation(summary = "获取均摊费用配置")
    public ApiResponse<List<OverheadCostConfig>> getOverheadCosts(
            @PathVariable @Parameter(description = "工厂ID") String factoryId) {
        log.info("Getting overhead costs: factoryId={}", factoryId);
        return ApiResponse.success(bomService.getOverheadCosts(factoryId));
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @PostMapping("/overhead")
    @Operation(summary = "添加均摊费用")
    public ApiResponse<OverheadCostConfig> addOverheadCost(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestBody OverheadCostConfig config) {
        log.info("Adding overhead cost: factoryId={}, name={}", factoryId, config.getName());
        config.setFactoryId(factoryId);
        return ApiResponse.success(bomService.saveOverheadCost(config));
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @PutMapping("/overhead/{id}")
    @Operation(summary = "更新均摊费用")
    public ApiResponse<OverheadCostConfig> updateOverheadCost(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "均摊费用ID") Long id,
            @RequestBody OverheadCostConfig config) {
        log.info("Updating overhead cost: factoryId={}, id={}", factoryId, id);
        // T-R5-3 (R5 audit §3 BUG#1, 2026-05-12): was a blind save(body) that
        // dropped DB-only audit columns (createdAt/version) to NULL on partial
        // payloads. Service now select-then-merge — only client-supplied fields
        // are overwritten; everything else stays.
        return ApiResponse.success(bomService.updateOverheadCost(factoryId, id, config));
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @DeleteMapping("/overhead/{id}")
    @Operation(summary = "删除均摊费用")
    public ApiResponse<Void> deleteOverheadCost(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "均摊费用ID") Long id) {
        log.info("Deleting overhead cost: factoryId={}, id={}", factoryId, id);
        bomService.deleteOverheadCost(id);
        return ApiResponse.success(null);
    }

    // ========== Cost Calculation (成本计算) ==========

    @GetMapping("/cost-summary/{productTypeId}")
    @Operation(summary = "计算产品成本汇总")
    public ApiResponse<BomCostSummaryDTO> calculateCost(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "产品类型ID") String productTypeId) {
        log.info("Calculating product cost: factoryId={}, productTypeId={}", factoryId, productTypeId);
        return ApiResponse.success(bomService.calculateProductCost(factoryId, productTypeId));
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @PostMapping("/cost-summary/batch")
    @Operation(summary = "批量计算产品成本")
    public ApiResponse<List<BomCostSummaryDTO>> calculateCostsBatch(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestBody List<String> productTypeIds) {
        log.info("Calculating batch product costs: factoryId={}, count={}", factoryId, productTypeIds.size());
        return ApiResponse.success(bomService.calculateProductCosts(factoryId, productTypeIds));
    }

    @GetMapping("/products-with-bom")
    @Operation(summary = "获取有BOM配置的产品列表")
    public ApiResponse<List<String>> getProductTypesWithBom(
            @PathVariable @Parameter(description = "工厂ID") String factoryId) {
        log.info("Getting product types with BOM: factoryId={}", factoryId);
        return ApiResponse.success(bomService.getProductTypesWithBom(factoryId));
    }

    // ========== BOM 出成率评估 (yield-estimate) ==========

    /**
     * 单产品出成率评估 (只读).
     * GET /api/mobile/{factoryId}/bom/yield-estimate?productTypeId={id}&materialCategory=RAW
     */
    @GetMapping("/yield-estimate")
    @Operation(summary = "BOM 出成率评估 (单产品)",
            description = "基于最近 10 个已完成批次的 cumulativeYieldRate 中位数推荐出成率. 样本 <3 时返回 null + reason.")
    public ApiResponse<BomYieldEstimateDTO> getYieldEstimate(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam @Parameter(description = "产品类型ID") String productTypeId,
            @RequestParam(defaultValue = "RAW") @Parameter(description = "物料分类 (默认 RAW)") String materialCategory) {
        log.info("[BomYieldEstimate] estimate: factoryId={}, productTypeId={}, materialCategory={}",
                factoryId, productTypeId, materialCategory);
        return ApiResponse.success(
                bomYieldEstimateService.estimateForProduct(factoryId, productTypeId, materialCategory));
    }

    /**
     * 批量预览 (只读, 不写).
     * POST /api/mobile/{factoryId}/bom/yield-estimate/recalculate-preview
     * body: {} 或 { productTypeIds: [...] }
     */
    @PostMapping("/yield-estimate/recalculate-preview")
    @Operation(summary = "BOM 出成率批量预览 (只读)",
            description = "对全部或指定产品评估出成率, 返回 UPDATABLE/INSUFFICIENT_SAMPLES/SKIP 三种状态行. 不修改任何数据.")
    public ApiResponse<List<BomYieldPreviewItemDTO>> recalculatePreview(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestBody(required = false) java.util.Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> productTypeIds = (body != null && body.containsKey("productTypeIds"))
                ? (List<String>) body.get("productTypeIds")
                : Collections.emptyList();
        log.info("[BomYieldEstimate] preview: factoryId={}, productTypeIds size={}",
                factoryId, productTypeIds == null ? 0 : productTypeIds.size());
        return ApiResponse.success(
                bomYieldEstimateService.recalculatePreview(factoryId,
                        (productTypeIds != null && !productTypeIds.isEmpty()) ? productTypeIds : null));
    }

    /**
     * 批量应用用户选中行 (写操作, 需 production:read_write).
     * POST /api/mobile/{factoryId}/bom/yield-estimate/recalculate-apply
     * body: [ { recipeId, yieldRate }, ... ]
     */
    @RequirePermission({"production:read_write"})
    @PostMapping("/yield-estimate/recalculate-apply")
    @Operation(summary = "BOM 出成率批量应用 (写)",
            description = "将用户选中的系统出成率更新写入 bom_recipes.overall_yield_rate, 每个配方记录 BomChangeLog. 需 production:read_write.")
    public ApiResponse<BomYieldApplyResultDTO> recalculateApply(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @Valid @RequestBody List<@Valid BomYieldApplyRequest> requests) {
        log.info("[BomYieldEstimate] apply: factoryId={}, requests count={}", factoryId, requests.size());
        BomYieldApplyResultDTO result = bomYieldEstimateService.recalculateApply(factoryId, requests);
        return ApiResponse.success(
                "已更新 " + result.getApplied() + " 条出成率配置", result);
    }

    @GetMapping("/yield-suggestions")
    @Operation(summary = "BOM 出成率自学习建议列表",
            description = "只读展示 PENDING/APPLIED/REJECTED 建议. 自动生成不会覆盖 BOM.")
    public ApiResponse<List<BomYieldSuggestion>> listYieldSuggestions(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(required = false) @Parameter(description = "状态: PENDING/APPLIED/REJECTED; 空返回全部") String status) {
        if (bomYieldSuggestionRepository == null) {
            return ApiResponse.success(Collections.emptyList());
        }
        if (status == null || status.isBlank()) {
            return ApiResponse.success(
                    bomYieldSuggestionRepository.findByFactoryIdAndDeletedAtIsNullOrderByGeneratedAtDesc(factoryId));
        }
        BomYieldSuggestion.Status parsed = BomYieldSuggestion.Status.valueOf(status.trim().toUpperCase());
        return ApiResponse.success(
                bomYieldSuggestionRepository.findByFactoryIdAndStatusAndDeletedAtIsNullOrderByGeneratedAtDesc(
                        factoryId, parsed));
    }


    // ========== Labor Cost request mappers ==========
    /**
     * Map CreateLaborCostRequest → LaborCostConfig entity. Defaults align with
     * @Builder.Default on LaborCostConfig (defaultQuantity=1, isActive=true,
     * sortOrder=0).
     */
    private static LaborCostConfig toLaborCostConfig(CreateLaborCostRequest r) {
        LaborCostConfig c = new LaborCostConfig();
        c.setProductTypeId(r.getProductTypeId());
        c.setProcessName(r.getProcessName());
        c.setProcessCategory(r.getProcessCategory());
        c.setUnitPrice(r.getUnitPrice());
        c.setPriceUnit(r.getPriceUnit());
        c.setDefaultQuantity(r.getDefaultQuantity() != null ? r.getDefaultQuantity() : BigDecimal.ONE);
        c.setIsActive(r.getIsActive() != null ? r.getIsActive() : Boolean.TRUE);
        c.setSortOrder(r.getSortOrder() != null ? r.getSortOrder() : 0);
        c.setRemark(r.getRemark());
        // SP3 Place 3a: set per-kg labor cost (null = not configured, honest gap)
        c.setLaborCostPerKg(r.getLaborCostPerKg());
        return c;
    }

    /** Update variant — same shape as create (PUT is full-replace). */
    private static LaborCostConfig toLaborCostConfig(UpdateLaborCostRequest r) {
        LaborCostConfig c = new LaborCostConfig();
        c.setProductTypeId(r.getProductTypeId());
        c.setProcessName(r.getProcessName());
        c.setProcessCategory(r.getProcessCategory());
        c.setUnitPrice(r.getUnitPrice());
        c.setPriceUnit(r.getPriceUnit());
        c.setDefaultQuantity(r.getDefaultQuantity() != null ? r.getDefaultQuantity() : BigDecimal.ONE);
        c.setIsActive(r.getIsActive() != null ? r.getIsActive() : Boolean.TRUE);
        c.setSortOrder(r.getSortOrder() != null ? r.getSortOrder() : 0);
        c.setRemark(r.getRemark());
        // SP3 Place 3b: update per-kg labor cost; null allowed (null-guard: preserve if absent handled
        // by caller supplying explicit null to clear, not by silently ignoring)
        c.setLaborCostPerKg(r.getLaborCostPerKg());
        return c;
    }
}
