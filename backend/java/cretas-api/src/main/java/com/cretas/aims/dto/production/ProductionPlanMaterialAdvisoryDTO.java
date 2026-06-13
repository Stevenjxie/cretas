package com.cretas.aims.dto.production;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Production plan material advisory")
public class ProductionPlanMaterialAdvisoryDTO {

    private String planId;
    private String planNumber;
    private boolean hasWarning;
    private String message;
    private List<Item> warnings;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Material shortage advisory item")
    public static class Item {
        private String materialTypeId;
        private String materialName;
        private BigDecimal requiredQuantity;
        private BigDecimal availableQuantity;
        private BigDecimal shortageQuantity;
        private String unit;
        private String message;
    }
}
