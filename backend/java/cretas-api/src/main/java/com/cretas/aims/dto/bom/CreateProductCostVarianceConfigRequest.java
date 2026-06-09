package com.cretas.aims.dto.bom;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * SP3: 创建产品成本超支阈值配置请求.
 */
@Data
@Schema(description = "SP3: 产品成本超支阈值配置创建请求")
public class CreateProductCostVarianceConfigRequest {

    @Schema(description = "产品类型ID, null=工厂全局默认配置")
    private String productTypeId;

    @NotNull(message = "阈值不能为空")
    @DecimalMin(value = "0.00", message = "阈值必须 >= 0")
    @DecimalMax(value = "999.99", message = "阈值不超过 999.99%")
    @Schema(description = "超支预警阈值 (%)", example = "10.00")
    private BigDecimal varianceThreshold;

    @Schema(description = "是否启用", defaultValue = "true")
    private Boolean isActive = Boolean.TRUE;

    @Schema(description = "备注")
    private String remark;
}
