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

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreatePurchaseOrderRequest {

    @NotBlank(message = "供应商ID不能为空")
    @Size(max = 191, message = "供应商ID长度不能超过191个字符")
    private String supplierId;

    /** 采购类型: DIRECT / HQ_UNIFIED / URGENT */
    @Size(max = 50, message = "采购类型长度不能超过50个字符")
    private String purchaseType = "DIRECT";

    @NotNull(message = "下单日期不能为空")
    private LocalDate orderDate;

    private LocalDate expectedDeliveryDate;

    @Size(max = 5000, message = "备注长度不能超过5000个字符")
    private String remark;

    /**
     * W-12 fix (Round 15): optional SO id for cross-module tracking
     * ("按销售订单做定点追踪"). When set, SO detail page's "关联采购" tab shows this PO.
     * FE list.vue previously had a `relatedSalesOrderId` field that got stripped from
     * payload — now renamed + passed through to this field.
     */
    @Size(max = 191, message = "销售订单ID长度不能超过191个字符")
    private String salesOrderId;

    @Valid
    @NotEmpty(message = "采购行项目不能为空")
    private List<PurchaseOrderItemDTO> items;

    /**
     * SP6 — 合同编号（对应纸质合同 / 框架合同号）。选填，null 表示无合同号。
     * 客户原话 [00:00]: "采购合同号"。
     */
    @Size(max = 100, message = "合同号长度不能超过100个字符")
    private String contractNumber;

    /**
     * SP6 — 结算方式（预付/月结/账期等）。选填，null 表示使用默认结算方式。
     * 合法值: PREPAID / CREDIT_FIRST / NO_INVOICE / MONTHLY / CREDIT_PERIOD / IMMEDIATE
     */
    @Size(max = 32, message = "结算方式长度不能超过32个字符")
    private String settlementType;

    /**
     * SP6 — 开票提醒天数（收货后 N 天未收到发票则提醒）。
     * null = 使用工厂默认；0 = 不提醒。
     */
    private Integer invoiceReminderDays;

    /** 乐观锁版本号 (编辑时必传, 来自 GET 响应); mismatch → 409 Conflict */
    private Long version;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PurchaseOrderItemDTO {

        @NotBlank(message = "原料类型ID不能为空")
        @Size(max = 191, message = "原料类型ID长度不能超过191个字符")
        private String materialTypeId;

        @Size(max = 200, message = "原料名称长度不能超过200个字符")
        private String materialName;

        @NotNull(message = "数量不能为空")
        @DecimalMin(value = "0.01", message = "采购数量必须大于0")
        private BigDecimal quantity;

        @NotBlank(message = "单位不能为空")
        @Size(max = 20, message = "单位长度不能超过20个字符")
        private String unit;

        private BigDecimal unitPrice;

        private BigDecimal taxRate;

        @Size(max = 5000, message = "备注长度不能超过5000个字符")
        private String remark;

        @Size(max = 500, message = "规格长度不能超过500个字符")
        private String specification;

        private BigDecimal boxQuantity;
    }
}
