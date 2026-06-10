package com.cretas.aims.dto.pricing;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.Data;

import java.math.BigDecimal;

/**
 * SP5: 更新工厂毛利红线配置请求.
 *
 * <p>所有字段可空, null = 不修改该字段 (PATCH 语义)。{@code targetGrossMargin}
 * 仍为 0-1 小数口径。
 */
@Data
@Schema(description = "SP5: 工厂毛利红线配置更新请求 (null = 不修改)")
public class UpdateGrossMarginConfigRequest {

    @DecimalMin(value = "0.0000", inclusive = false, message = "目标毛利率必须 > 0")
    @DecimalMax(value = "0.9999", message = "目标毛利率必须 < 1 (即 < 100%)")
    @Schema(description = "目标毛利率 (0-1 小数, e.g. 0.30 = 30%)", example = "0.30")
    private BigDecimal targetGrossMargin;

    @Schema(description = "是否启用")
    private Boolean isActive;

    @Schema(description = "备注 / 描述")
    private String description;
}
