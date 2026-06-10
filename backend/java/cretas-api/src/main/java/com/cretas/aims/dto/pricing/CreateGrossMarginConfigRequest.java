package com.cretas.aims.dto.pricing;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * SP5: 创建工厂毛利红线配置请求.
 *
 * <p>{@code targetGrossMargin} 是 0-1 之间的小数 (e.g. 0.30 = 30%)，与
 * {@link com.cretas.aims.entity.pricing.FactoryGrossMarginConfig#getTargetGrossMargin()}
 * 口径一致。前端以百分比展示/录入 (30)，提交前换算为小数 (0.30)。
 */
@Data
@Schema(description = "SP5: 工厂毛利红线配置创建请求")
public class CreateGrossMarginConfigRequest {

    @Schema(description = "产品类型ID, null=工厂全局默认红线")
    private String productTypeId;

    @NotNull(message = "目标毛利率不能为空")
    @DecimalMin(value = "0.0000", inclusive = false, message = "目标毛利率必须 > 0")
    @DecimalMax(value = "0.9999", message = "目标毛利率必须 < 1 (即 < 100%)")
    @Schema(description = "目标毛利率 (0-1 小数, e.g. 0.30 = 30%)", example = "0.30")
    private BigDecimal targetGrossMargin;

    @Schema(description = "是否启用", defaultValue = "true")
    private Boolean isActive = Boolean.TRUE;

    @Schema(description = "备注 / 描述")
    private String description;
}
