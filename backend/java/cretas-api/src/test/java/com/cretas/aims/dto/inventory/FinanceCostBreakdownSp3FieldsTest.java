package com.cretas.aims.dto.inventory;

import com.cretas.aims.security.PriceSensitive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * SP3 regression guard: new variance fields on FinanceCostBreakdown carry @PriceSensitive.
 *
 * <p>Order-level: varianceAbsolute, variancePct — must be @PriceSensitive (numeric cost values).
 * alarmMessage is intentionally NOT @PriceSensitive: it is a generic warning string
 * ("成本超出标准阈值，请关注") with no embedded cost figures or percentages, so it is
 * safe to expose to sales roles. The sensitive numeric data lives in variancePct /
 * varianceAbsolute which are separately @PriceSensitive-gated.
 * belowThreshold is also NOT @PriceSensitive (boolean status gate).
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

    @ParameterizedTest(name = "FinanceCostBreakdown.{0} is NOT @PriceSensitive (non-sensitive status/text field)")
    @CsvSource({
            "belowThreshold",
            "alarmMessage",
    })
    void orderLevelNonSensitiveField_notPriceSensitive(String fieldName) throws NoSuchFieldException {
        // belowThreshold: boolean flag visible to all roles; cost values are what's sensitive.
        // alarmMessage: generic warning text ("成本超出标准阈值，请关注") — no embedded ¥ amounts or
        // percentages, so safe for sales roles. Sensitive numeric data is in variancePct /
        // varianceAbsolute which are separately @PriceSensitive-gated.
        Field field = FinanceCostBreakdown.class.getDeclaredField(fieldName);
        assertNull(
                field.getAnnotation(PriceSensitive.class),
                "FinanceCostBreakdown." + fieldName + " must NOT be @PriceSensitive"
        );
    }
}
