package com.cretas.aims.service.production;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SalesOrderPlanQuantityNormalizerTest {

    private SalesOrderPlanQuantityNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new SalesOrderPlanQuantityNormalizer(
                com.cretas.aims.service.unit.TestUnitContractFactory.legacyFacade());
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

    private static SalesOrderItem item(String quantity, String unit) {
        SalesOrderItem item = new SalesOrderItem();
        item.setQuantity(new BigDecimal(quantity));
        item.setUnit(unit);
        return item;
    }

    private static ProductType product(String unit, String gramsPerUnit) {
        ProductType product = new ProductType();
        product.setUnit(unit);
        product.setGramsPerUnit(gramsPerUnit == null ? null : new BigDecimal(gramsPerUnit));
        return product;
    }
}
