package com.cretas.aims.dto.inventory;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Warehouse confirmation of one partial customer-supplied material receipt. */
@Data
public class CustomerSuppliedMaterialReceiptRequest {

    @NotBlank(message = "幂等键不能为空")
    @Size(max = 64, message = "幂等键不能超过64个字符")
    private String idempotencyKey;

    @NotNull(message = "实收数量不能为空")
    @DecimalMin(value = "0.01", message = "实收数量必须不小于0.01")
    @Digits(integer = 8, fraction = 2, message = "实收数量最多8位整数和2位小数")
    private BigDecimal receivedQuantity;

    /**
     * Optional canonical/display input from the UI. The task unit remains authoritative;
     * when supplied this value must normalize to the same inventory unit.
     */
    @Size(max = 20, message = "计量单位不能超过20个字符")
    private String unit;

    private LocalDate productionDate;
    private LocalDate expireDate;

    @Size(max = 100, message = "客户批次号不能超过100个字符")
    private String externalBatchNumber;

    @Size(max = 200, message = "产地不能超过200个字符")
    private String originPlace;

    @Size(max = 500, message = "备注不能超过500个字符")
    private String notes;
}
