package com.cretas.aims.service.unit;

import com.cretas.aims.entity.unit.ProductUnitConversion;
import com.cretas.aims.repository.MaterialPackagingHierarchyRepository;
import com.cretas.aims.repository.config.UnitOfMeasurementRepository;
import com.cretas.aims.repository.unit.ProductUnitConversionRepository;
import com.cretas.aims.service.unit.impl.UnitContractServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ProductUnitConversionGraphTest {

    private static final String FACTORY_ID = "F006";
    private static final String PRODUCT_TYPE_ID = "SKU-1";
    private static final LocalDateTime AT = LocalDateTime.of(2026, 7, 14, 0, 0);

    @Mock
    private UnitOfMeasurementRepository unitRepository;

    @Mock
    private ProductUnitConversionRepository conversionRepository;

    @Mock
    private MaterialPackagingHierarchyRepository materialPackagingRepository;

    private UnitContractService service;

    @BeforeEach
    void setUp() {
        service = new UnitContractServiceImpl(
                unitRepository, conversionRepository, materialPackagingRepository);
    }

    @Test
    void rejectsDifferentProductsAcrossMultipleShortestPaths() {
        givenEffectiveConversions(
                conversion("case-box", "case", "box", "10", 1L),
                conversion("box-g", "box", "g", "20", 2L),
                conversion("case-pcs", "case", "pcs", "5", 3L),
                conversion("pcs-g", "pcs", "g", "50", 4L));

        UnitConversionResult result = service.convert(
                new BigDecimal("1"), context("case", "g"));

        assertThat(result.status()).isEqualTo(UnitConversionStatus.AMBIGUOUS_CONVERSION);
        assertThat(result.quantity()).isEqualByComparingTo("1");
        assertThat(result.message()).contains("最短路径");
    }

    @Test
    void reportsNonIdentityConversionCycleAsInvalid() {
        givenEffectiveConversions(
                conversion("case-pcs", "case", "pcs", "10", 1L),
                conversion("pcs-g", "pcs", "g", "200", 2L),
                conversion("case-g", "case", "g", "2500", 3L));

        List<String> errors = service.validateConversionGraph(
                FACTORY_ID, PRODUCT_TYPE_ID, AT);

        assertThat(errors).anySatisfy(error ->
                assertThat(error).contains("闭环").contains("case-g"));
    }

    @Test
    void returnsCompleteAuditableStepsForComposedConversion() {
        givenEffectiveConversions(
                conversion("case-pcs", "case", "pcs", "12", 3L),
                conversion("pcs-g", "pcs", "g", "200", 7L));

        UnitConversionResult result = service.convert(
                new BigDecimal("1"), context("case", "g"));

        assertThat(result.status()).isEqualTo(UnitConversionStatus.CONVERTED);
        assertThat(result.quantity()).isEqualByComparingTo("2400");
        assertThat(result.path()).containsExactly("case", "pcs", "g");
        assertThat(result.steps()).containsExactly(
                new UnitConversionStep("case", "pcs", new BigDecimal("12"), "case-pcs", 3L),
                new UnitConversionStep("pcs", "g", new BigDecimal("200"), "pcs-g", 7L));
        assertThat(result.conversionRefId()).isNull();
        assertThat(result.conversionVersion()).isNull();
    }

    private void givenEffectiveConversions(ProductUnitConversion... conversions) {
        given(conversionRepository.findEffectiveByFactoryIdAndProductTypeIdAt(
                FACTORY_ID, PRODUCT_TYPE_ID, AT)).willReturn(List.of(conversions));
    }

    private ProductUnitConversion conversion(
            String id,
            String fromUnit,
            String toUnit,
            String factor,
            long version) {
        return ProductUnitConversion.builder()
                .id(id)
                .factoryId(FACTORY_ID)
                .productTypeId(PRODUCT_TYPE_ID)
                .fromUnitCode(fromUnit)
                .toUnitCode(toUnit)
                .factor(new BigDecimal(factor))
                .sourceType(ProductUnitConversion.SourceType.MANUAL)
                .primarySalesConversion(false)
                .effectiveFrom(AT.minusDays(1))
                .version(version)
                .build();
    }

    private UnitConversionContext context(String fromUnit, String toUnit) {
        return new UnitConversionContext(
                FACTORY_ID,
                PRODUCT_TYPE_ID,
                fromUnit,
                toUnit,
                AT,
                UnitUsageScene.INVENTORY,
                2,
                RoundingMode.HALF_UP
        );
    }
}
