package com.cretas.aims.dto.factory;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 半成品盘点明细行更新 DTO (镜像 SP7 {@link StocktakeItemUpdateDTO})。
 */
@Data
public class SemiFinishedStocktakeItemUpdateDTO {

    @NotBlank(message = "明细行 ID 不能为空")
    private String itemId;

    @NotNull(message = "实盘数量不能为空")
    @DecimalMin(value = "0", message = "实盘数量不能为负")
    private BigDecimal actualQty;

    private String photoUrls; // JSON 数组字符串

    private String notes;
}
