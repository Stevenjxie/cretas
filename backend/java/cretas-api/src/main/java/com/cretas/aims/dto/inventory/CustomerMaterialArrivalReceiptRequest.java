package com.cretas.aims.dto.inventory;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Warehouse records actual facts against a customer material arrival notice. */
@Data
public class CustomerMaterialArrivalReceiptRequest {

    @NotBlank(message = "幂等键不能为空")
    @Size(max = 64, message = "幂等键不能超过64个字符")
    private String idempotencyKey;

    @NotBlank(message = "实际原料不能为空")
    @Size(max = 191, message = "原料ID不能超过191个字符")
    private String materialTypeId;

    @NotBlank(message = "实际入库仓库不能为空")
    @Size(max = 64, message = "仓库ID不能超过64个字符")
    private String warehouseId;

    @NotNull(message = "实收数量不能为空")
    @DecimalMin(value = "0.01", message = "实收数量必须不小于0.01")
    @Digits(integer = 8, fraction = 2, message = "实收数量最多8位整数和2位小数")
    private BigDecimal receivedQuantity;

    @Size(max = 20, message = "计量单位不能超过20个字符")
    private String unit;

    private LocalDate productionDate;
    private LocalDate expireDate;

    @Size(max = 100, message = "客户批次号不能超过100个字符")
    private String externalBatchNumber;

    @Size(max = 100, message = "合同号不能超过100个字符")
    private String contractNumber;

    @Size(max = 100, message = "厂号不能超过100个字符")
    private String factoryNumber;

    @Min(value = 0, message = "件数不能为负数")
    private Integer boxCount;

    @Size(max = 200, message = "产地不能超过200个字符")
    private String originPlace;

    @Size(max = 500, message = "备注不能超过500个字符")
    private String notes;

    /** True only when warehouse confirms no more truck loads are expected for this notice. */
    private Boolean completeNotice = false;
}
