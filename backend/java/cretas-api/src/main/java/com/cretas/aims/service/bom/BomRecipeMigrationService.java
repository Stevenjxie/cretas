package com.cretas.aims.service.bom;

import com.cretas.aims.dto.bom.BomRecipeMigrationReport;
import com.cretas.aims.dto.bom.BomRecipeMigrationReport.SkuResult;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.entity.recipe.ProductRecipe;
import com.cretas.aims.entity.recipe.RecipeIngredient;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.repository.recipe.ProductRecipeRepository;
import com.cretas.aims.repository.recipe.RecipeIngredientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * U3: product_recipes → BOM 一次性迁移 (BOM 统管配方+锅序合并, decision A 真折叠).
 *
 * <p>对每个 ACTIVE {@link ProductRecipe}:
 * <ol>
 *   <li>按 (factory, productTypeId) 找当前 BOM ({@code is_current=TRUE}).</li>
 *   <li>无 BOM → 不自动建 (decision 2), 记 {@code SKIPPED_NO_BOM} 警告.</li>
 *   <li>BOM 已有调料 ({@code bom_seasoning_items} 非空) → 幂等跳过 {@code SKIPPED_ALREADY}.</li>
 *   <li>否则: 锅序参数 (cooking_pot_base_kg / subsequent_pot_ratio / injection_rate) 写进 BOM;
 *       {@code recipe_ingredients} 逐行 1:1 → {@code bom_seasoning_items}.</li>
 * </ol>
 *
 * <p>{@code dryRun=true}: 计算报告但不持久化 (只读对比, spec U3 Step 3).
 * <p>{@code product_recipes} 永不删除 (只读回滚保险, U8 cleanup 才删).
 *
 * @since 2026-06-24
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BomRecipeMigrationService {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final ProductRecipeRepository productRecipeRepo;
    private final RecipeIngredientRepository ingredientRepo;
    private final BomRecipeRepository bomRecipeRepo;
    private final BomSeasoningItemRepository bomSeasoningItemRepo;

    /**
     * 迁移指定工厂的 product_recipes 进 BOM.
     *
     * @param factoryId 工厂
     * @param dryRun    true = 只读对比不写; false = 实际迁移
     * @return 详细报告 (per-SKU action + 警告)
     */
    @Transactional
    public BomRecipeMigrationReport migrate(String factoryId, boolean dryRun) {
        List<ProductRecipe> all = productRecipeRepo.findByFactoryId(factoryId);
        List<ProductRecipe> active = all.stream()
                .filter(r -> STATUS_ACTIVE.equals(r.getStatus()))
                .toList();

        List<SkuResult> results = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int migrated = 0, skippedAlready = 0, skippedNoBom = 0;

        for (ProductRecipe pr : active) {
            Optional<BomRecipe> bomOpt = bomRecipeRepo
                    .findByFactoryIdAndProductTypeIdAndIsCurrentTrue(factoryId, pr.getProductTypeId());

            if (bomOpt.isEmpty()) {
                skippedNoBom++;
                warnings.add("SKU " + pr.getProductTypeId() + " (" + pr.getName()
                        + ") 有配方但无现有 BOM — 跳过, 需先建 BOM (decision 2: 不自动建空 BOM)");
                results.add(SkuResult.builder()
                        .productTypeId(pr.getProductTypeId())
                        .productName(pr.getName())
                        .productRecipeId(pr.getId())
                        .bomRecipeId(null)
                        .action(BomRecipeMigrationReport.SKIPPED_NO_BOM)
                        .seasoningItemCount(0)
                        .build());
                continue;
            }

            BomRecipe bom = bomOpt.get();

            if (bomSeasoningItemRepo.existsByRecipeId(bom.getId())) {
                skippedAlready++;
                results.add(SkuResult.builder()
                        .productTypeId(pr.getProductTypeId())
                        .productName(pr.getName())
                        .productRecipeId(pr.getId())
                        .bomRecipeId(bom.getId())
                        .action(BomRecipeMigrationReport.SKIPPED_ALREADY)
                        .seasoningItemCount(0)
                        .build());
                continue;
            }

            List<RecipeIngredient> ingredients = ingredientRepo.findByRecipeIdOrderBySeqAsc(pr.getId());

            // 1) 锅序参数写进 BOM (1:1).
            bom.setCookingPotBaseKg(pr.getCookingPotBaseKg());
            bom.setSubsequentPotRatio(pr.getSubsequentPotRatio());
            bom.setInjectionRate(pr.getInjectionRate());

            // 2) recipe_ingredients → bom_seasoning_items (1:1 字段拷贝 → U4 零回归).
            List<BomSeasoningItem> seasoning = new ArrayList<>();
            for (RecipeIngredient ing : ingredients) {
                seasoning.add(BomSeasoningItem.builder()
                        .recipeId(bom.getId())
                        .factoryId(factoryId)
                        .section(ing.getSection())
                        .seq(ing.getSeq())
                        .name(ing.getName())
                        .dosagePerKgG(ing.getDosagePerKgG())
                        .priceSource1(ing.getPriceSource1())
                        .priceSource2(ing.getPriceSource2())
                        .countInSeasoning(ing.getCountInSeasoning())
                        .remark(ing.getRemark())
                        .build());
            }

            if (!dryRun) {
                bomRecipeRepo.save(bom);
                bomSeasoningItemRepo.saveAll(seasoning);
            }

            migrated++;
            results.add(SkuResult.builder()
                    .productTypeId(pr.getProductTypeId())
                    .productName(pr.getName())
                    .productRecipeId(pr.getId())
                    .bomRecipeId(bom.getId())
                    .action(BomRecipeMigrationReport.MIGRATED)
                    .seasoningItemCount(seasoning.size())
                    .cookingPotBaseKg(pr.getCookingPotBaseKg())
                    .subsequentPotRatio(pr.getSubsequentPotRatio())
                    .injectionRate(pr.getInjectionRate())
                    .build());
        }

        log.info("[BOM-MIGRATE] factory={} dryRun={} processed={} migrated={} skippedAlready={} skippedNoBom={}",
                factoryId, dryRun, active.size(), migrated, skippedAlready, skippedNoBom);

        return BomRecipeMigrationReport.builder()
                .dryRun(dryRun)
                .factoryId(factoryId)
                .skusProcessed(active.size())
                .migrated(migrated)
                .skippedAlready(skippedAlready)
                .skippedNoBom(skippedNoBom)
                .results(results)
                .warnings(warnings)
                .build();
    }
}
