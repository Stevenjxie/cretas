package com.cretas.aims.dto.bom;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** One proposed substitute under a single BOM requirement parent. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BomSubstituteInput {

    @NotBlank(message = "替代物料不能为空")
    private String materialTypeId;

    /**
     * Substitute quantity per one parent-equivalent quantity. Null means the 1:1 default and is
     * accepted only when both sides use the same canonical unit.
     */
    @Positive(message = "替代换算系数必须大于0")
    private BigDecimal conversionFactor;
}
