package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.dto.bom.BomItemUsage;
import com.cretas.aims.dto.bom.MaterialReplacementImpact;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.service.bom.BomReverseQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * BOM 反查 REST Controller (Sprint 6 W4-C / M-BOM-VER-1).
 *
 * <p>路径前缀: {@code /api/mobile/{factoryId}/bom/reverse-query}.
 *
 * <p>物料 → BOM 反查: 哪些 BOM 用了某物料? 替换 / 停产时影响哪些产品?
 *
 * <p>RBAC: Read 方法不加 @RequirePermission, 但成本字段经
 * {@code @PriceSensitive + PriceFieldResponseAdvice} 自动 strip 给无 procurement:price:view 权限的角色.
 *
 * <p>Perf target: 1000 BomRecipe ≤200ms (per BomReverseQueryService.findRecipesByMaterial DoD).
 *
 * @author Cretas Team / Sprint 6 W4-C
 * @since 2026-05-20
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/bom/reverse-query")
@RequiredArgsConstructor
@Tag(name = "BOM 反查 (M-BOM-VER-1)",
        description = "物料 → BOM 反查 + 替换影响分析 (cost-optimization ECN 评估)")
@RequireModule("bom")
public class BomReverseQueryController {

    private final BomReverseQueryService reverseQueryService;

    @GetMapping("/by-material/{materialId}")
    @Operation(summary = "Sprint 6 W4-C — 哪些 BOM 用了这个物料? (distinct BomRecipes)",
            description = "反查 page: 某物料挂哪些 BomRecipe. 走 idx_bri_material 索引 ≤200ms.")
    public ApiResponse<List<BomRecipe>> findRecipesByMaterial(
            @PathVariable String factoryId,
            @PathVariable String materialId) {
        return ApiResponse.success(reverseQueryService.findRecipesByMaterial(factoryId, materialId));
    }

    @GetMapping("/by-material/{materialId}/usage")
    @Operation(summary = "Sprint 6 W4-C — 物料挂哪些 BomRecipe 含明细 (用量/出成率/成本/per item row)",
            description = "同 recipe 多 item 引用同 material 时多次出现 (不同 substituteGroup).")
    public ApiResponse<List<BomItemUsage>> findUsageByMaterial(
            @PathVariable String factoryId,
            @PathVariable String materialId) {
        return ApiResponse.success(reverseQueryService.findUsageByMaterial(factoryId, materialId));
    }

    @GetMapping("/replace-impact")
    @Operation(summary = "Sprint 6 W4-C — 替换影响分析 (read-only)",
            description = "oldMaterialId → newMaterialId 跨所有 BomRecipe 的 cost delta 分析. 用于 ECN 创建前评估.")
    public ApiResponse<MaterialReplacementImpact> analyzeReplacement(
            @PathVariable String factoryId,
            @Parameter(description = "旧物料 ID")
            @RequestParam String oldMaterialId,
            @Parameter(description = "新物料 ID")
            @RequestParam String newMaterialId) {
        return ApiResponse.success(reverseQueryService.analyzeReplacement(factoryId, oldMaterialId, newMaterialId));
    }
}
