package com.cretas.aims.entity.bom;

import com.cretas.aims.entity.bom.BomChangeLog.ChangeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P1-9 BOM 变更痕迹 schema test.
 */
@DisplayName("BomChangeLog schema (P1-9)")
class BomChangeLogSchemaTest {

    @Test
    void shouldHaveFactoryId() {
        assertDoesNotThrow(() -> BomChangeLog.class.getDeclaredField("factoryId"));
    }

    @Test
    void shouldHaveBomRecipeId() {
        assertDoesNotThrow(() -> BomChangeLog.class.getDeclaredField("bomRecipeId"));
    }

    @Test
    void shouldHaveBomRecipeItemId() {
        assertDoesNotThrow(() -> BomChangeLog.class.getDeclaredField("bomRecipeItemId"));
    }

    @Test
    void shouldHaveChangeType() {
        assertDoesNotThrow(() -> BomChangeLog.class.getDeclaredField("changeType"));
    }

    @Test
    void shouldHaveOldValueAndNewValue() {
        assertDoesNotThrow(() -> BomChangeLog.class.getDeclaredField("oldValue"));
        assertDoesNotThrow(() -> BomChangeLog.class.getDeclaredField("newValue"));
    }

    @Test
    void shouldHaveChangedByAndReason() {
        assertDoesNotThrow(() -> BomChangeLog.class.getDeclaredField("changedBy"));
        assertDoesNotThrow(() -> BomChangeLog.class.getDeclaredField("changedByName"));
        assertDoesNotThrow(() -> BomChangeLog.class.getDeclaredField("changeReason"));
    }

    @Test
    void changeType_shouldHaveThreeValues() {
        ChangeType[] values = ChangeType.values();
        assertEquals(3, values.length);
    }
}
