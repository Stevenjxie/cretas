package com.cretas.aims.service.restock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RestockUnitConverter kg↔盒")
class RestockUnitConverterTest {

    @Test
    @DisplayName("kgToBox: 540kg / 120g每盒 = 4500盒")
    void kgToBox_normal() {
        BigDecimal box = RestockUnitConverter.kgToBox(new BigDecimal("540"), new BigDecimal("120"));
        assertEquals(0, new BigDecimal("4500.00").compareTo(box));
    }

    @Test
    @DisplayName("kgToBox: gramsPerUnit null → null (无法换算)")
    void kgToBox_nullGrams() {
        assertNull(RestockUnitConverter.kgToBox(new BigDecimal("540"), null));
    }

    @Test
    @DisplayName("kgToBox: gramsPerUnit <=0 → null")
    void kgToBox_zeroGrams() {
        assertNull(RestockUnitConverter.kgToBox(new BigDecimal("540"), BigDecimal.ZERO));
    }

    @Test
    @DisplayName("kgToBox: kg null → null")
    void kgToBox_nullKg() {
        assertNull(RestockUnitConverter.kgToBox(null, new BigDecimal("120")));
    }
}
