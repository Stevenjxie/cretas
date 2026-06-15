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
public class CreateDeliveryRequest {

    /** 关联的销售订单ID（可选，支持无单出库） */
    @Size(max = 191, message = "销售订单ID长度不能超过191个字符")
    private String salesOrderId;

    @NotBlank(message = "客户ID不能为空")
    @Size(max = 191, message = "客户ID长度不能超过191个字符")
    private String customerId;

    @NotNull(message = "发货日期不能为空")
    private LocalDate deliveryDate;

    @Size(max = 500, message = "发货地址长度不能超过500个字符")
    private String deliveryAddress;

    @Size(max = 200, message = "物流公司长度不能超过200个字符")
    private String logisticsCompany;

    @Size(max = 100, message = "物流单号长度不能超过100个字符")
    private String trackingNumber;

    @Size(max = 5000, message = "备注长度不能超过5000个字符")
    private String remark;

    @Valid
    @NotEmpty(message = "发货行项目不能为空")
    private List<DeliveryItemDTO> items;

    /**
     * Round 9 Fix (R8-α Gap #1): Canvas V3 dynamic fields map. Customer-configured
     * fields (运输温度, 司机姓名, 车牌号, etc.) land here and are persisted via
     * DynamicFieldService after the delivery record is saved. Previously the entire
     * delivery path silently dropped customFields — the frontend Canvas form would
     * submit them but the backend DTO didn't have the slot. This is the SalesDelivery
     * template pattern for the 16 other modules that still need per-method integration.
     */
    private Map<String, Object> customFields;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeliveryItemDTO {

        @NotBlank(message = "产品ID不能为空")
        @Size(max = 191, message = "产品ID长度不能超过191个字符")
        private String productTypeId;

        @Size(max = 200, message = "产品名称长度不能超过200个字符")
        private String productName;

        @NotNull(message = "发货数量不能为空")
        @DecimalMin(value = "0.01", message = "发货数量必须大于0")
        private BigDecimal deliveredQuantity;

        @NotBlank(message = "单位不能为空")
        @Size(max = 20, message = "单位长度不能超过20个字符")
        private String unit;

        private BigDecimal unitPrice;

        /**
         * T4-D5 (issue #553): WH-LOG (总仓) / WH-WKS (线边仓) — source warehouse
         * code preserved from the originating SalesOrderItem. Nullable for legacy
         * 无单出库 path + drafts. SalesServiceImpl.createDeliveryRecord propagates
         * this directly to SalesDeliveryItem.sourceWarehouseCode without filtering
         * inventory allocation (allocation refactor deferred per PR #553 scope).
         */
        @Size(max = 20, message = "来源仓库code长度不能超过20")
        private String sourceWarehouseCode;

        @Size(max = 5000, message = "备注长度不能超过5000个字符")
        private String remark;
    }
}
