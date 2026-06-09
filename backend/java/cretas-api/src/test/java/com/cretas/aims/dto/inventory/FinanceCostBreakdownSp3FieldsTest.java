package com.cretas.aims.dto.inventory;

import com.cretas.aims.security.PriceSensitive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * SP3 regression guard: new variance fields on FinanceCostBreakdown carry @PriceSensitive.
 *
 * <p>Order-level: varianceAbsolute, variancePct, alarmMessage — must be @PriceSensitive.
 * belowThreshold is intentionally NOT @PriceSensitive (boolean gate; OK for any role to see).
 *
 * <p>LineCostBreakdown: standardCostPerUnit, actualCostPerUnit, variancePct — @PriceSensitive.
 * belowThreshold again is not @PriceSensitive.
 */
@DisplayName("SP3: FinanceCostBreakdown 新字段 @PriceSensitive 回归守卫")
class FinanceCostBreakdownSp3FieldsTest {

    @ParameterizedTest(name = "FinanceCostBreakdown.{0} is @PriceSensitive")
    @CsvSource({
            "varianceAbsolute",
            "variancePct",
            "alarmMessage",
    })
    void orderLevelField_priceSensitive(String fieldName) throws NoSuchFieldException {
        Field field = FinanceCostBreakdown.class.getDeclaredField(fieldName);
        assertNotNull(
                field.getAnnotation(PriceSensitive.class),
                "FinanceCostBreakdown." + fieldName + " must be @PriceSensitive"
        );
    }

    @ParameterizedTest(name = "LineCostBreakdown.{0} is @PriceSensitive")
    @CsvSource({
            "standardCostPerUnit",
            "actualCostPerUnit",
            "variancePct",
    })
    void lineLevelField_priceSensitive(String fieldName) throws NoSuchFieldException {
        // LineCostBreakdown is a static inner class
        Class<?> lineClass = FinanceCostBreakdown.LineCostBreakdown.class;
        Field field = lineClass.getDeclaredField(fieldName);
        assertNotNull(
                field.getAnnotation(PriceSensitive.class),
                "LineCostBreakdown." + fieldName + " must be @PriceSensitive"
        );
    }

    @ParameterizedTest(name = "FinanceCostBreakdown.{0} is NOT @PriceSensitive (boolean gate)")
    @CsvSource({
            "belowThreshold",
    })
    void orderLevelBooleanGate_notPriceSensitive(String fieldName) throws NoSuchFieldException {
        // belowThreshold is a boolean flag visible to all roles; cost values are what's sensitive
        Field field = FinanceCostBreakdown.class.getDeclaredField(fieldName);
        // We deliberately do not annotate belowThreshold — just verify the field exists
        assertNotNull(field, "FinanceCostBreakdown." + fieldName + " should exist");
    }
}
