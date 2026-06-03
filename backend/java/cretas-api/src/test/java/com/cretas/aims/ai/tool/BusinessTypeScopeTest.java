package com.cretas.aims.ai.tool;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BusinessTypeScopeTest {
    @Test
    void restaurantFactory_allowsNullCommonRestaurant_excludesManufacturing() {
        assertTrue(BusinessTypeScope.isCompatible(null, "RESTAURANT"));
        assertTrue(BusinessTypeScope.isCompatible("COMMON", "RESTAURANT"));
        assertTrue(BusinessTypeScope.isCompatible("RESTAURANT", "RESTAURANT"));
        assertFalse(BusinessTypeScope.isCompatible("MANUFACTURING", "RESTAURANT"));
    }
    @Test
    void nonRestaurantFactory_excludesOnlyRestaurant() {
        assertFalse(BusinessTypeScope.isCompatible("RESTAURANT", "FACTORY"));
        assertTrue(BusinessTypeScope.isCompatible("MANUFACTURING", "FACTORY"));
        assertTrue(BusinessTypeScope.isCompatible("COMMON", "FACTORY"));
        assertTrue(BusinessTypeScope.isCompatible(null, "FACTORY"));
        assertTrue(BusinessTypeScope.isCompatible("MANUFACTURING", "COMMON"));
    }
}
