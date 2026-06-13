package com.cretas.aims.dto.production;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductionWarehouseReceiptRequest {

    @NotBlank(message = "idempotencyKey 不能为空")
    @Size(max = 128, message = "idempotencyKey 不能超过128字符")
    private String idempotencyKey;

    @NotNull(message = "仓库实收数量不能为空")
    @DecimalMin(value = "0.0001", message = "仓库实收数量必须大于0")
    private BigDecimal receivedQuantity;

    @Size(max = 20, message = "单位不能超过20字符")
    private String quantityUnit;

    @Size(max = 64, message = "差异原因不能超过64字符")
    private String varianceReason;

    @Size(max = 30, message = "责任归属不能超过30字符")
    private String responsibilitySide;

    @Size(max = 1000, message = "差异说明不能超过1000字符")
    private String varianceNote;
}
