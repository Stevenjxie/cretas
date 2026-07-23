package com.cretas.aims.dto.processentry;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/** 正式报工提交时生产库不足的结构化明细。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductionStockShortageDTO {

    private BigDecimal required;
    private BigDecimal available;
    private BigDecimal shortage;
    private String unit;
    private List<Item> items;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private String materialTypeId;
        private String materialName;
        private String sourceType;
        private BigDecimal required;
        private BigDecimal available;
        private BigDecimal shortage;
        private String unit;

        public Item(
                String materialTypeId,
                BigDecimal required,
                BigDecimal available,
                BigDecimal shortage,
                String unit) {
            this(materialTypeId, null, null, required, available, shortage, unit);
        }
    }
}
