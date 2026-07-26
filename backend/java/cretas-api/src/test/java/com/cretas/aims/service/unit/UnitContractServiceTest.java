package com.cretas.aims.service.unit;

import com.cretas.aims.entity.config.UnitOfMeasurement;
import com.cretas.aims.entity.MaterialPackagingHierarchy;
import com.cretas.aims.entity.material.MaterialPackagingSpec;
import com.cretas.aims.entity.unit.ProductUnitConversion;
import com.cretas.aims.repository.MaterialPackagingHierarchyRepository;
import com.cretas.aims.repository.config.UnitOfMeasurementRepository;
import com.cretas.aims.repository.material.MaterialPackagingSpecRepository;
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
import java.util.Optional;

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

    @Mock
    private MaterialPackagingHierarchyRepository materialPackagingRepository;

    @Mock
    private MaterialPackagingSpecRepository materialPackagingSpecRepository;

    private UnitContractService service;

    @BeforeEach
    void setUp() {
        service = new UnitContractServiceImpl(
                unitRepository, conversionRepository, materialPackagingRepository,
                materialPackagingSpecRepository);
    }

    @ParameterizedTest
    @CsvSource({
            "克,g", "g,g", "公斤,kg", "千克,kg", "KG,kg",
            "毫升,ml", "mL,ml", "升,l", "件,pcs", "个,pcs", "只,pcs",
            "毫米,mm", "公厘,mm", "厘米,cm", "公分,cm", "米,m", "公尺,m", "千米,km", "公里,km",
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
    void catalogIncludesBuiltInLengthUnitsWithoutDatabaseSeedRows() {
        assertThat(service.catalog(FACTORY_ID))
                .filteredOn(unit -> unit.dimension() == UnitDimension.LENGTH)
                .extracting(CanonicalUnit::code)
                .containsExactly("cm", "km", "m", "mm");
    }

    @Test
    void inventoryCatalogExcludesLengthTimeTemperatureAndRatio() {
        UnitOfMeasurement minute = catalogUnit("minute", "TIME", "分钟");
        UnitOfMeasurement celsius = catalogUnit("celsius", "TEMPERATURE", "摄氏度");
        UnitOfMeasurement percent = catalogUnit("percent", "RATIO", "百分比");
        when(unitRepository.findAllByFactoryId(FACTORY_ID))
                .thenReturn(List.of(minute, celsius, percent));

        assertThat(service.catalog(FACTORY_ID, UnitUsageScope.INVENTORY_QUANTITY))
                .extracting(CanonicalUnit::code)
                .contains("g", "kg", "box", "case", "slice")
                .doesNotContain("mm", "cm", "m", "km", "minute", "celsius", "percent");
    }

    @Test
    void explicitlyScopedLengthUnitCanBeUsedForInventory() {
        UnitOfMeasurement metre = catalogUnit("m", "LENGTH", "米");
        metre.setUsageScopesJson(List.of("INVENTORY_QUANTITY", "SPECIFICATION"));
        metre.setConversionFamily("LENGTH");
        when(unitRepository.findAllByFactoryId(FACTORY_ID)).thenReturn(List.of(metre));

        assertThat(service.supportsUsage(FACTORY_ID, "米", UnitUsageScope.INVENTORY_QUANTITY))
                .isTrue();
        assertThat(service.catalog(FACTORY_ID, UnitUsageScope.INVENTORY_QUANTITY))
                .extracting(CanonicalUnit::code)
                .contains("m");
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
    void convertsLengthAliasesWithinTheLengthDimension() {
        UnitConversionResult result = service.convert(
                new BigDecimal("1.25"), context("公里", "米"));

        assertThat(result.status()).isEqualTo(UnitConversionStatus.CONVERTED);
        assertThat(result.quantity()).isEqualByComparingTo("1250");
        assertThat(service.describe(FACTORY_ID, "cm"))
                .hasValueSatisfying(unit -> assertThat(unit.dimension()).isEqualTo(UnitDimension.LENGTH));
        verifyNoInteractions(conversionRepository);
    }

    @Test
    void rejectsCrossScientificDimensionsEvenWhenProductContextExists() {
        UnitConversionResult result = service.convert(
                new BigDecimal("1"), context(PRODUCT_TYPE_ID, "kg", "m"));

        assertThat(result.status()).isEqualTo(UnitConversionStatus.INCOMPATIBLE_DIMENSION);
        assertThat(result.quantity()).isEqualByComparingTo("1");
        verifyNoInteractions(conversionRepository);
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
    void convertsRawMaterialPurchaseCasesToInventoryKilogramsBothWays() {
        given(conversionRepository.findEffectiveByFactoryIdAndProductTypeIdAt(
                FACTORY_ID, "MAT-1", AT)).willReturn(List.of());
        given(materialPackagingRepository.findByMaterialTypeId("MAT-1"))
                .willReturn(Optional.of(packagingHierarchy("MAT-1", "kg", "case", "10", null, null)));

        UnitConversionResult inbound = service.convert(
                new BigDecimal("8"), context("MAT-1", "case", "kg"));
        UnitConversionResult reverse = service.convert(
                new BigDecimal("25"), context("MAT-1", "kg", "case"));

        assertThat(inbound.status()).isEqualTo(UnitConversionStatus.CONVERTED);
        assertThat(inbound.quantity()).isEqualByComparingTo("80");
        assertThat(inbound.path()).containsExactly("case", "kg");
        assertThat(reverse.quantity()).isEqualByComparingTo("2.5");
    }

    @Test
    void convertsAnyDynamicMaterialPackagingRuleDirectlyToInventoryBaseUnit() {
        given(conversionRepository.findEffectiveByFactoryIdAndProductTypeIdAt(
                FACTORY_ID, "MAT-1", AT)).willReturn(List.of());
        given(materialPackagingSpecRepository
                .findByFactoryIdAndMaterialTypeIdAndActiveTrueOrderBySortOrderAscCreatedAtAsc(
                        FACTORY_ID, "MAT-1"))
                .willReturn(List.of(packagingSpec("SPEC-PAIL", "MAT-1", "pail", "kg", "18.5")));

        UnitConversionResult inbound = service.convert(
                new BigDecimal("4"), context("MAT-1", "pail", "kg"));
        UnitConversionResult reverse = service.convert(
                new BigDecimal("37"), context("MAT-1", "kg", "pail"));

        assertThat(inbound.status()).isEqualTo(UnitConversionStatus.CONVERTED);
        assertThat(inbound.quantity()).isEqualByComparingTo("74");
        assertThat(inbound.conversionRefId()).isEqualTo("SPEC-PAIL");
        assertThat(reverse.quantity()).isEqualByComparingTo("2");
    }

    @Test
    void composesRawMaterialThirdLevelPackagingConversion() {
        given(conversionRepository.findEffectiveByFactoryIdAndProductTypeIdAt(
                FACTORY_ID, "MAT-1", AT)).willReturn(List.of());
        given(materialPackagingRepository.findByMaterialTypeId("MAT-1"))
                .willReturn(Optional.of(packagingHierarchy(
                        "MAT-1", "kg", "case", "10", "crate", "12")));

        UnitConversionResult result = service.convert(
                new BigDecimal("2"), context("MAT-1", "crate", "kg"));

        assertThat(result.status()).isEqualTo(UnitConversionStatus.CONVERTED);
        assertThat(result.quantity()).isEqualByComparingTo("240");
        assertThat(result.path()).containsExactly("crate", "case", "kg");
        assertThat(result.steps()).hasSize(2);
    }

    @Test
    void explicitProductConversionKeepsPrecedenceOverMaterialHierarchy() {
        given(conversionRepository.findEffectiveByFactoryIdAndProductTypeIdAt(
                FACTORY_ID, "MAT-1", AT))
                .willReturn(List.of(conversion("manual-case-kg", "case", "kg", "12", 8L)));

        UnitConversionResult result = service.convert(
                new BigDecimal("2"), context("MAT-1", "case", "kg"));

        assertThat(result.quantity()).isEqualByComparingTo("24");
        assertThat(result.conversionRefId()).isEqualTo("manual-case-kg");
        verifyNoInteractions(materialPackagingRepository);
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

    private MaterialPackagingHierarchy packagingHierarchy(
            String materialTypeId,
            String level1Unit,
            String level2Unit,
            String level1PerLevel2,
            String level3Unit,
            String level2PerLevel3) {
        MaterialPackagingHierarchy hierarchy = new MaterialPackagingHierarchy();
        hierarchy.setId("MPH-" + materialTypeId);
        hierarchy.setFactoryId(FACTORY_ID);
        hierarchy.setMaterialTypeId(materialTypeId);
        hierarchy.setLevel1Unit(level1Unit);
        hierarchy.setLevel2Unit(level2Unit);
        hierarchy.setLevel1PerLevel2(new BigDecimal(level1PerLevel2));
        hierarchy.setLevel3Unit(level3Unit);
        hierarchy.setLevel2PerLevel3(
                level2PerLevel3 == null ? null : new BigDecimal(level2PerLevel3));
        return hierarchy;
    }

    private MaterialPackagingSpec packagingSpec(
            String id, String materialTypeId, String packageUnit, String baseUnit, String factor) {
        MaterialPackagingSpec spec = new MaterialPackagingSpec();
        spec.setId(id);
        spec.setFactoryId(FACTORY_ID);
        spec.setMaterialTypeId(materialTypeId);
        spec.setName("包装规格");
        spec.setPackageUnit(packageUnit);
        spec.setBaseUnit(baseUnit);
        spec.setConversionFactor(new BigDecimal(factor));
        spec.setDefaultSpec(true);
        spec.setActive(true);
        spec.setSortOrder(0);
        spec.setVersion(0L);
        return spec;
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
