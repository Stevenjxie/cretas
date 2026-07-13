package com.cretas.aims.dto.material;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 续入到已有批次请求 (方案A 严格匹配续入, 六扇门 F006 采购补货).
 *
 * <p>把本次到货数量并进指定的已有批次, 沿用该批次单价/保质期/供应商, 不重算成本口径。
 * 发起单信息 (sourceDocType/sourceDocId) 记入续入审计流水供溯源。
 */
@Data
@Schema(description = "续入到已有批次请求")
public class ReplenishMaterialBatchRequest {

    @Schema(description = "本次续入数量 (必须 > 0)")
    @NotNull(message = "续入数量不能为空")
    @DecimalMin(value = "0.01", message = "续入数量必须大于0")
    private BigDecimal addQuantity;

    @Schema(description = "本次到货发起单类型 (如 采购单)")
    private String sourceDocType;

    @Schema(description = "本次到货发起单ID")
    private String sourceDocId;

    @Schema(description = "备注")
    private String note;
}
