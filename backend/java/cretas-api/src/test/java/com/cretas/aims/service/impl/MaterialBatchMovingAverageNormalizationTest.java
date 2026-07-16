package com.cretas.aims.service.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.MaterialPackagingHierarchyRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.FuturePlanMatchingService;
import com.cretas.aims.service.inventory.cost.MaterialMovingAverageCalculator;
import com.cretas.aims.service.uom.MaterialUomConverter;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MaterialBatchMovingAverageNormalizationTest {

    @Test
    void recalculationUsesEveryBatchValueAfterNormalizingToMasterUnit() {
        MaterialBatchRepository batchRepository = mock(MaterialBatchRepository.class);
        RawMaterialTypeRepository materialRepository = mock(RawMaterialTypeRepository.class);
        MaterialBatchServiceImpl service = new MaterialBatchServiceImpl(
                batchRepository,
                mock(MaterialBatchAdjustmentRepository.class),
                materialRepository,
                mock(MaterialBatchMapper.class),
                mock(MaterialConsumptionRepository.class),
                mock(ProductionPlanBatchUsageRepository.class),
                mock(ExcelUtil.class),
                mock(FuturePlanMatchingService.class));

        MaterialUomConverter converter = new MaterialUomConverter(
                mock(MaterialPackagingHierarchyRepository.class),
                materialRepository,
                com.cretas.aims.service.unit.TestUnitContractFactory.legacyFacade());
        ReflectionTestUtils.setField(service, "materialMovingAverageCalculator",
                new MaterialMovingAverageCalculator(converter));

        RawMaterialType material = new RawMaterialType();
        material.setId("MAT-1");
        material.setFactoryId("F006");
        material.setUnit("kg");
        material.setMovingAvgPrice(new BigDecimal("10"));
        when(materialRepository.findById("MAT-1")).thenReturn(Optional.of(material));
        when(materialRepository.save(any(RawMaterialType.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(batchRepository.findByFactoryIdAndMaterialTypeId("F006", "MAT-1"))
                .thenReturn(List.of(batch("B-1", "1", "kg", "10"), batch("B-2", "1000", "g", "0.02")));

        service.recalculateMovingAvgPrice("MAT-1", new BigDecimal("1000"), new BigDecimal("0.02"), "B-2");

        assertThat(material.getMovingAvgPrice()).isEqualByComparingTo("15.0000");
    }

    private static MaterialBatch batch(String id, String quantity, String unit, String price) {
        MaterialBatch batch = new MaterialBatch();
        batch.setId(id);
        batch.setReceiptQuantity(new BigDecimal(quantity));
        batch.setUsedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setQuantityUnit(unit);
        batch.setUnitPrice(new BigDecimal(price));
        return batch;
    }
}
