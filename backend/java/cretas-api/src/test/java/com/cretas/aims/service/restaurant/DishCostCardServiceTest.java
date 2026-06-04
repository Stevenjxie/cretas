package com.cretas.aims.service.restaurant;

import com.cretas.aims.dto.restaurant.DishCostCardResponse;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.restaurant.Recipe;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.restaurant.RecipeRepository;
import com.cretas.aims.service.restaurant.impl.DishCostCardServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DishCostCardServiceImpl}.
 *
 * <p>Verifies the cost rollup (via {@link com.cretas.aims.service.shared.CostRollupUtil}),
 * portion scaling, gross-margin formula, the missing-price guard (any unpriced
 * ingredient → totalIngredientCost null + hasMissingPrices true), and the
 * no-recipe 404.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DishCostCardServiceImpl 单元测试")
class DishCostCardServiceTest {

    @Mock RecipeRepository recipeRepository;
    @Mock ProductTypeRepository productTypeRepository;
    @Mock RawMaterialTypeRepository rawMaterialTypeRepository;
    @InjectMocks DishCostCardServiceImpl service;

    private static final String FID = "RES_3101_009";
    private static final String DISH = "dish-1";

    private static ProductType dish(String price) {
        ProductType p = new ProductType();
        p.setId(DISH);
        p.setFactoryId(FID);
        p.setName("白卤猪舌");
        p.setUnit("份");
        p.setUnitPrice(price == null ? null : new BigDecimal(price));
        return p;
    }

    private static Recipe recipe(String matId, String stdQty, String yieldFraction) {
        Recipe r = new Recipe();
        r.setId("rec-" + matId);
        r.setFactoryId(FID);
        r.setProductTypeId(DISH);
        r.setRawMaterialTypeId(matId);
        r.setStandardQuantity(new BigDecimal(stdQty));
        r.setNetYieldRate(yieldFraction == null ? null : new BigDecimal(yieldFraction));
        r.setUnit("kg");
        r.setIsActive(true);
        return r;
    }

    private static RawMaterialType material(String id, String name, String unitPrice) {
        RawMaterialType m = new RawMaterialType();
        m.setId(id);
        m.setFactoryId(FID);
        m.setName(name);
        m.setUnit("kg");
        m.setUnitPrice(unitPrice == null ? null : new BigDecimal(unitPrice));
        return m;
    }

    @Test
    @DisplayName("happy path: per-line cost, total, gross margin, 1 portion")
    void costCard_happyPath() {
        when(productTypeRepository.findByIdAndFactoryId(DISH, FID)).thenReturn(Optional.of(dish("38.00")));
        when(recipeRepository.findActiveByFactoryIdAndProductTypeId(FID, DISH)).thenReturn(List.of(
                recipe("m1", "0.45", "0.58"),   // actual = 0.45/0.58 = 0.775862; × 15.90 = 12.3362...
                recipe("m2", "0.10", "1.00")    // actual = 0.10; × 6.00 = 0.6000
        ));
        when(rawMaterialTypeRepository.findAllById(anyIterable())).thenReturn(List.of(
                material("m1", "猪舌", "15.90"),
                material("m2", "盐", "6.00")
        ));

        DishCostCardResponse card = service.getCostCard(FID, DISH, 1);

        assertEquals("白卤猪舌", card.getProductName());
        assertEquals(1, card.getPortions());
        assertEquals(2, card.getIngredients().size());
        assertFalse(card.getHasMissingPrices());
        // line m1: actualQty 0.775862, itemCost 0.775862×15.90 = 12.3362 (scale 4 HALF_UP)
        DishCostCardResponse.IngredientCostLine l1 = card.getIngredients().stream()
                .filter(l -> l.getRawMaterialTypeId().equals("m1")).findFirst().orElseThrow();
        assertEquals(new BigDecimal("0.775862"), l1.getActualQty());
        assertEquals(new BigDecimal("12.3362"), l1.getItemCost());
        // total = 12.3362 + 0.6000 = 12.9362
        assertEquals(new BigDecimal("12.9362"), card.getTotalIngredientCost());
        assertEquals(new BigDecimal("38.00"), card.getSellPrice());
        // gross margin = (38 - 12.9362)/38 = 0.6596... (scale 4 HALF_UP) = 0.6596
        assertEquals(new BigDecimal("0.6596"), card.getGrossMargin());
    }

    @Test
    @DisplayName("portion scaling: 3 portions triples cost and sell price")
    void costCard_portionScaling() {
        when(productTypeRepository.findByIdAndFactoryId(DISH, FID)).thenReturn(Optional.of(dish("38.00")));
        when(recipeRepository.findActiveByFactoryIdAndProductTypeId(FID, DISH)).thenReturn(List.of(
                recipe("m2", "0.10", "1.00")    // actual 0.10 × 6 = 0.60 per portion
        ));
        when(rawMaterialTypeRepository.findAllById(anyIterable())).thenReturn(List.of(
                material("m2", "盐", "6.00")
        ));

        DishCostCardResponse card = service.getCostCard(FID, DISH, 3);

        assertEquals(3, card.getPortions());
        // total per-portion 0.60 × 3 = 1.80
        assertEquals(new BigDecimal("1.8000"), card.getTotalIngredientCost());
        // sell 38 × 3 = 114.00
        assertEquals(new BigDecimal("114.00"), card.getSellPrice());
    }

    @Test
    @DisplayName("missing price: any unpriced ingredient → total null + hasMissingPrices true + margin null")
    void costCard_missingPrice() {
        when(productTypeRepository.findByIdAndFactoryId(DISH, FID)).thenReturn(Optional.of(dish("38.00")));
        when(recipeRepository.findActiveByFactoryIdAndProductTypeId(FID, DISH)).thenReturn(List.of(
                recipe("m1", "0.45", "0.58"),
                recipe("m2", "0.10", "1.00")
        ));
        when(rawMaterialTypeRepository.findAllById(anyIterable())).thenReturn(List.of(
                material("m1", "猪舌", "15.90"),
                material("m2", "盐", null)   // no price
        ));

        DishCostCardResponse card = service.getCostCard(FID, DISH, 1);

        assertTrue(card.getHasMissingPrices());
        assertNull(card.getTotalIngredientCost());
        assertNull(card.getGrossMargin());
        // the priced line still shows its own itemCost
        DishCostCardResponse.IngredientCostLine l1 = card.getIngredients().stream()
                .filter(l -> l.getRawMaterialTypeId().equals("m1")).findFirst().orElseThrow();
        assertNotNull(l1.getItemCost());
        DishCostCardResponse.IngredientCostLine l2 = card.getIngredients().stream()
                .filter(l -> l.getRawMaterialTypeId().equals("m2")).findFirst().orElseThrow();
        assertNull(l2.getItemCost());
    }

    @Test
    @DisplayName("zero sell price → gross margin null (no div-by-zero)")
    void costCard_zeroSellPrice() {
        when(productTypeRepository.findByIdAndFactoryId(DISH, FID)).thenReturn(Optional.of(dish("0.00")));
        when(recipeRepository.findActiveByFactoryIdAndProductTypeId(FID, DISH)).thenReturn(List.of(
                recipe("m2", "0.10", "1.00")
        ));
        when(rawMaterialTypeRepository.findAllById(anyIterable())).thenReturn(List.of(
                material("m2", "盐", "6.00")
        ));

        DishCostCardResponse card = service.getCostCard(FID, DISH, 1);

        assertNotNull(card.getTotalIngredientCost());
        assertNull(card.getGrossMargin());
    }

    @Test
    @DisplayName("no active recipe → ResourceNotFoundException (防呆 Rule 5)")
    void costCard_noRecipe() {
        when(productTypeRepository.findByIdAndFactoryId(DISH, FID)).thenReturn(Optional.of(dish("38.00")));
        when(recipeRepository.findActiveByFactoryIdAndProductTypeId(FID, DISH)).thenReturn(List.of());

        assertThrows(ResourceNotFoundException.class, () -> service.getCostCard(FID, DISH, 1));
    }

    @Test
    @DisplayName("dish not found → ResourceNotFoundException")
    void costCard_dishNotFound() {
        when(productTypeRepository.findByIdAndFactoryId(DISH, FID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getCostCard(FID, DISH, 1));
    }
}
