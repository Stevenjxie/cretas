package com.cretas.aims.service.production;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.entity.unit.ProductUnitConversion;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialPackagingHierarchyRepository;
import com.cretas.aims.repository.config.UnitOfMeasurementRepository;
import com.cretas.aims.repository.material.MaterialPackagingSpecRepository;
import com.cretas.aims.repository.unit.ProductUnitConversionRepository;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.impl.UnitContractServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SalesOrderPlanQuantityNormalizerTest {

    private static final String FACTORY_ID = "F006";
    private static final String SKU_ID = "eb0aa47b-a5dd-49dc-af20-bf48ce8e1207";

    private SalesOrderPlanQuantityNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new SalesOrderPlanQuantityNormalizer(
                com.cretas.aims.service.unit.TestUnitContractFactory.contract());
    }

    @Test
    void convertsSalesBoxesToProductBaseUnitsUsingImmutablePackagingSnapshot() {
        SalesOrderItem item = item("10", "box");
        item.setPackagingUnit("box");
        item.setPackagingBaseUnit("piece");
        item.setPackagingFactor(new BigDecimal("50"));

        SalesOrderPlanQuantityNormalizer.PlanQuantity result = normalizer.normalize(item, product("piece", "200"));

        assertThat(result.quantity()).isEqualByComparingTo("500");
        assertThat(result.unit()).isEqualTo("piece");
        assertThat(result.displayQuantity()).isEqualByComparingTo("10");
        assertThat(result.displayUnit()).isEqualTo("box");
    }

    @Test
    void convertsAWeightBasedSalesLineToProductBaseUnitsUsingNetWeightSnapshot() {
        SalesOrderItem item = item("100000", "g");

        SalesOrderPlanQuantityNormalizer.PlanQuantity result = normalizer.normalize(item, product("piece", "200"));

        assertThat(result.quantity()).isEqualByComparingTo("500");
        assertThat(result.unit()).isEqualTo("piece");
    }

    @Test
    void failsLoudWhenCrossDimensionConversionHasNoPackagingOrNetWeightContract() {
        SalesOrderItem item = item("10", "box");

        assertThatThrownBy(() -> normalizer.normalize(item, product("piece", null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("SALES_PLAN_UNIT_UNCONVERTIBLE");
    }

    /**
     * Prod repro of SO-20260817-0001 (F006, 2026-08-17): the order line stores the English alias
     * {@code case} while its own packaging snapshot stores the Chinese code {@code 箱}. Both name the
     * same canonical unit, so the snapshot must still apply. Before the fix the two were compared as
     * raw strings and the line was rejected with SALES_PLAN_UNIT_UNCONVERTIBLE.
     */
    @Test
    void appliesThePackagingSnapshotWhenTheOrderLineUsesAnAliasOfTheSnapshotUnit() {
        SalesOrderItem item = item("10", "case");
        item.setPackagingUnit("箱");
        item.setPackagingBaseUnit("盒");
        item.setPackagingFactor(new BigDecimal("8"));

        SalesOrderPlanQuantityNormalizer.PlanQuantity result =
                normalizer.normalize(item, product("盒", "800"));

        assertThat(result.quantity()).isEqualByComparingTo("80");
        assertThat(result.unit()).isEqualTo("盒");
        assertThat(result.displayUnit()).isEqualTo("case");
    }

    /** An alias-only unit pair with no packaging hop at all is the same unit and must pass through. */
    @Test
    void treatsAnEnglishAliasOfTheProductBaseUnitAsTheSameUnit() {
        SalesOrderItem item = item("10", "box");

        SalesOrderPlanQuantityNormalizer.PlanQuantity result =
                normalizer.normalize(item, product("盒", null));

        assertThat(result.quantity()).isEqualByComparingTo("10");
        assertThat(result.unit()).isEqualTo("盒");
    }

    /**
     * The SKU's packaging spec (箱→盒 = 8) and net content (盒→800g) are synchronized into
     * {@code product_unit_conversions} by ProductSpecificationConversionSyncService. Reaching that
     * graph requires factoryId + productTypeId + business time; the normalizer previously called a
     * facade that hardcoded all three to null, so the graph was never consulted.
     */
    @Test
    void fallsBackToTheProductConversionGraphWhenTheOrderLineHasNoPackagingSnapshot() {
        normalizer = new SalesOrderPlanQuantityNormalizer(contractWithSkuGraph());

        SalesOrderItem item = item("10", "箱");

        SalesOrderPlanQuantityNormalizer.PlanQuantity result =
                normalizer.normalize(item, product("盒", "800"));

        assertThat(result.quantity()).isEqualByComparingTo("80");
        assertThat(result.unit()).isEqualTo("盒");
    }

    /**
     * Negative control for the graph wiring: same call shape, but the SKU has no effective
     * conversions. It must still fail loud rather than invent a factor.
     */
    @Test
    void stillFailsLoudWhenTheProductConversionGraphIsEmpty() {
        SalesOrderItem item = item("10", "箱");

        assertThatThrownBy(() -> normalizer.normalize(item, product("盒", null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("SALES_PLAN_UNIT_UNCONVERTIBLE");
    }

    /** The refusal must name the SKU and the fields that are actually missing on it. */
    @Test
    void refusalNamesTheSkuAndOnlyTheFieldsThatAreActuallyMissing() {
        SalesOrderItem item = item("10", "箱");
        ProductType product = product("盒", null);
        product.setName("SOP-20260817-01-黄油鸡-成品800g");
        product.setCode("CPF0060028");

        assertThatThrownBy(() -> normalizer.normalize(item, product))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CPF0060028")
                .hasMessageContaining("箱")
                .hasMessageContaining("盒");
    }

    private static UnitContractService contractWithSkuGraph() {
        ProductUnitConversionRepository conversions = mock(ProductUnitConversionRepository.class);
        when(conversions.findEffectiveByFactoryIdAndProductTypeIdAt(
                eq(FACTORY_ID), eq(SKU_ID), any(LocalDateTime.class)))
                .thenReturn(List.of(
                        conversion("箱", "盒", "8", ProductUnitConversion.SourceType.PACKAGING),
                        conversion("盒", "g", "800", ProductUnitConversion.SourceType.NET_CONTENT)));
        return new UnitContractServiceImpl(
                mock(UnitOfMeasurementRepository.class),
                conversions,
                mock(MaterialPackagingHierarchyRepository.class),
                mock(MaterialPackagingSpecRepository.class));
    }

    private static ProductUnitConversion conversion(
            String from, String to, String factor, ProductUnitConversion.SourceType sourceType) {
        ProductUnitConversion conversion = new ProductUnitConversion();
        conversion.setFactoryId(FACTORY_ID);
        conversion.setProductTypeId(SKU_ID);
        conversion.setFromUnitCode(from);
        conversion.setToUnitCode(to);
        conversion.setFactor(new BigDecimal(factor));
        conversion.setSourceType(sourceType);
        conversion.setEffectiveFrom(LocalDateTime.now().minusDays(1));
        return conversion;
    }

    private static SalesOrderItem item(String quantity, String unit) {
        SalesOrderItem item = new SalesOrderItem();
        item.setQuantity(new BigDecimal(quantity));
        item.setUnit(unit);
        return item;
    }

    private static ProductType product(String unit, String gramsPerUnit) {
        ProductType product = new ProductType();
        product.setId(SKU_ID);
        product.setFactoryId(FACTORY_ID);
        product.setUnit(unit);
        product.setGramsPerUnit(gramsPerUnit == null ? null : new BigDecimal(gramsPerUnit));
        return product;
    }
}
