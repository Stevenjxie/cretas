package com.cretas.aims.service.recipe;

import com.cretas.aims.entity.bom.BomSeasoningItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecipeCostCalculatorBindingPotTest {
    @Test
    void eachBindingUsesItsOwnNonCompoundingPotRule() {
        BomSeasoningItem chili = cooking("MAT-CHILI", "10", "1000", "0.5");
        BomSeasoningItem salt = cooking("MAT-SALT", "10", "1000", null);
        SeasoningCost result = RecipeCostCalculator.computeBindingPotRules(
                new BigDecimal("0.2"), List.of(chili, salt),
                List.of(new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100")));
        // price=1000/kg makes numeric cost equal grams: chili=2000g, salt=3000g.
        assertEquals(0, new BigDecimal("5000.0000").compareTo(result.getCookingTotal()));
    }

    @Test
    void legacyUnboundRowMayFallbackToProcessRatio() {
        BomSeasoningItem legacy = cooking(null, "10", "1000", null);
        SeasoningCost result = RecipeCostCalculator.computeBindingPotRules(
                new BigDecimal("0.5"), List.of(legacy),
                List.of(new BigDecimal("100"), new BigDecimal("100")));
        assertEquals(0, new BigDecimal("1500.0000").compareTo(result.getCookingTotal()));
    }

    private BomSeasoningItem cooking(String materialTypeId, String dosage, String price, String ratio) {
        BomSeasoningItem item = new BomSeasoningItem();
        item.setMaterialTypeId(materialTypeId);
        item.setSection("COOKING");
        item.setDosagePerKgG(new BigDecimal(dosage));
        item.setPriceSource1(new BigDecimal(price));
        item.setCountInSeasoning(true);
        item.setSubsequentPotRatio(ratio == null ? null : new BigDecimal(ratio));
        return item;
    }
}
