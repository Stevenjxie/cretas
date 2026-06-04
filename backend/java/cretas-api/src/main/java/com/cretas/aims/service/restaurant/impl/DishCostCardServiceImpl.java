package com.cretas.aims.service.restaurant.impl;

import com.cretas.aims.dto.restaurant.DishCostCardResponse;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.restaurant.Recipe;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.restaurant.RecipeRepository;
import com.cretas.aims.service.restaurant.DishCostCardService;
import com.cretas.aims.service.shared.CostRollupUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@link DishCostCardService} 实现.
 *
 * <p>读取菜品 active 配方 → 批量取各食材 {@code unitPrice} → 逐行经
 * {@link CostRollupUtil} 滚动 → 总成本 + 毛利率, 缺价 fail-safe (null, 不误导 ¥0)。
 *
 * @author Cretas Team
 * @since 2026-06-04 (feature #57)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DishCostCardServiceImpl implements DishCostCardService {

    private final RecipeRepository recipeRepository;
    private final ProductTypeRepository productTypeRepository;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;

    @Override
    @Transactional(readOnly = true)
    public DishCostCardResponse getCostCard(String factoryId, String productTypeId, int portions) {
        int safePortions = Math.max(1, portions);

        ProductType dish = productTypeRepository.findByIdAndFactoryId(productTypeId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("菜品", "id", productTypeId));

        List<Recipe> recipes = recipeRepository.findActiveByFactoryIdAndProductTypeId(factoryId, productTypeId);
        if (recipes.isEmpty()) {
            throw new ResourceNotFoundException("配方", "productTypeId", productTypeId);
        }

        // 批量取食材单价 (避免 N+1)
        List<String> materialIds = recipes.stream()
                .map(Recipe::getRawMaterialTypeId)
                .distinct()
                .collect(Collectors.toList());
        Map<String, RawMaterialType> materialMap = new HashMap<>();
        for (RawMaterialType m : rawMaterialTypeRepository.findAllById(materialIds)) {
            materialMap.put(m.getId(), m);
        }

        BigDecimal portionFactor = BigDecimal.valueOf(safePortions);
        List<DishCostCardResponse.IngredientCostLine> lines = new ArrayList<>();
        List<BigDecimal> perLineCosts = new ArrayList<>();
        boolean hasMissingPrices = false;
        LocalDateTime recipeUpdatedAt = null;

        for (Recipe r : recipes) {
            RawMaterialType mat = materialMap.get(r.getRawMaterialTypeId());
            String materialName = mat != null ? mat.getName() : null;
            BigDecimal unitPrice = mat != null ? mat.getUnitPrice() : null;

            // 每份: 标准用量 + 折算实际用量 (净料率已是 fraction)
            BigDecimal baseStdQty = r.getStandardQuantity();
            BigDecimal baseActualQty = CostRollupUtil.calcActualQuantity(baseStdQty, r.getNetYieldRate());
            // 按份数缩放展示量
            BigDecimal stdQty = scaleQty(baseStdQty, portionFactor);
            BigDecimal actualQty = scaleQty(baseActualQty, portionFactor);

            // 单项成本 = 缩放后实际用量 × 单价 (CostRollupUtil 统一舍入 / null-guard)
            BigDecimal itemCost = CostRollupUtil.calcItemCost(actualQty, unitPrice);
            if (itemCost == null) {
                hasMissingPrices = true;
            }
            perLineCosts.add(itemCost);

            lines.add(DishCostCardResponse.IngredientCostLine.builder()
                    .rawMaterialTypeId(r.getRawMaterialTypeId())
                    .materialName(materialName)
                    .standardQty(stdQty)
                    .actualQty(actualQty)
                    .unit(r.getUnit())
                    .netYieldRate(r.getNetYieldRate())
                    .unitPrice(unitPrice)
                    .itemCost(itemCost)
                    .build());

            if (r.getUpdatedAt() != null
                    && (recipeUpdatedAt == null || r.getUpdatedAt().isAfter(recipeUpdatedAt))) {
                recipeUpdatedAt = r.getUpdatedAt();
            }
        }

        // 总成本: 任一缺价 → null (CostRollupUtil guard)
        BigDecimal totalCost = CostRollupUtil.sumItemCosts(perLineCosts);

        // 售价 (按份数缩放); 缺失 → null
        BigDecimal sellPrice = dish.getUnitPrice() != null
                ? dish.getUnitPrice().multiply(portionFactor).setScale(2, RoundingMode.HALF_UP)
                : null;

        // 毛利率 = (售价 - 成本) / 售价; 售价缺失/为 0 或成本缺失 → null
        BigDecimal grossMargin = null;
        if (sellPrice != null && sellPrice.compareTo(BigDecimal.ZERO) != 0 && totalCost != null) {
            grossMargin = sellPrice.subtract(totalCost)
                    .divide(sellPrice, 4, RoundingMode.HALF_UP);
        }

        return DishCostCardResponse.builder()
                .productTypeId(productTypeId)
                .productName(dish.getName())
                .portions(safePortions)
                .totalIngredientCost(totalCost)
                .sellPrice(sellPrice)
                .grossMargin(grossMargin)
                .hasMissingPrices(hasMissingPrices)
                .recipeLineCount(recipes.size())
                .recipeUpdatedAt(recipeUpdatedAt)
                .computedAt(LocalDateTime.now())
                .ingredients(lines)
                .build();
    }

    /** 用量缩放: qty × portions, scale 6 HALF_UP (与 CostRollupUtil QTY_SCALE 一致). null 透传. */
    private static BigDecimal scaleQty(BigDecimal qty, BigDecimal portionFactor) {
        if (qty == null) {
            return null;
        }
        return qty.multiply(portionFactor).setScale(CostRollupUtil.QTY_SCALE, RoundingMode.HALF_UP);
    }
}
