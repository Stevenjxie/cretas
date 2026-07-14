package com.cretas.aims.service.unit;

import com.cretas.aims.entity.config.UnitOfMeasurement;
import com.cretas.aims.repository.config.UnitOfMeasurementRepository;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnitContractServiceTest {

    private static final String FACTORY_ID = "F006";

    @Mock
    private UnitOfMeasurementRepository unitRepository;

    private UnitContractService service;

    @BeforeEach
    void setUp() {
        service = new UnitContractServiceImpl(unitRepository);
    }

    @ParameterizedTest
    @CsvSource({
            "克,g", "g,g", "公斤,kg", "千克,kg", "KG,kg",
            "毫升,ml", "mL,ml", "升,l", "件,pcs", "个,pcs", "只,pcs",
            "份,portion", "盒,box", "箱,case", "袋,bag", "瓶,bottle"
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

    private UnitConversionContext context(String fromUnit, String toUnit) {
        return new UnitConversionContext(
                FACTORY_ID,
                null,
                fromUnit,
                toUnit,
                LocalDateTime.of(2026, 7, 14, 0, 0),
                UnitUsageScene.INVENTORY,
                2,
                RoundingMode.HALF_UP
        );
    }
}
