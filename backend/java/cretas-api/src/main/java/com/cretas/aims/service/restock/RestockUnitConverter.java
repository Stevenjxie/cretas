package com.cretas.aims.service.restock;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 备货看板单位换算: kg ↔ 盒 (份). 盒 = kg × 1000 / gramsPerUnit. */
public final class RestockUnitConverter {

    private RestockUnitConverter() {}

    /**
     * kg 折算为盒。
     * @return 盒数 (scale=2, HALF_UP); 若 kg 或 gramsPerUnit 缺失/非正 → null
     *         (调用方据此显警告, 不静默算错)。
     */
    public static BigDecimal kgToBox(BigDecimal kg, BigDecimal gramsPerUnit) {
        if (kg == null || gramsPerUnit == null || gramsPerUnit.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return kg.multiply(BigDecimal.valueOf(1000))
                 .divide(gramsPerUnit, 2, RoundingMode.HALF_UP);
    }
}
