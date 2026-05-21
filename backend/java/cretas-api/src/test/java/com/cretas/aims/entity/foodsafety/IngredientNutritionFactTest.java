package com.cretas.aims.entity.foodsafety;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IngredientNutritionFact entity unit tests — Sprint 9 P2.B.
 */
class IngredientNutritionFactTest {

    @Test
    void builderSetsRequiredFields() {
        IngredientNutritionFact f = IngredientNutritionFact.builder()
                .ingredientName("猪蹄")
                .ingredientCode("08-1-001")
                .per100gEnergy(new BigDecimal("1004.20"))
                .per100gProtein(new BigDecimal("22.60"))
                .per100gFat(new BigDecimal("18.80"))
                .per100gCarbohydrate(new BigDecimal("0.00"))
                .per100gSodium(new BigDecimal("80.00"))
                .per100gSaturatedFat(new BigDecimal("6.80"))
                .sourceRef("中国食物成分表 第6版")
                .build();
        assertNull(f.getId());
        assertEquals("猪蹄", f.getIngredientName());
        assertEquals("08-1-001", f.getIngredientCode());
        assertEquals(new BigDecimal("1004.20"), f.getPer100gEnergy());
        assertEquals(new BigDecimal("22.60"), f.getPer100gProtein());
        assertEquals(new BigDecimal("18.80"), f.getPer100gFat());
        assertEquals(new BigDecimal("0.00"), f.getPer100gCarbohydrate());
        assertEquals(new BigDecimal("80.00"), f.getPer100gSodium());
        assertEquals(new BigDecimal("6.80"), f.getPer100gSaturatedFat());
        assertEquals("中国食物成分表 第6版", f.getSourceRef());
        assertTrue(f.isActive()); // default true
    }

    @Test
    void optionalRecommendedFieldsCanBeNull() {
        IngredientNutritionFact f = IngredientNutritionFact.builder()
                .ingredientName("盐")
                .ingredientCode("24-2-001")
                .per100gEnergy(BigDecimal.ZERO)
                .per100gProtein(BigDecimal.ZERO)
                .per100gFat(BigDecimal.ZERO)
                .per100gCarbohydrate(BigDecimal.ZERO)
                .per100gSodium(new BigDecimal("38758.00"))
                .build();
        // 推荐字段允许 null
        assertNull(f.getPer100gSaturatedFat());
        assertNull(f.getPer100gTransFat());
        assertNull(f.getPer100gSugar());
        assertNull(f.getPer100gDietaryFiber());
    }

    @Test
    void inactiveFlagForObsoletedEntry() {
        IngredientNutritionFact f = IngredientNutritionFact.builder()
                .ingredientName("旧版数据")
                .ingredientCode("XX-9-999")
                .per100gEnergy(BigDecimal.ZERO)
                .per100gProtein(BigDecimal.ZERO)
                .per100gFat(BigDecimal.ZERO)
                .per100gCarbohydrate(BigDecimal.ZERO)
                .per100gSodium(BigDecimal.ZERO)
                .active(false)
                .build();
        assertFalse(f.isActive());
    }

    @Test
    void softDeleteSetsDeletedAt() {
        IngredientNutritionFact f = IngredientNutritionFact.builder()
                .ingredientName("八角")
                .ingredientCode("21-1-001")
                .per100gEnergy(new BigDecimal("1424.70"))
                .per100gProtein(new BigDecimal("3.80"))
                .per100gFat(new BigDecimal("8.00"))
                .per100gCarbohydrate(new BigDecimal("35.40"))
                .per100gSodium(new BigDecimal("152.00"))
                .build();
        assertNull(f.getDeletedAt());
        f.softDelete();
        assertNotNull(f.getDeletedAt());
        assertTrue(f.isDeleted());
    }
}
