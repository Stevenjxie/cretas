package com.cretas.aims.controller.restaurant;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.entity.restaurant.Recipe;
import com.cretas.aims.event.RecipeSavedEvent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.restaurant.RecipeRepository;
import org.springframework.context.ApplicationEventPublisher;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;
import com.cretas.aims.annotation.RequireModule;

/**
 * BOM 配方管理 Controller
 *
 * @author Cretas Team
 * @since 2026-02-20
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/restaurant/recipes")
@RequiredArgsConstructor
@Tag(name = "餐饮-配方管理")
public class RecipeController {

    private final RecipeRepository recipeRepository;
    private final ApplicationEventPublisher eventPublisher;

    // ==================== 列表查询 ====================

    @GetMapping
    @Operation(summary = "配方列表", description = "支持按菜品和启用状态筛选")
    public ApiResponse<Page<Recipe>> list(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(required = false) String productTypeId,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(Math.max(0, page - 1), size);
        Page<Recipe> result;
        if (productTypeId != null) {
            result = recipeRepository.findByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(factoryId, productTypeId, pageable);
        } else if (isActive != null) {
            result = recipeRepository.findByFactoryIdAndIsActiveOrderByCreatedAtDesc(factoryId, isActive, pageable);
        } else {
            result = recipeRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId, pageable);
        }
        return ApiResponse.success(result);
    }

    // ==================== 详情 ====================

    @GetMapping("/{recipeId}")
    @Operation(summary = "配方详情")
    public ApiResponse<Recipe> detail(
            @PathVariable String factoryId,
            @PathVariable String recipeId) {
        Recipe recipe = recipeRepository.findByIdAndFactoryId(recipeId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("配方", "id", recipeId));
        return ApiResponse.success(recipe);
    }

    // ==================== 创建 ====================

    @RequirePermission({"production:read_write", "rd:read_write"})
    @RequireModule("restaurant")
    @PostMapping
    @Operation(summary = "创建配方")
    public ApiResponse<Recipe> create(
            @PathVariable String factoryId,
            @RequestAttribute("userId") @Parameter(hidden = true) Long userId,
            @RequestBody @Valid Recipe recipe) {
        log.info("创建配方: factoryId={}, productTypeId={}, materialId={}",
                factoryId, recipe.getProductTypeId(), recipe.getRawMaterialTypeId());

        // 检查重复
        if (recipeRepository.existsByFactoryIdAndProductTypeIdAndRawMaterialTypeIdAndIsActiveTrue(
                factoryId, recipe.getProductTypeId(), recipe.getRawMaterialTypeId())) {
            throw new BusinessException(409, "该菜品已存在此食材的配方")
                    .withHint("请前往配方管理停用现有配方或修改其用量");
        }

        recipe.setId(null); // 由 @PrePersist 生成
        recipe.setFactoryId(factoryId);
        recipe.setCreatedBy(userId);
        if (recipe.getIsActive() == null) {
            recipe.setIsActive(true);
        }
        Recipe saved = recipeRepository.save(recipe);
        // #57: 配方变更 → 异步触发该菜品成本缓存重算 (REQUIRES_NEW 隔离, fail-soft)
        eventPublisher.publishEvent(new RecipeSavedEvent(this, factoryId, saved.getProductTypeId(), saved.getId()));
        return ApiResponse.success("配方创建成功", saved);
    }

    // ==================== 更新 ====================

    @RequirePermission({"production:read_write", "rd:read_write"})
    @RequireModule("restaurant")
    @PutMapping("/{recipeId}")
    @Operation(summary = "更新配方")
    public ApiResponse<Recipe> update(
            @PathVariable String factoryId,
            @PathVariable String recipeId,
            @RequestBody @Valid Recipe recipe) {
        Recipe existing = recipeRepository.findByIdAndFactoryId(recipeId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("配方", "id", recipeId));
        existing.setStandardQuantity(recipe.getStandardQuantity());
        existing.setUnit(recipe.getUnit());
        existing.setNetYieldRate(recipe.getNetYieldRate());
        existing.setIsMainIngredient(recipe.getIsMainIngredient());
        existing.setNotes(recipe.getNotes());
        existing.setIsActive(recipe.getIsActive());
        Recipe updated = recipeRepository.save(existing);
        // #57: 配方变更 → 异步触发该菜品成本缓存重算 (REQUIRES_NEW 隔离, fail-soft)
        eventPublisher.publishEvent(new RecipeSavedEvent(this, factoryId, updated.getProductTypeId(), updated.getId()));
        return ApiResponse.success("配方更新成功", updated);
    }

    // ==================== 软删除 ====================

    @RequirePermission({"production:read_write", "rd:read_write"})
    @RequireModule("restaurant")
    @DeleteMapping("/{recipeId}")
    @Operation(summary = "停用配方（软删除）")
    public ApiResponse<Void> softDelete(
            @PathVariable String factoryId,
            @PathVariable String recipeId) {
        Recipe recipe = recipeRepository.findByIdAndFactoryId(recipeId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("配方", "id", recipeId));
        recipe.setIsActive(false);
        recipeRepository.save(recipe);
        return ApiResponse.successMessage("配方已停用");
    }

    // ==================== 菜品配方 ====================

    @GetMapping("/by-dish/{productTypeId}")
    @Operation(summary = "查询菜品配方", description = "获取某菜品的完整 BOM 配方列表")
    public ApiResponse<List<Recipe>> getByDish(
            @PathVariable String factoryId,
            @PathVariable String productTypeId) {
        List<Recipe> recipes = recipeRepository.findActiveByFactoryIdAndProductTypeId(factoryId, productTypeId);
        return ApiResponse.success(recipes);
    }

    @GetMapping("/by-dish/{productTypeId}/calculate")
    @Operation(summary = "计算配料用量", description = "根据制作份数计算所需食材用量")
    public ApiResponse<List<Map<String, Object>>> calculateIngredients(
            @PathVariable String factoryId,
            @PathVariable String productTypeId,
            @RequestParam(defaultValue = "1") int quantity) {
        List<Recipe> recipes = recipeRepository.findActiveByFactoryIdAndProductTypeId(factoryId, productTypeId);
        if (recipes.isEmpty()) {
            throw new ResourceNotFoundException("配方", "productTypeId", productTypeId);
        }
        BigDecimal qty = BigDecimal.valueOf(quantity);
        List<Map<String, Object>> items = recipes.stream().map(r -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("recipeId", r.getId());
            item.put("rawMaterialTypeId", r.getRawMaterialTypeId());
            item.put("unit", r.getUnit());
            item.put("isMainIngredient", r.getIsMainIngredient());
            item.put("standardQuantityPerServing", r.getStandardQuantity());
            item.put("totalStandardQuantity", r.getStandardQuantity().multiply(qty).setScale(4, RoundingMode.HALF_UP));
            item.put("totalActualQuantity", r.getActualQuantity().multiply(qty).setScale(4, RoundingMode.HALF_UP));
            item.put("netYieldRate", r.getNetYieldRate());
            return item;
        }).collect(Collectors.toList());
        return ApiResponse.success(items);
    }

    // ==================== 统计 ====================

    @GetMapping("/summary")
    @Operation(summary = "配方统计", description = "按菜品分组统计配方条目数")
    public ApiResponse<Map<String, Object>> summary(@PathVariable String factoryId) {
        List<Object[]> stats = recipeRepository.countActiveRecipesByProduct(factoryId);
        long totalProducts = stats.size();
        long totalRecipeLines = stats.stream().mapToLong(row -> (Long) row[1]).sum();

        List<Map<String, Object>> byProduct = stats.stream().map(row -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productTypeId", row[0]);
            m.put("ingredientCount", row[1]);
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalProducts", totalProducts);
        result.put("totalRecipeLines", totalRecipeLines);
        result.put("byProduct", byProduct);
        return ApiResponse.success(result);
    }
}
