package com.cretas.aims.dto.finance;

import com.cretas.aims.entity.enums.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class PayableSettlementRequest {

    @NotBlank(message = "供应商不能为空")
    private String supplierId;

    @NotNull(message = "付款金额不能为空")
    @DecimalMin(value = "0.01", message = "付款金额必须大于0")
    private BigDecimal amount;

    @NotBlank(message = "币种不能为空")
    @Pattern(regexp = "^[A-Za-z]{3}$", message = "币种必须是3位ISO代码")
    private String currencyCode;

    @NotNull(message = "付款方式不能为空")
    private PaymentMethod paymentMethod;

    @NotBlank(message = "付款凭证号不能为空")
    @Size(max = 100, message = "付款凭证号不能超过100字符")
    private String paymentReference;

    @NotBlank(message = "幂等键不能为空")
    @Size(max = 191, message = "幂等键不能超过191字符")
    private String idempotencyKey;

    @Size(max = 500, message = "备注不能超过500字符")
    private String remark;
}
