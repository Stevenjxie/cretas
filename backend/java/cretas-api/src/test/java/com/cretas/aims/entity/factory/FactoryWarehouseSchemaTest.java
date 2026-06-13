package com.cretas.aims.entity.factory;

import com.cretas.aims.entity.factory.FactoryWarehouse.WarehouseType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * P1-4 双仓体系 schema test — FactoryWarehouse entity 存在 + WarehouseType enum 含 3 值.
 */
@DisplayName("FactoryWarehouse schema (P1-4)")
class FactoryWarehouseSchemaTest {

    @Test
    void shouldHaveFactoryIdField() {
        assertDoesNotThrow(() -> FactoryWarehouse.class.getDeclaredField("factoryId"));
    }

    @Test
    void shouldHaveCodeField() {
        assertDoesNotThrow(() -> FactoryWarehouse.class.getDeclaredField("code"));
    }

    @Test
    void shouldHaveNameField() {
        assertDoesNotThrow(() -> FactoryWarehouse.class.getDeclaredField("name"));
    }

    @Test
    void shouldHaveTypeField() {
        assertDoesNotThrow(() -> FactoryWarehouse.class.getDeclaredField("type"));
    }

    @Test
    void warehouseTypeEnum_shouldHave15Values() {
        // SP7 added SALTED (盐化仓) to WarehouseType enum, expanding count from 13 → 14.
        // Sprint 4 W1 W-CLASS-1: expanded from 3 legacy types (LOGISTICS/WORKSHOP/OTHER, now @Deprecated)
        // to 13 total with 10 new phase-semantic types (RAW/WIP/etc). SP7 §3.1 added SALTED = 14.
        WarehouseType[] values = WarehouseType.values();
        assertEquals(15, values.length);
        // Legacy 3 (backwards-compat, deprecated)
        assertNotNull(WarehouseType.valueOf("LOGISTICS"));
        assertNotNull(WarehouseType.valueOf("WORKSHOP"));
        assertNotNull(WarehouseType.valueOf("OTHER"));
        // SP7 §3.1: 盐化仓 - 盐水/腌制工序专属仓库
        assertNotNull(WarehouseType.valueOf("SALTED"));
        assertNotNull(WarehouseType.valueOf("RD"));
    }
}
