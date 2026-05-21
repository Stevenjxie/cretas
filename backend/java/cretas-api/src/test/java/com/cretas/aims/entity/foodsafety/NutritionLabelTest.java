package com.cretas.aims.entity.foodsafety;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NutritionLabel entity unit tests — Sprint 9 P2.B.
 */
class NutritionLabelTest {

    @Test
    void builderSetsAllMandatoryFields() {
        NutritionLabel l = NutritionLabel.builder()
                .factoryId("F006")
                .productCode("QHJ-LZT-200G")
                .productName("叮咚好食光卤猪蹄 200g")
                .bomVersionId("bom-uuid-1234")
                .servingSize(new BigDecimal("200.00"))
                .energyPerServing(new BigDecimal("1856.50"))
                .proteinPerServing(new BigDecimal("44.20"))
                .fatPerServing(new BigDecimal("37.60"))
                .carbohydratePerServing(new BigDecimal("8.40"))
                .sodiumPerServing(new BigDecimal("520.00"))
                .generatedByUserId(22L)
                .build();
        assertNull(l.getId());
        assertEquals("F006", l.getFactoryId());
        assertEquals("QHJ-LZT-200G", l.getProductCode());
        assertEquals("叮咚好食光卤猪蹄 200g", l.getProductName());
        assertEquals("bom-uuid-1234", l.getBomVersionId());
        assertEquals(new BigDecimal("200.00"), l.getServingSize());
        assertEquals(new BigDecimal("1856.50"), l.getEnergyPerServing());
        assertEquals("DRAFT", l.getStatus()); // default
        assertEquals(22L, l.getGeneratedByUserId());
    }

    @Test
    void recommendedFieldsCanBeNull() {
        NutritionLabel l = NutritionLabel.builder()
                .factoryId("F006")
                .productCode("QHJ-X")
                .productName("X")
                .bomVersionId("v1")
                .servingSize(new BigDecimal("100"))
                .energyPerServing(BigDecimal.ZERO)
                .proteinPerServing(BigDecimal.ZERO)
                .fatPerServing(BigDecimal.ZERO)
                .carbohydratePerServing(BigDecimal.ZERO)
                .sodiumPerServing(BigDecimal.ZERO)
                .generatedByUserId(1L)
                .build();
        assertNull(l.getSaturatedFatPerServing());
        assertNull(l.getTransFatPerServing());
        assertNull(l.getSugarPerServing());
        assertNull(l.getDietaryFiberPerServing());
    }

    @Test
    void statusOverrideTakesEffect() {
        NutritionLabel l = NutritionLabel.builder()
                .factoryId("F006")
                .productCode("Y")
                .productName("Y")
                .bomVersionId("v1")
                .servingSize(new BigDecimal("100"))
                .energyPerServing(BigDecimal.ZERO)
                .proteinPerServing(BigDecimal.ZERO)
                .fatPerServing(BigDecimal.ZERO)
                .carbohydratePerServing(BigDecimal.ZERO)
                .sodiumPerServing(BigDecimal.ZERO)
                .generatedByUserId(1L)
                .status("PUBLISHED")
                .build();
        assertEquals("PUBLISHED", l.getStatus());
    }

    @Test
    void softDeleteSetsDeletedAt() {
        NutritionLabel l = NutritionLabel.builder()
                .factoryId("F006").productCode("X").productName("X")
                .bomVersionId("v1").servingSize(new BigDecimal("100"))
                .energyPerServing(BigDecimal.ZERO).proteinPerServing(BigDecimal.ZERO)
                .fatPerServing(BigDecimal.ZERO).carbohydratePerServing(BigDecimal.ZERO)
                .sodiumPerServing(BigDecimal.ZERO).generatedByUserId(1L)
                .build();
        assertNull(l.getDeletedAt());
        l.softDelete();
        assertNotNull(l.getDeletedAt());
        assertTrue(l.isDeleted());
    }
}
