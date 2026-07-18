package com.cretas.aims.service.impl;

import com.cretas.aims.dto.bom.BomCostSummaryDTO;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.LaborCostConfigRepository;
import com.cretas.aims.repository.bom.OverheadCostConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@DisplayName("B8 BOM cost pre-tax caliber")
@ExtendWith(MockitoExtension.class)
class BomServiceImplPreTaxCaliberTest {

    @Mock
    private BomRecipeItemRepository bomItemRepository;
    @Mock
    private LaborCostConfigRepository laborCostConfigRepository;
    @Mock
    private OverheadCostConfigRepository overheadCostConfigRepository;

    private BomServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BomServiceImpl(bomItemRepository, laborCostConfigRepository, overheadCostConfigRepository);
    }

    @Test
    @DisplayName("B8: cost summary marks material prices as pre-tax and does not divide an already pre-tax BOM price again")
    void calculateProductCost_marksPreTaxCaliberWithoutDoubleConverting() {
        BomRecipeItem beef = BomRecipeItem.builder()
                .factoryId("F006")
                .recipeId("RECIPE-P-B8")
                .materialTypeId("RM-BEEF")
                .materialName("beef shank")
                .standardQuantity(new BigDecimal("1.0000"))
                .yieldRate(new BigDecimal("100.00"))
                .unit("jin")
                .unitPrice(new BigDecimal("57.5221"))
                .taxRate(new BigDecimal("13.00"))
                .build();
        attachRecipe(beef, "P-B8", "B8 cost product");

        when(bomItemRepository.findCurrentByProduct("F006", "P-B8"))
                .thenReturn(List.of(beef));
        when(laborCostConfigRepository.findByFactoryIdAndProductTypeIdAndIsActiveTrueAndDeletedAtIsNullOrderBySortOrderAsc("F006", "P-B8"))
                .thenReturn(List.of());
        when(laborCostConfigRepository.findByFactoryIdAndProductTypeIdIsNullAndDeletedAtIsNullOrderBySortOrderAsc("F006"))
                .thenReturn(List.of());
        when(overheadCostConfigRepository.findByFactoryIdAndIsActiveTrueAndDeletedAtIsNullOrderBySortOrderAsc("F006"))
                .thenReturn(List.of());

        BomCostSummaryDTO result = service.calculateProductCost("F006", "P-B8");

        assertThat(result.getMaterialCostTotal()).isEqualByComparingTo("57.5221");
        assertThat(result.getCostCaliber()).isEqualTo("PRE_TAX");
        assertThat(result.getCaliberHint()).contains("未税");
        assertThat(result.getMaterialCosts()).singleElement().satisfies(item -> {
            assertThat(item.getUnitPrice()).isEqualByComparingTo("57.5221");
            assertThat(item.getSubtotal()).isEqualByComparingTo("57.5221");
            assertThat(item.getUnitPriceCaliber()).isEqualTo("PRE_TAX");
            assertThat(item.getCaliberHint()).contains("BOM unitPrice 为未税价");
        });
    }

    @Test
    @DisplayName("B8: missing tax rate remains null and cost summary honestly marks the gap")
    void calculateProductCost_missingTaxRateStaysNullAndMarkedInCaliberHint() {
        BomRecipeItem material = BomRecipeItem.builder()
                .factoryId("F006")
                .recipeId("RECIPE-P-B8-MISSING-TAX")
                .materialTypeId("RM-MISSING-TAX")
                .materialName("material without tax rate")
                .standardQuantity(new BigDecimal("1.0000"))
                .yieldRate(new BigDecimal("100.00"))
                .unit("kg")
                .unitPrice(new BigDecimal("57.5221"))
                .taxRate(null)
                .build();
        attachRecipe(material, "P-B8-MISSING-TAX", "B8 missing tax");

        when(bomItemRepository.findCurrentByProduct(
                "F006", "P-B8-MISSING-TAX"))
                .thenReturn(List.of(material));
        when(laborCostConfigRepository.findByFactoryIdAndProductTypeIdAndIsActiveTrueAndDeletedAtIsNullOrderBySortOrderAsc(
                "F006", "P-B8-MISSING-TAX"))
                .thenReturn(List.of());
        when(laborCostConfigRepository.findByFactoryIdAndProductTypeIdIsNullAndDeletedAtIsNullOrderBySortOrderAsc("F006"))
                .thenReturn(List.of());
        when(overheadCostConfigRepository.findByFactoryIdAndIsActiveTrueAndDeletedAtIsNullOrderBySortOrderAsc("F006"))
                .thenReturn(List.of());

        BomCostSummaryDTO result = service.calculateProductCost("F006", "P-B8-MISSING-TAX");

        assertThat(result.getMaterialCosts()).singleElement().satisfies(item -> {
            assertThat(item.getTaxRate()).isNull();
            assertThat(item.getCaliberHint()).contains("缺税率");
        });
        assertThat(result.getCaliberHint()).contains("缺税率");
    }

    @Test
    @DisplayName("B-BUG-1: 缺单价的原料行不静默当 ¥0, 而是显式标记 hasMissingPrice + 缺价列表 + caliberHint 警示")
    void calculateProductCost_missingPriceSurfacedNotSilentlyZero() {
        // 一行有价 (¥10 * 1 = ¥10), 一行缺价 (unitPrice=null → 之前静默当 ¥0 并入合计)。
        BomRecipeItem priced = BomRecipeItem.builder()
                .factoryId("F006")
                .recipeId("RECIPE-P-MISSING-PRICE")
                .materialTypeId("RM-PRICED")
                .materialName("有价原料")
                .standardQuantity(new BigDecimal("1.0000"))
                .yieldRate(new BigDecimal("100.00"))
                .unit("kg")
                .unitPrice(new BigDecimal("10.0000"))
                .taxRate(new BigDecimal("13.00"))
                .build();
        attachRecipe(priced, "P-MISSING-PRICE", "missing price product");
        BomRecipeItem noPrice = BomRecipeItem.builder()
                .factoryId("F006")
                .recipeId("RECIPE-P-MISSING-PRICE")
                .materialTypeId("RM-NOPRICE")
                .materialName("缺价原料")
                .standardQuantity(new BigDecimal("2.0000"))
                .yieldRate(new BigDecimal("100.00"))
                .unit("kg")
                .unitPrice(null)
                .taxRate(new BigDecimal("13.00"))
                .build();
        attachRecipe(noPrice, "P-MISSING-PRICE", "missing price product");

        when(bomItemRepository.findCurrentByProduct(
                "F006", "P-MISSING-PRICE"))
                .thenReturn(List.of(priced, noPrice));
        when(laborCostConfigRepository.findByFactoryIdAndProductTypeIdAndIsActiveTrueAndDeletedAtIsNullOrderBySortOrderAsc(
                "F006", "P-MISSING-PRICE"))
                .thenReturn(List.of());
        when(laborCostConfigRepository.findByFactoryIdAndProductTypeIdIsNullAndDeletedAtIsNullOrderBySortOrderAsc("F006"))
                .thenReturn(List.of());
        when(overheadCostConfigRepository.findByFactoryIdAndIsActiveTrueAndDeletedAtIsNullOrderBySortOrderAsc("F006"))
                .thenReturn(List.of());

        BomCostSummaryDTO result = service.calculateProductCost("F006", "P-MISSING-PRICE");

        // 总成本仍只含已知价格行 (¥10), 但 incompleteness 被显式 surface (禁止降级)。
        assertThat(result.isHasMissingPrice()).isTrue();
        assertThat(result.getMissingPriceCount()).isEqualTo(1);
        assertThat(result.getMissingPriceMaterials()).containsExactly("缺价原料");
        assertThat(result.getCaliberHint()).contains("成本不完整");
        assertThat(result.getMaterialCostTotal()).isEqualByComparingTo("10.0000");

        // 每行 missingPrice 标记正确
        assertThat(result.getMaterialCosts())
                .filteredOn(item -> "缺价原料".equals(item.getMaterialName()))
                .singleElement()
                .satisfies(item -> assertThat(item.isMissingPrice()).isTrue());
        assertThat(result.getMaterialCosts())
                .filteredOn(item -> "有价原料".equals(item.getMaterialName()))
                .singleElement()
                .satisfies(item -> assertThat(item.isMissingPrice()).isFalse());
    }

    private void attachRecipe(BomRecipeItem item, String productTypeId, String productName) {
        BomRecipe recipe = new BomRecipe();
        recipe.setId(item.getRecipeId());
        recipe.setFactoryId(item.getFactoryId());
        recipe.setProductTypeId(productTypeId);
        recipe.setProductName(productName);
        recipe.setStatus(BomRecipe.Status.ACTIVE);
        recipe.setIsCurrent(true);
        item.setRecipe(recipe);
    }
}
