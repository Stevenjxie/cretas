package com.cretas.aims.mapper;

import com.cretas.aims.dto.production.CreateProductionPlanRequest;
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.entity.ProductionPlan;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionPlanSourceDisplayMapperTest {

    private final ProductionPlanMapper mapper = new ProductionPlanMapper();

    @Test
    void preservesSalesOrderDisplayQuantitySeparatelyFromNormalizedPlanQuantity() {
        CreateProductionPlanRequest request = new CreateProductionPlanRequest();
        request.setProductTypeId("PROD-1");
        request.setPlannedQuantity(new BigDecimal("500"));
        request.setPlannedUnit("piece");
        request.setSourceDisplayQuantity(new BigDecimal("10"));
        request.setSourceDisplayUnit("box");
        request.setWorkflowOutputUnit("g");

        ProductionPlan entity = mapper.toEntity(request, "F006", 1L);
        ProductionPlanDTO dto = mapper.toDTO(entity);

        assertThat(dto.getPlannedQuantity()).isEqualByComparingTo("500");
        assertThat(dto.getPlannedUnit()).isEqualTo("piece");
        assertThat(dto.getSourceDisplayQuantity()).isEqualByComparingTo("10");
        assertThat(dto.getSourceDisplayUnit()).isEqualTo("box");
        assertThat(dto.getWorkflowOutputUnit()).isEqualTo("g");
    }
}
