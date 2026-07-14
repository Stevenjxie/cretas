package com.cretas.aims.service.unit;

import com.cretas.aims.entity.config.UnitOfMeasurement;
import com.cretas.aims.entity.unit.ProductUnitConversion;
import com.cretas.aims.repository.config.UnitOfMeasurementRepository;
import com.cretas.aims.repository.unit.ProductUnitConversionRepository;
import com.cretas.aims.service.unit.impl.UnitContractServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnitContractServiceTest {

    private static final String FACTORY_ID = "F006";
    private static final String PRODUCT_TYPE_ID = "SKU-1";
    private static final LocalDateTime AT = LocalDateTime.of(2026, 7, 14, 0, 0);

    @Mock
    private UnitOfMeasurementRepository unitRepository;

    @Mock
    private ProductUnitConversionRepository conversionRepository;

    private UnitContractService service;

    @BeforeEach
    void setUp() {
        service = new UnitContractServiceImpl(unitRepository, conversionRepository);
    }

    @ParameterizedTest
    @CsvSource({
            "克,g", "g,g", "公斤,kg", "千克,kg", "KG,kg",
            "毫升,ml", "mL,ml", "升,l", "件,pcs", "个,pcs", "只,pcs",
            "份,portion", "盒,box", "箱,case", "袋,bag", "包,pack", "瓶,bottle",
            "罐,can", "框,crate", "筐,crate", "桶,pail", "卷,roll", "片,slice", "项,item"
    })
    void normalizeKnownAliases(String raw, String expected) {
        assertThat(service.normalize(FACTORY_ID, raw).code()).isEqualTo(expected);
    }

    @Test
    void describesKnownMassUnitWithCanonicalDimension() {
        assertThat(service.describe(FACTORY_ID, "公斤"))
                .hasValueSatisfying(unit -> {
                    assertThat(unit.code()).isEqualTo("kg");
                    assertThat(unit.dimension()).isEqualTo(UnitDimension.MASS);
                    assertThat(unit.baseCode()).isEqualTo("g");
                    assertThat(unit.factorToBase()).isEqualByComparingTo("1000");
                });
    }

    @Test
    void boxAndPcsAreNotEquivalent() {
        assertThat(service.areEquivalent(FACTORY_ID, "盒", "件")).isFalse();
    }

    @Test
    void convertsMassWithoutApplyingDisplayRounding() {
        UnitConversionResult result = service.convert(
                new BigDecimal("1.234567"), context("kg", "g"));

        assertThat(result.status()).isEqualTo(UnitConversionStatus.CONVERTED);
        assertThat(result.quantity()).isEqualByComparingTo("1234.567");
    }

    @Test
    void refusesCountToMassWithoutProductContext() {
        assertThat(service.convert(context("pcs", "g")).status())
                .isEqualTo(UnitConversionStatus.PRODUCT_CONVERSION_MISSING);

        verifyNoInteractions(conversionRepository);
    }

    @Test
    void convertsProductSpecificCountToMassBothWays() {
        given(conversionRepository.findEffectiveByFactoryIdAndProductTypeIdAt(
                FACTORY_ID, PRODUCT_TYPE_ID, AT))
                .willReturn(List.of(conversion("pcs-g", "pcs", "g", "200", 7L)));

        UnitConversionResult forward = service.convert(
                new BigDecimal("10"), context(PRODUCT_TYPE_ID, "pcs", "g"));
        UnitConversionResult reverse = service.convert(
                new BigDecimal("2000"), context(PRODUCT_TYPE_ID, "g", "pcs"));

        assertThat(forward.status()).isEqualTo(UnitConversionStatus.CONVERTED);
        assertThat(forward.quantity()).isEqualByComparingTo("2000");
        assertThat(reverse.status()).isEqualTo(UnitConversionStatus.CONVERTED);
        assertThat(reverse.quantity()).isEqualByComparingTo("10");
    }

    @Test
    void intrinsicMassConversionWinsBeforeProductGraphLookup() {
        UnitConversionResult result = service.convert(
                new BigDecimal("1000"), context(PRODUCT_TYPE_ID, "g", "kg"));

        assertThat(result.status()).isEqualTo(UnitConversionStatus.CONVERTED);
        assertThat(result.quantity()).isEqualByComparingTo("1");
        verifyNoInteractions(conversionRepository);
    }

    @Test
    void refusesProductConversionWhenEffectiveRelationshipIsMissing() {
        given(conversionRepository.findEffectiveByFactoryIdAndProductTypeIdAt(
                FACTORY_ID, PRODUCT_TYPE_ID, AT)).willReturn(List.of());

        UnitConversionResult result = service.convert(
                new BigDecimal("1"), context(PRODUCT_TYPE_ID, "case", "g"));

        assertThat(result.status()).isEqualTo(UnitConversionStatus.PRODUCT_CONVERSION_MISSING);
        assertThat(result.quantity()).isEqualByComparingTo("1");
    }

    @Test
    void normalizesFactoryCatalogAliasesWithoutTreatingThemAsPackageConversions() {
        UnitOfMeasurement crate = UnitOfMeasurement.builder()
                .factoryId(FACTORY_ID)
                .unitCode("crate")
                .unitName("周转筐")
                .unitSymbol("筐")
                .baseUnit("crate")
                .category("PACKAGE")
                .aliasesJson(List.of("筐"))
                .isActive(true)
                .build();
        when(unitRepository.findAllByFactoryId(FACTORY_ID)).thenReturn(List.of(crate));

        assertThat(service.normalize(FACTORY_ID, "筐").code()).isEqualTo("crate");
        assertThat(service.areEquivalent(FACTORY_ID, "筐", "盒")).isFalse();
    }

    @Test
    void rejectsFactoryAliasThatMasqueradesAsSystemBox() {
        UnitOfMeasurement kilogram = catalogUnit("kg", "MASS", "box");
        when(unitRepository.findAllByFactoryId(FACTORY_ID)).thenReturn(List.of(kilogram));

        assertThat(service.normalize(FACTORY_ID, "box").recognized()).isFalse();
        assertThat(service.convert(new BigDecimal("1"), context("box", "g")).status())
                .isEqualTo(UnitConversionStatus.UNKNOWN_UNIT);
    }

    @Test
    void rejectsDuplicateAliasAcrossFactoryAndGlobalCatalogEntries() {
        UnitOfMeasurement crate = catalogUnit("crate", "PACKAGE", "周转容器");
        UnitOfMeasurement pallet = catalogUnit("pallet", "PACKAGE", "周转容器");
        when(unitRepository.findAllByFactoryId(FACTORY_ID)).thenReturn(List.of(crate, pallet));

        assertThat(service.normalize(FACTORY_ID, "周转容器").recognized()).isFalse();
    }

    @Test
    void rejectsFactoryAliasThatConflictsWithSystemAlias() {
        UnitOfMeasurement crate = catalogUnit("crate", "PACKAGE", "公斤");
        when(unitRepository.findAllByFactoryId(FACTORY_ID)).thenReturn(List.of(crate));

        assertThat(service.normalize(FACTORY_ID, "公斤").recognized()).isFalse();
    }

    @Test
    void legacyGlobalSystemDictionaryCannotOverrideCanonicalBoxAndCaseAliases() {
        UnitOfMeasurement legacyBox = UnitOfMeasurement.builder()
                .factoryId("*")
                .unitCode("box")
                .unitName("箱")
                .unitSymbol("箱")
                .baseUnit("pcs")
                .category("COUNT")
                .isSystem(true)
                .isActive(true)
                .build();
        when(unitRepository.findAllByFactoryId(FACTORY_ID)).thenReturn(List.of(legacyBox));

        assertThat(service.normalize(FACTORY_ID, "盒").code()).isEqualTo("box");
        assertThat(service.normalize(FACTORY_ID, "箱").code()).isEqualTo("case");
        assertThat(service.normalize(FACTORY_ID, "box").code()).isEqualTo("box");
    }

    private UnitOfMeasurement catalogUnit(String code, String category, String alias) {
        return UnitOfMeasurement.builder()
                .factoryId(FACTORY_ID)
                .unitCode(code)
                .unitName(code)
                .unitSymbol(code)
                .baseUnit(code)
                .category(category)
                .aliasesJson(List.of(alias))
                .isActive(true)
                .build();
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
        return context(null, fromUnit, toUnit);
    }

    private UnitConversionContext context(String productTypeId, String fromUnit, String toUnit) {
        return new UnitConversionContext(
                FACTORY_ID,
                productTypeId,
                fromUnit,
                toUnit,
                AT,
                UnitUsageScene.INVENTORY,
                2,
                RoundingMode.HALF_UP
        );
    }
}
