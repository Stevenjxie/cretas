package com.cretas.aims.service.impl;

import com.cretas.aims.entity.ProductionSettlementOutputLine;
import com.cretas.aims.entity.bom.BomRecipe;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductionWorkflowOutputCostAllocationTest {

    @Test
    void appliesRatioOncePerSkuThenSplitsThatSkuCostAcrossItsBatches() {
        ProductionSettlementOutputLine a1 = line("A", "60", "70");
        ProductionSettlementOutputLine a2 = line("A", "40", "70");
        ProductionSettlementOutputLine b1 = line("B", "50", "30");

        ProductionPlanServiceImpl.allocateWorkflowOutputCostsFromTotal(
                new BigDecimal("1000"), List.of(a1, a2, b1));

        assertDecimal("420", a1.getAllocatedCost());
        assertDecimal("280", a2.getAllocatedCost());
        assertDecimal("300", b1.getAllocatedCost());
        assertDecimal("7", a1.getUnitCost());
        assertDecimal("7", a2.getUnitCost());
        assertDecimal("6", b1.getUnitCost());
        assertDecimal("1000", List.of(a1, a2, b1).stream()
                .map(ProductionSettlementOutputLine::getAllocatedCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private ProductionSettlementOutputLine line(String sku, String quantity, String ratio) {
        ProductionSettlementOutputLine line = ProductionSettlementOutputLine.create();
        line.setProductTypeId(sku);
        line.setOutputRole("A".equals(sku)
                ? BomRecipe.OutputRole.MAIN : BomRecipe.OutputRole.CO_PRODUCT);
        line.setReceivedQuantity(new BigDecimal(quantity));
        line.setCostAllocationRatio(new BigDecimal(ratio));
        return line;
    }

    private void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
