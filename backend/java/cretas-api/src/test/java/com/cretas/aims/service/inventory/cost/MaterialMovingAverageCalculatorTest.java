package com.cretas.aims.service.inventory.cost;

import com.cretas.aims.entity.MaterialPackagingHierarchy;
import com.cretas.aims.repository.MaterialPackagingHierarchyRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.uom.MaterialUomConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MaterialMovingAverageCalculatorTest {

    private static final String MATERIAL_ID = "MAT-001";

    private MaterialPackagingHierarchyRepository packagingRepository;
    private MaterialMovingAverageCalculator calculator;

    @BeforeEach
    void setUp() {
        packagingRepository = mock(MaterialPackagingHierarchyRepository.class);
        RawMaterialTypeRepository materialRepository = mock(RawMaterialTypeRepository.class);
        MaterialUomConverter converter = new MaterialUomConverter(
                packagingRepository,
                materialRepository,
                com.cretas.aims.service.unit.TestUnitContractFactory.legacyFacade());
        calculator = new MaterialMovingAverageCalculator(converter);
    }

    @Test
    void normalizesGramAndKilogramLayersBeforeAveraging() {
        MaterialMovingAverageCalculator.CalculationResult result = calculator.calculate(
                MATERIAL_ID,
                "kg",
                List.of(
                        new MaterialMovingAverageCalculator.CostLayer(
                                new BigDecimal("1"), "kg", new BigDecimal("10"), "B-1"),
                        new MaterialMovingAverageCalculator.CostLayer(
                                new BigDecimal("1000"), "g", new BigDecimal("0.02"), "B-2")));

        assertThat(result.complete()).isTrue();
        assertThat(result.normalizedQuantity()).isEqualByComparingTo("2");
        assertThat(result.totalValue()).isEqualByComparingTo("30");
        assertThat(result.averagePrice()).isEqualByComparingTo("15.0000");
    }

    @Test
    void normalizesPackagingLayersThroughMaterialHierarchy() {
        MaterialPackagingHierarchy hierarchy = new MaterialPackagingHierarchy();
        hierarchy.setLevel1Unit("kg");
        hierarchy.setLevel2Unit("box");
        hierarchy.setLevel1PerLevel2(new BigDecimal("10"));
        when(packagingRepository.findByMaterialTypeId(MATERIAL_ID)).thenReturn(Optional.of(hierarchy));

        MaterialMovingAverageCalculator.CalculationResult result = calculator.calculate(
                MATERIAL_ID,
                "kg",
                List.of(
                        new MaterialMovingAverageCalculator.CostLayer(
                                new BigDecimal("10"), "box", new BigDecimal("100"), "B-BOX"),
                        new MaterialMovingAverageCalculator.CostLayer(
                                new BigDecimal("100"), "kg", new BigDecimal("8"), "B-KG")));

        assertThat(result.complete()).isTrue();
        assertThat(result.normalizedQuantity()).isEqualByComparingTo("200");
        assertThat(result.totalValue()).isEqualByComparingTo("1800");
        assertThat(result.averagePrice()).isEqualByComparingTo("9.0000");
    }

    @Test
    void refusesToPublishPartialAverageWhenAUnitCannotBeConverted() {
        MaterialMovingAverageCalculator.CalculationResult result = calculator.calculate(
                MATERIAL_ID,
                "kg",
                List.of(new MaterialMovingAverageCalculator.CostLayer(
                        new BigDecimal("10"), "box", new BigDecimal("100"), "B-BAD")));

        assertThat(result.complete()).isFalse();
        assertThat(result.averagePrice()).isNull();
        assertThat(result.issues()).singleElement().asString().contains("B-BAD");
    }
}
