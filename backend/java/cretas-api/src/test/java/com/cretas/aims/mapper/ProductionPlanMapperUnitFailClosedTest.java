package com.cretas.aims.mapper;

import com.cretas.aims.dto.production.CreateProductionPlanRequest;
import com.cretas.aims.entity.ProductionPlan;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProductionPlanMapperUnitFailClosedTest {

    private final ProductionPlanMapper mapper = new ProductionPlanMapper();

    @Test
    void entityAndMapperDoNotInventKilogramsWhenPlannedUnitIsMissing() {
        ProductionPlan fresh = new ProductionPlan();
        assertNull(fresh.getPlannedUnit());

        ProductionPlan existing = new ProductionPlan();
        existing.setPlannedUnit(null);
        assertNull(mapper.toDTO(existing).getPlannedUnit());

        CreateProductionPlanRequest request = new CreateProductionPlanRequest();
        request.setProductTypeId("PT-1");
        request.setPlannedUnit(null);
        assertNull(mapper.toEntity(request, "F001", 1L).getPlannedUnit());
    }

    @Test
    void projectsPlanNetWeightSnapshotWithoutReReadingSku() {
        ProductionPlan plan = new ProductionPlan();
        plan.setPlannedNetWeightGrams(new BigDecimal("200"));

        assertEquals(new BigDecimal("200"), mapper.toDTO(plan).getPlannedNetWeightGrams());
    }
}
