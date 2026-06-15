package com.cretas.aims.dto.inventory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateReturnOrderRequest {

    @NotBlank(message = "退货类型不能为空")
    @Size(max = 50, message = "退货类型长度不能超过50个字符")
    private String returnType; // PURCHASE_RETURN | SALES_RETURN

    @NotBlank(message = "交易对手ID不能为空")
    @Size(max = 191, message = "交易对手ID长度不能超过191个字符")
    private String counterpartyId;

    @Size(max = 191, message = "源订单ID长度不能超过191个字符")
    private String sourceOrderId;

    @NotNull(message = "退货日期不能为空")
    private LocalDate returnDate;

    @Size(max = 1000, message = "退货原因长度不能超过1000个字符")
    private String reason;

    /**
     * T-RTA business logic (issue #571): TRUE=有食物 (库存入库总仓 → AR 冲减), FALSE=无食物 (直接退款无库存动作).
     * Nullable in DTO (default TRUE in service if absent) for backward-compat with legacy callers.
     * Customer transcript (第四次:956-1037) explicitly distinguishes the two flows.
     */
    private Boolean withGoods;

    @Size(max = 5000, message = "备注长度不能超过5000个字符")
    private String remark;

    @Valid
    @NotEmpty(message = "退货行项目不能为空")
    private List<ReturnOrderItemDTO> items;

    /**
     * Round 11 T2 — Canvas Integration Template slot for factory-configured
     * dynamic fields. Fields like 退货照片, 客诉单号, 退货责任方 land here and
     * are persisted to cf_* columns on return_orders via DynamicFieldService.
     */
    private Map<String, Object> customFields;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReturnOrderItemDTO {

        @Size(max = 191, message = "原料类型ID长度不能超过191个字符")
        private String materialTypeId;

        @Size(max = 191, message = "产品类型ID长度不能超过191个字符")
        private String productTypeId;

        @Size(max = 200, message = "品项名称长度不能超过200个字符")
        private String itemName;

        @NotNull(message = "数量不能为空")
        @DecimalMin(value = "0.01", message = "退货数量必须大于0")
        private BigDecimal quantity;

        private BigDecimal unitPrice;

        @Size(max = 100, message = "批次号长度不能超过100个字符")
        private String batchNumber;

        @Size(max = 1000, message = "原因长度不能超过1000个字符")
        private String reason;
    }
}
