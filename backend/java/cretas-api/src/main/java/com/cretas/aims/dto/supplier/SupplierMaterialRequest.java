package com.cretas.aims.dto.supplier;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class SupplierMaterialRequest {
    @NotBlank(message = "materialTypeId 不能为空")
    private String materialTypeId;
    @Size(max = 100, message = "供应商料号不能超过100字符")
    private String supplierMaterialCode;
    @PositiveOrZero(message = "默认采购价不能为负数")
    private BigDecimal defaultPurchasePrice;
    @Pattern(regexp = "^[A-Z]{3}$", message = "币种必须是3位大写代码")
    private String currency;
    @Size(max = 20, message = "采购单位不能超过20字符")
    private String purchaseUnit;
    @Positive(message = "最小起订量必须大于0")
    private BigDecimal minOrderQuantity;
    @PositiveOrZero(message = "交期天数不能为负数")
    private Integer leadTimeDays;
    private Boolean preferred;
    private Boolean active;
    private Long version;
}
